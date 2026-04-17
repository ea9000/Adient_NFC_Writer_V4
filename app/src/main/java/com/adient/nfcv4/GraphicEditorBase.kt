package com.adient.nfcv4

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.annotation.IdRes
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine


abstract class GraphicEditorBase: AppCompatActivity() {
    @get:LayoutRes
    abstract val layoutId: Int
    @get:IdRes
    abstract val flashButtonId: Int
    @get:IdRes
    abstract val webViewId: Int
    abstract val webViewUrl: String
    protected var mWebView: WebView? = null

    private var lastStationName: String? = null

    // Initialize the ViewModel
    private val viewModel: MainViewModel by viewModels()

    /* ▼▼  INSERT THE BRIDGE HERE  ▼▼ */
    private class JsBridge(
        val onResetRequested: () -> Unit
    ) {
        @android.webkit.JavascriptInterface
        fun resetScreen() { onResetRequested() }
    }
    /* ▲▲  BRIDGE END  ▲▲ */

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(this.layoutId)

        val webView: WebView = findViewById(this.webViewId)
        this.mWebView = webView

        // Setup asset loader to handle local asset paths
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        // Override WebView client
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                onWebViewPageStarted()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                onWebViewPageFinished()
            }
        }

        // WebView - Enable JS
        webView.settings.javaScriptEnabled = true
        // --- JS bridge so the HTML "Reset" button can call Kotlin ---
        webView.addJavascriptInterface(
            JsBridge { lifecycleScope.launch { flushWhiteAndFlash() } },
            "AndroidBridge"
        )
        webView.webChromeClient = WebChromeClient()
        webView.loadUrl(this.webViewUrl)

        val flashButton: Button = findViewById(this.flashButtonId)
        flashButton.setOnClickListener {
            lifecycleScope.launch {
                getAndFlashGraphic()
            }
        }

        // --- START: Code to connect ViewModel ---

        observeViewModel()

        val preferences = Preferences(this)
        val savedUrl = preferences.getPreferences()?.getString(PrefKeys.BaseUrl, "")
        if (!savedUrl.isNullOrEmpty()) {
            viewModel.setBaseUrl(savedUrl)
            viewModel.fetchAndExtractStations()
        } else {
            Toast.makeText(this, "No URL is set. Please go to settings in the main app.", Toast.LENGTH_LONG).show()
        }
        // --- END: Code ---
    }

    private fun observeViewModel() {
        // Both spinners may be absent in layouts that don't include them (e.g. WysiwygEditor).
        val spinnerStation: Spinner? = findViewById(R.id.spinner_stations)
        val spinnerFile: Spinner?    = findViewById(R.id.spinner_files)

        // File spinner is GONE until a station is chosen (GONE collapses it in ConstraintLayout)
        spinnerFile?.visibility = View.GONE

        // ── Station list ──────────────────────────────────────────────────────
        // stationDisplayNames[0] is always "-- Select Station --";
        // stationDisplayNames[N] maps to stationPrefixes[N-1].
        viewModel.stationDisplayNames.observe(this) { displayNames ->
            spinnerStation?.adapter = buildStationAdapter(displayNames)
            // Whenever the station list refreshes, hide the file spinner
            spinnerFile?.visibility = View.GONE
            spinnerFile?.adapter    = null
            lastStationName = null
        }

        // ── File content ──────────────────────────────────────────────────────
        viewModel.selectedStationText.observe(this) { content ->
            val safe = content.replace("`", "\\`")
            mWebView?.evaluateJavascript("setTextContent(`${safe}`);", null)
        }

        // ── Errors ────────────────────────────────────────────────────────────
        viewModel.errorMessage.observe(this) { message ->
            if (message.isNullOrBlank()) return@observe
            if (!message.startsWith("Network") && !message.startsWith("HTTP")) return@observe
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }

        // ── Station spinner → populate file spinner ───────────────────────────
        spinnerStation?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == 0) {
                    // Placeholder — hide file spinner
                    spinnerFile?.visibility = View.GONE
                    spinnerFile?.adapter    = null
                    lastStationName = null
                    return
                }
                // position - 1 because display names list has a leading placeholder
                val prefix = viewModel.stationPrefixes.value?.getOrNull(position - 1) ?: return
                val files  = viewModel.filesForStation(prefix)
                if (files.isEmpty()) {
                    spinnerFile?.visibility = View.GONE
                    spinnerFile?.adapter    = null
                    return
                }
                val adapter = ArrayAdapter(
                    this@GraphicEditorBase,
                    android.R.layout.simple_spinner_item,
                    files.map { it.label }
                )
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinnerFile?.adapter    = adapter
                spinnerFile?.visibility = View.VISIBLE
                // Auto-selects position 0 → triggers spinnerFile.onItemSelected below
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // ── File spinner → download selected file ─────────────────────────────
        spinnerFile?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val stationPos = spinnerStation?.selectedItemPosition ?: return
                if (stationPos == 0) return
                val prefix = viewModel.stationPrefixes.value?.getOrNull(stationPos - 1) ?: return
                val file   = viewModel.filesForStation(prefix).getOrNull(position) ?: return
                lastStationName = file.basename
                Log.d("PickList", "Selected file: ${file.basename}")
                viewModel.downloadFileContent(file.basename)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /**
     * Build an adapter for the station header spinner.
     * The "selected" view (always visible) is bold, 18sp, white — matching the blue header bar.
     * The dropdown items use the standard system style.
     */
    private fun buildStationAdapter(items: List<String>): ArrayAdapter<String> {
        val adapter = object : ArrayAdapter<String>(
            this, android.R.layout.simple_spinner_item, items
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tv = super.getView(position, convertView, parent) as TextView
                tv.setTextColor(Color.WHITE)
                tv.textSize = 18f
                tv.setTypeface(tv.typeface, Typeface.BOLD)
                return tv
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        return adapter
    }

    private fun clearAppCache() {
        try {
            val dir: File = cacheDir
            if (dir.deleteRecursively()) {
                Log.i("Cache", "App cache cleared successfully.")
            } else {
                Log.e("Cache", "Failed to clear app cache.")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun getAndFlashGraphic() {
        val mContext = this
        val imageBytes = this.getBitmapFromWebView(this.mWebView!!)
        @Suppress("UNUSED_VARIABLE")
        val bitmap: Bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        withContext(Dispatchers.IO) {
            openFileOutput(GeneratedImageFilename, Context.MODE_PRIVATE).use { fileOutStream ->
                fileOutStream.write(imageBytes)
                fileOutStream.close()
                val navIntent = Intent(mContext, NfcFlasher::class.java)
                val bundle = Bundle()
                bundle.putString(IntentKeys.GeneratedImgPath, GeneratedImageFilename)

// ▼ NEW: pass the txt file name
                val rawName  = lastStationName
                    ?.substringAfterLast('/')    // remove any folder part like "/picklist/"
                    ?.substringAfterLast('\\')   // just in case of backslashes
                val fileToDelete = if (rawName?.endsWith(".txt", true) == true) rawName else "$rawName.txt"
                Log.d("PickList", "Passing file to delete: $fileToDelete")
                bundle.putString(IntentKeys.PickListFileName, fileToDelete)

                navIntent.putExtras(bundle)
                startActivity(navIntent)

            }
        }
    }

    /* ---------------- white‑flush helper ---------------- */
    private suspend fun flushWhiteAndFlash() {
        val fileName = "white_flush.png"

        // 1) decode bundled PNG (place white_flush.png in res/drawable-nodpi)
        val whiteBmp = withContext(Dispatchers.IO) {
            BitmapFactory.decodeResource(resources, R.drawable.white_flush)
        } ?: return   // abort if not found

        // 2) save to internal storage so NfcFlasher can read it
        withContext(Dispatchers.IO) {
            openFileOutput(fileName, Context.MODE_PRIVATE).use { out ->
                whiteBmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }

        // 3) launch the flasher just like a normal image
        val intent = Intent(this, NfcFlasher::class.java).apply {
            putExtra("RESET_MODE", true)
            putExtra(IntentKeys.GeneratedImgPath, fileName)
        }
        startActivity(intent)
    }
    /* ---------------------------------------------------- */

    protected fun updateCanvasSize() {
        val preferences = Preferences(this)
        val pixelSize = ScreenSizesInPixels[preferences.getScreenSize()]
        this.mWebView?.evaluateJavascript("setDisplaySize(${pixelSize!!.first}, ${pixelSize.second});", null)
    }

    open fun onWebViewPageFinished() {}
    open fun onWebViewPageStarted() {}

    override fun onResume() {
        super.onResume()

        // Re‑query the server every time this screen regains focus
        viewModel.fetchAndExtractStations()
    }


    open suspend fun getBitmapFromWebView(webView: WebView): ByteArray {
        webView.evaluateJavascript(
            "getImgSerializedFromCanvas(undefined, undefined, (output) => window.imgStr = output);",
            null
        )
        delay(1000L)

        return suspendCoroutine<ByteArray> { continuation ->
            webView.evaluateJavascript("window.imgStr;") { bitmapStr ->
                val imageBytes = Base64.decode(bitmapStr, Base64.DEFAULT)
                continuation.resume(imageBytes)
            }
        }
    }
}