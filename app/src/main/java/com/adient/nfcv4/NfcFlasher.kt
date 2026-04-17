package com.adient.nfcv4

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.NfcA
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import waveshare.feng.nfctag.activity.WaveShareHandler
import java.io.IOException

class NfcFlasher : AppCompatActivity(), NfcAdapter.ReaderCallback {

    /* ───────── constants ───────── */
    private companion object {
        const val DELETE_SECRET = "CHANGE_ME_123"
        const val TAG = "NfcFlasher"
    }

    /* ───────── state / refs ─────── */
    private var mIsFlashing = false
        set(v) {
            field = v
            mWhileFlashingArea?.visibility = if (v) View.VISIBLE else View.GONE
            mWhileFlashingArea?.requestLayout()
            mProgressVal = 0
        }

    private var pickListFileToDelete: String? = null
    private var mBitmap: Bitmap? = null
    private var mImgFileUri: Uri? = null
    private var mImgFilePath: String? = null

    private var mNfcAdapter: NfcAdapter? = null
    private var mCurrentProtocol: ProtocolMode = ProtocolMode.AUTO

    /**
     * Reader-mode flags — NFC-A ownership + skip NDEF check so the OS never
     * deactivates the tag's IsoDep layer before we connect.
     */
    private val READER_FLAGS =
        NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK

    private var mNfcCheckHandler: Handler? = null
    private val mNfcCheckIntervalMs = 250L

    private var mWhileFlashingArea: ConstraintLayout? = null
    private var mProgressBar: ProgressBar? = null
    private var mHardwareLabel: TextView? = null
    private var mProgressVal = 0
    private val mProgressCheckInterval = 50L

