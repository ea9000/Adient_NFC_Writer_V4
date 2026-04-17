package com.adient.nfcv4

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class MainViewModel : ViewModel() {

    // ── Public data types ────────────────────────────────────────────────────

    /**
     * A single pick-list file entry, as it appears in the second (file) spinner.
     *
     * @param label    Short display string, e.g. "25.07.08 #1 Seq 0004319855-0004319870"
     * @param basename Full filename without the .txt extension, used for download + delete.
     */
    data class PickListFile(val label: String, val basename: String)

    // ── LiveData ─────────────────────────────────────────────────────────────

    /** Raw station ID prefixes, e.g. ["4.1_Blenden_FS", "4.1_lehne_FS"] (no placeholder). */
    private val _stationPrefixes = MutableLiveData<List<String>>(emptyList())
    val stationPrefixes: LiveData<List<String>> = _stationPrefixes

    /**
     * Station display names for the first spinner, underscores replaced by spaces.
     * Index 0 is always the "-- Select Station --" placeholder, so
     * displayName[N] corresponds to stationPrefixes[N-1].
     */
    private val _stationDisplayNames = MutableLiveData<List<String>>(listOf("-- Select Station --"))
    val stationDisplayNames: LiveData<List<String>> = _stationDisplayNames

    /** Text content of the currently selected pick-list file. */
    private val _selectedStationText = MutableLiveData<String>()
    val selectedStationText: LiveData<String> = _selectedStationText

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val _errorMessage = MutableLiveData<String>()
    val errorMessage: LiveData<String> = _errorMessage

    // ── Internal state ────────────────────────────────────────────────────────

    private var baseUrl: String = ""

    /** All raw file basenames fetched from the server (no .txt extension). */
    private val rawFileBasenames = mutableListOf<String>()

    companion object {
        private const val TAG = "MainViewModel"

        /**
         * Matches the date segment that separates the station prefix from the
         * date+sequence suffix in a pick-list filename.
         * Example: "_25.07.08_" inside "4.1_Blenden_FS_25.07.08_Seq_0004319855-0004319870".
         */
        private val DATE_PATTERN = Regex("_\\d{2}\\.\\d{2}\\.\\d{2}_")
    }

    // ── Public API ───────────────────────────────────────────────────────────

    fun setBaseUrl(url: String) {
        baseUrl = url.trimEnd('/') + "/"
    }

    /**
     * Return the files belonging to [stationPrefix], sorted, each with a short label.
     *
     * Label format: "YY.MM.DD #N Rest of name" where N is the 1-based index within
     * the station's files.  Example: "25.07.08 #1 Seq 0004319855-0004319870".
     */
    fun filesForStation(stationPrefix: String): List<PickListFile> =
        rawFileBasenames
            .filter { stationPrefixOf(it) == stationPrefix }
            .sorted()
            .mapIndexed { index, basename ->
                val match = DATE_PATTERN.find(basename)
                val label = if (match != null) {
                    // Characters between the surrounding underscores → "25.07.08"
                    val date   = basename.substring(match.range.first + 1, match.range.last)
                    // Everything after the trailing underscore, underscores → spaces
                    val suffix = basename.substring(match.range.last + 1).replace('_', ' ')
                    "$date #${index + 1} ($suffix)"
                } else {
                    "#${index + 1} $basename"
                }
                PickListFile(label = label, basename = basename)
            }

    /**
     * Fetch the server's file listing, extract unique station prefixes, and update
     * [stationPrefixes] / [stationDisplayNames].
     *
     * Cache-busting: appends ?t=<epoch-ms> so CDN/proxy caches never serve a stale
     * directory listing after a file has been deleted.
     */
    fun fetchAndExtractStations() {
        if (baseUrl.isEmpty()) {
            Log.w(TAG, "fetchAndExtractStations: baseUrl is empty — skipping")
            return
        }
        _isLoading.postValue(true)
        rawFileBasenames.clear()

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fetchUrl = "${baseUrl}?t=${System.currentTimeMillis()}"
                Log.d(TAG, "fetchAndExtractStations: fetching $fetchUrl")
                val url = URL(fetchUrl)
                (url.openConnection() as? HttpURLConnection)?.run {
                    useCaches = false
                    requestMethod = "GET"
                    setRequestProperty("Cache-Control", "no-cache, no-store")
                    val code = responseCode
                    Log.d(TAG, "fetchAndExtractStations: HTTP $code")
                    if (code == HttpURLConnection.HTTP_OK) {
                        val html = inputStream.bufferedReader().use { it.readText() }
                        Log.d(TAG, "fetchAndExtractStations: received ${html.length} chars of HTML")

                        // Use a[href] (all anchors) rather than pre a[href] (Apache-only)
                        // so the selector works across Apache, IIS, nginx, and custom servers.
                        val allLinks = Jsoup.parse(html).select("a[href]")
                        Log.d(TAG, "fetchAndExtractStations: found ${allLinks.size} total <a href> elements")

                        allLinks.forEach { link ->
                            val href = link.attr("href")
                            if (href.endsWith(".txt", ignoreCase = true)) {
                                // Strip any leading path component (e.g. "/picklist/") so
                                // we store only the bare filename without extension, e.g.
                                // "4.1_Blenden_FS_25.07.08_Seq_0004319855-0004319870"
                                val bare = href.substringBeforeLast(".txt")
                                    .substringAfterLast('/')
                                    .substringAfterLast('\\')
                                if (bare.isNotEmpty()) {
                                    Log.d(TAG, "fetchAndExtractStations: found file basename: $bare")
                                    rawFileBasenames += bare
                                }
                            }
                        }
                        Log.d(TAG, "fetchAndExtractStations: total .txt basenames: ${rawFileBasenames.size}")
                    } else {
                        Log.e(TAG, "fetchAndExtractStations: HTTP $code — cannot load file list")
                        withContext(Dispatchers.Main) {
                            _errorMessage.value = "HTTP $code fetching file list"
                        }
                    }
                    disconnect()
                }

                val prefixes     = rawFileBasenames.map { stationPrefixOf(it) }.toSortedSet().toList()
                val displayNames = listOf("-- Select Station --") + prefixes.map { displayNameOf(it) }
                Log.d(TAG, "fetchAndExtractStations: extracted ${prefixes.size} station(s): $prefixes")
                withContext(Dispatchers.Main) {
                    _stationPrefixes.value     = prefixes
                    _stationDisplayNames.value = displayNames
                }
            } catch (e: Exception) {
                Log.e(TAG, "fetchAndExtractStations: exception — ${e.message}", e)
                withContext(Dispatchers.Main) {
                    _errorMessage.value = "Network Error: ${e.message}"
                }
            } finally {
                withContext(Dispatchers.Main) { _isLoading.value = false }
            }
        }
    }

    /**
     * Download the text content of [basename].txt and push it to [selectedStationText].
     * [basename] is the raw filename without .txt, e.g. "4.1_Blenden_FS_25.07.08_Seq_0004319855-0004319870".
     */
    fun downloadFileContent(basename: String) {
        if (baseUrl.isEmpty() || basename.isEmpty()) return

        _isLoading.value = true
        _errorMessage.value = ""

        viewModelScope.launch(Dispatchers.IO) {
            // ?t= ensures we never get a cached copy of a file that was recently updated
            val txtUrl = "${baseUrl}${basename}.txt?t=${System.currentTimeMillis()}"
            try {
                (URL(txtUrl).openConnection() as? HttpURLConnection)?.run {
                    useCaches = false
                    setRequestProperty("Cache-Control", "no-cache, no-store")
                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        val text = inputStream.bufferedReader().use { it.readText() }
                        withContext(Dispatchers.Main) {
                            _selectedStationText.value = text
                            _isLoading.value = false
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            _errorMessage.value = "HTTP $responseCode loading $basename.txt"
                            _isLoading.value = false
                        }
                    }
                    disconnect()
                }
            } catch (e: IOException) {
                Log.e("ViewModel", "Download failed: $basename.txt", e)
                withContext(Dispatchers.Main) {
                    _errorMessage.value = "Network Error: ${e.message}"
                    _isLoading.value = false
                }
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Extract the station prefix from a raw basename (everything before the date segment). */
    private fun stationPrefixOf(basename: String): String =
        DATE_PATTERN.find(basename)?.let { basename.substring(0, it.range.first) } ?: basename

    /** Convert a raw station prefix to a human-readable display name. */
    private fun displayNameOf(prefix: String): String = prefix.replace('_', ' ')
}