    /* ───────── onCreate ────────── */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nfc_flasher)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val resetMode = intent.getBooleanExtra("RESET_MODE", false)
        findViewById<Button>(R.id.btn_reset_screen)?.visibility =
            if (resetMode) View.VISIBLE else View.GONE

        val savedUri = savedInstanceState?.getString("serializedGeneratedImgUri")
        mImgFileUri = savedUri?.let { Uri.parse(it) }

        if (mImgFileUri == null) {
            val ex = intent.extras
            mImgFilePath         = ex?.getString(IntentKeys.GeneratedImgPath)
            pickListFileToDelete = ex?.getString(IntentKeys.PickListFileName)
            mImgFileUri = mImgFilePath?.let { Uri.fromFile(getFileStreamPath(it)) }
                ?: Uri.fromFile(getFileStreamPath(GeneratedImageFilename))
        }

        findViewById<ImageView>(R.id.previewImageView).setImageURI(mImgFileUri)
        mBitmap = mImgFileUri?.path?.let { BitmapFactory.decodeFile(it, BitmapFactory.Options()) }

        mWhileFlashingArea = findViewById(R.id.whileFlashingArea)
        mProgressBar       = findViewById(R.id.nfcFlashProgressbar)
        mHardwareLabel     = findViewById(R.id.tv_hardware_version)

        mCurrentProtocol = Preferences(this).getProtocolMode()

        mNfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (mNfcAdapter == null)
            Toast.makeText(this, "NFC not available on this device.", Toast.LENGTH_LONG).show()

        startNfcCheckLoop()
    }

    /* ───────── Options menu (overflow — export) ────────── */
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_nfc_flasher, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_export_stability -> { exportStabilityLog(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /* ───────── onNewIntent ─────── */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val resetMode = intent.getBooleanExtra("RESET_MODE", false)
        findViewById<Button>(R.id.btn_reset_screen)?.visibility =
            if (resetMode) View.VISIBLE else View.GONE
    }

    /* ───────── lifecycle ──────── */
    override fun onPause() {
        super.onPause()
        stopNfcCheckLoop()
        mNfcAdapter?.disableReaderMode(this)
    }

    override fun onResume() {
        super.onResume()
        mCurrentProtocol = Preferences(this).getProtocolMode()
        startNfcCheckLoop()
        val readerExtras = Bundle().apply {
            putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 5000)
        }
        mNfcAdapter?.enableReaderMode(this, this, READER_FLAGS, readerExtras)
    }

    /* ───────── NfcAdapter.ReaderCallback ───── */
    override fun onTagDiscovered(tag: Tag) {
        if (mIsFlashing) return
        val techList = tag.techList.toList()
        Log.d(TAG, "Tag discovered. techs=$techList protocol=$mCurrentProtocol")

        // Read-only / diagnostic modes — no image needed
        when (mCurrentProtocol) {
            ProtocolMode.IDENTIFY -> {
                lifecycleScope.launch { runIdentify(tag) }
                return
            }
            ProtocolMode.PROBE -> {
                lifecycleScope.launch { runProbe(tag) }
                return
            }
            else -> { /* fall through to write routing */ }
        }

        // Write modes — require a loaded image
        val bitmap = mBitmap ?: run {
            runOnUiThread {
                Toast.makeText(this, "No image loaded", Toast.LENGTH_SHORT).show()
            }
            return
        }
        val screenSizeEnum = Preferences(this).getScreenSizeEnum()

        when (mCurrentProtocol) {
            ProtocolMode.FORCE_V2 -> {
                Log.d(TAG, "Force V2 — NfcA handler")
                lifecycleScope.launch { flashBitmapV2(tag, bitmap, screenSizeEnum, forced = true) }
            }
            ProtocolMode.FORCE_V3 -> {
                Log.d(TAG, "Force V3 — IsoDep handler")
                lifecycleScope.launch { flashBitmapV3(tag, bitmap, screenSizeEnum, forced = true) }
            }
            else -> {
                // AUTO: route by techList
                when {
                    techList.contains(IsoDep::class.java.name) -> {
                        Log.d(TAG, "Auto → V3 IsoDep")
                        lifecycleScope.launch { flashBitmapV3(tag, bitmap, screenSizeEnum, forced = false) }
                    }
                    techList.contains(NfcA::class.java.name) -> {
                        Log.d(TAG, "Auto → V2 NfcA")
                        lifecycleScope.launch { flashBitmapV2(tag, bitmap, screenSizeEnum, forced = false) }
                    }
                    else -> {
                        runOnUiThread {
                            Toast.makeText(this,
                                "Unrecognised NFC tag (techs: $techList)", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    /* ─── V2 flash — NfcA, 0xCD commands — handler unchanged from V2V3 ─── */
    private suspend fun flashBitmapV2(
        tag: Tag, bitmap: Bitmap, screenSizeEnum: Int, forced: Boolean
    ) {
        mIsFlashing = true
        showHardwareVersion("V2 (NfcA)${if (forced) " [forced]" else ""}")
        val waveShareHandler = WaveShareHandler(this)

        val progressH = Handler(Looper.getMainLooper())
        val progressCB = object : Runnable {
            override fun run() {
                if (mIsFlashing) {
                    updateProgressBar(waveShareHandler.progress)
                    progressH.postDelayed(this, mProgressCheckInterval)
                }
            }
        }
        progressH.post(progressCB)

        val startMs = SystemClock.elapsedRealtime()
        var writeSucceeded = false

        withContext(Dispatchers.IO) {
            val nfcaObj = NfcA.get(tag)
            try {
                val result = waveShareHandler.sendBitmap(nfcaObj, screenSizeEnum, bitmap)
                writeSucceeded = result.success
                runOnUiThread {
                    Toast.makeText(
                        this@NfcFlasher,
                        if (result.success) "Success! Flashed display (V2)!"
                        else "Write failed, tap again",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                val durationMs = SystemClock.elapsedRealtime() - startMs
                try { nfcaObj.close() } catch (_: Exception) {}
                progressH.removeCallbacks(progressCB)

                StabilityLog.append(
                    this@NfcFlasher,
                    StabilityLog.Entry(
                        hardware   = StabilityLog.Hardware.V2,
                        protocol   = if (forced) StabilityLog.Protocol.NFCA_FORCED
                                     else        StabilityLog.Protocol.AUTO_V2,
                        success    = writeSucceeded,
                        durationMs = durationMs,
                        retryCount = 0,
                        errorType  = if (writeSucceeded) "" else "write_failed",
                    )
                )

                runOnUiThread {
                    mIsFlashing = false
                    showHardwareVersion(null)
                    if (writeSucceeded) askDeletePickList()
                }
            }
        }
    }

    /* ─── V3 flash — IsoDep, 0x74 commands — handler unchanged from V2V3 ─── */
    private suspend fun flashBitmapV3(
        tag: Tag, bitmap: Bitmap, screenSizeEnum: Int, forced: Boolean
    ) {
        mIsFlashing = true
        showHardwareVersion("V3 (IsoDep)${if (forced) " [forced]" else ""}")
        val v3Handler = WaveShareV3Handler()

        val progressH = Handler(Looper.getMainLooper())
        val progressCB = object : Runnable {
            override fun run() {
                if (mIsFlashing) {
                    updateProgressBar(v3Handler.progress)
                    progressH.postDelayed(this, mProgressCheckInterval)
                }
            }
        }
        progressH.post(progressCB)

        val startMs = SystemClock.elapsedRealtime()
        var writeSucceeded = false
        var errorType = ""

        withContext(Dispatchers.IO) {
            val isoDepObj = try {
                IsoDep.get(tag)
            } catch (e: SecurityException) {
                Log.w(TAG, "IsoDep.get() SecurityException: ${e.message}")
                runOnUiThread {
                    Toast.makeText(this@NfcFlasher,
                        "Tag expired, please tap again", Toast.LENGTH_SHORT).show()
                    mIsFlashing = false
                    showHardwareVersion(null)
                }
                progressH.removeCallbacks(progressCB)
                return@withContext
            }

            try {
                val result = v3Handler.sendBitmap(isoDepObj, screenSizeEnum, bitmap)
                writeSucceeded = result.success
                if (!result.success) errorType = "write_failed"
                runOnUiThread {
                    Toast.makeText(
                        this@NfcFlasher,
                        if (result.success) "Success! Flashed display (V3)!"
                        else "Write failed, tap again",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: SecurityException) {
                errorType = "tag_expired"
                Log.w(TAG, "SecurityException mid-transfer: ${e.message}")
                runOnUiThread {
                    Toast.makeText(this@NfcFlasher,
                        "Tag expired, please tap again", Toast.LENGTH_SHORT).show()
                }
            } finally {
                val durationMs = SystemClock.elapsedRealtime() - startMs
                try { isoDepObj.close() } catch (_: Exception) {}
                progressH.removeCallbacks(progressCB)

                StabilityLog.append(
                    this@NfcFlasher,
                    StabilityLog.Entry(
                        hardware   = StabilityLog.Hardware.V3,
                        protocol   = if (forced) StabilityLog.Protocol.ISODEP_FORCED
                                     else        StabilityLog.Protocol.AUTO_V3,
                        success    = writeSucceeded,
                        durationMs = durationMs,
                        retryCount = 0,
                        errorType  = errorType,
                    )
                )

                runOnUiThread {
                    mIsFlashing = false
                    showHardwareVersion(null)
                    if (writeSucceeded) askDeletePickList()
                }
            }
        }
    }

    /* ─── IDENTIFY mode ─────────────────────────────────────────────────── */
    private suspend fun runIdentify(tag: Tag) {
        showHardwareVersion("Identifying…")
        val identifier = HardwareIdentifier()
        val result = withContext(Dispatchers.IO) { identifier.identify(tag) }
        val report = identifier.formatReport(result)
        runOnUiThread {
            showHardwareVersion(null)
            showIdentifyDialog(report)
        }
    }

    private fun showIdentifyDialog(report: String) {
        val tv = TextView(this).apply {
            text = report
            setTextIsSelectable(true)
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(32, 24, 32, 24)
        }
        val scroll = ScrollView(this).apply { addView(tv) }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Hardware Identification")
            .setView(scroll)
            .setNegativeButton("Close", null)
            .setPositiveButton("Copy to clipboard") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("hardware_id", report))
                Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .create()

        dialog.setOnShowListener {
            val white = ContextCompat.getColor(this, android.R.color.white)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(white)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(white)
        }
        dialog.show()
    }

    /* ─── PROBE mode ─────────────────────────────────────────────────────── */
    private suspend fun runProbe(tag: Tag) {
        showHardwareVersion("Probing…")
        val handler = ProbeHandler()
        val result = withContext(Dispatchers.IO) { handler.probe(tag) }
        runOnUiThread {
            showHardwareVersion(null)
            val v2Label = when (result.v2Responded) {
                true  -> "responded ✓"
                false -> "no response ✗${if (result.v2Error.isNotEmpty()) " (${result.v2Error})" else ""}"
                null  -> "not tried (NfcA absent)"
            }
            val v3Label = when (result.v3Responded) {
                true  -> "responded ✓"
                false -> "no response ✗${if (result.v3Error.isNotEmpty()) " (${result.v3Error})" else ""}"
                null  -> "not tried (IsoDep absent)"
            }
            val msg = "NfcA   (CD 0A):   $v2Label\nIsoDep (74 9B): $v3Label\n\n${result.verdict}"
            AlertDialog.Builder(this)
                .setTitle("Protocol Probe Results")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    /* ───────── hardware version indicator ───── */
    private fun showHardwareVersion(version: String?) {
        mHardwareLabel?.apply {
            text = version ?: ""
            visibility = if (version != null) View.VISIBLE else View.GONE
        }
    }

    /* ───────── delete‑file dialog ───── */
    private fun askDeletePickList() {
        val file = pickListFileToDelete ?: return
        val dialog = AlertDialog.Builder(this)
            .setTitle("Delete pick list?")
            .setMessage("Delete \"$file\" from the server?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ -> deletePickListOnServer(file) }
            .create()

        dialog.setOnShowListener {
            val white = ContextCompat.getColor(this, android.R.color.white)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(white)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(white)
        }
        dialog.show()
    }

    private fun deletePickListOnServer(file: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val prefs = Preferences(this@NfcFlasher)
                val base  = prefs.getPreferences()?.getString(PrefKeys.BaseUrl, "") ?: ""
                val urlStr = base.trimEnd('/') + "/delete-file.aspx"
                val payload = "file=" + java.net.URLEncoder.encode(file, "UTF-8") +
                        "&t=" + java.net.URLEncoder.encode(DELETE_SECRET, "UTF-8")

                val conn = (java.net.URL(urlStr).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                }
                conn.outputStream.use { it.write(payload.toByteArray()) }

                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.readText().orEmpty()
                conn.disconnect()

                withContext(Dispatchers.Main) {
                    if (code == 200) {
                        Toast.makeText(this@NfcFlasher, "Deleted on server.", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@NfcFlasher, "Delete failed ($code): $body", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@NfcFlasher, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
                Log.e("PickList", "Delete error", e)
            }
        }
    }

    /* ───────── Export stability log ───── */
    private fun exportStabilityLog() {
        if (!StabilityLog.exists(this)) {
            Toast.makeText(this, "No stability data yet — flash some tags first.", Toast.LENGTH_LONG).show()
            return
        }
        val file = StabilityLog.getCsvFile(this) ?: return
        try {
            val uri = FileProvider.getUriForFile(this, "$PackageName.provider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Adient NFC V4 Stability Log")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Export stability log"))
        } catch (e: Exception) {
            Log.e(TAG, "Export failed: ${e.message}")
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /* ───────── NFC watchdog ───── */
    private fun startNfcCheckLoop() {
        if (mNfcCheckHandler == null) {
            mNfcCheckHandler = Handler(Looper.getMainLooper())
            mNfcCheckHandler?.postDelayed(::checkNfcAvailable, mNfcCheckIntervalMs)
        }
    }
    private fun stopNfcCheckLoop() {
        mNfcCheckHandler?.removeCallbacks(::checkNfcAvailable)
        mNfcCheckHandler = null
    }
    private fun checkNfcAvailable() {
        if (mNfcAdapter == null) Log.e(TAG, "NFC adapter unavailable")
    }

    /* ───── progress bar helper ───── */
    private fun updateProgressBar(updated: Int) {
        if (mProgressBar == null) mProgressBar = findViewById(R.id.nfcFlashProgressbar)
        mProgressBar?.setProgress(updated, true)
    }
}
