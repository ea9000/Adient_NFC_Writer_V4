package com.adient.nfcv4

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Appends one line per write attempt to:
 *   Android/data/com.adient.nfcv4/files/stability.csv
 *
 * Format (CSV, no spaces around commas):
 *   timestamp,hardware_detected,protocol_used,result,duration_ms,retry_count,error_type
 *
 * Example rows:
 *   2026-04-16T21:34:12,V2,nfca,success,2140,0,
 *   2026-04-16T21:34:55,V3,isodep,fail,4200,3,tag_lost
 *   2026-04-16T21:35:18,V3,nfca_forced,success,2890,1,
 *
 * This class is thread-safe (synchronised on the file object).
 */
object StabilityLog {

    private const val TAG       = "StabilityLog"
    private const val FILE_NAME = "stability.csv"
    private val CSV_HEADER      = "timestamp,hardware_detected,protocol_used,result,duration_ms,retry_count,error_type\n"
    private val ISO_FMT         = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    /**
     * Hardware detected values — kept terse to match the spec.
     */
    enum class Hardware(val csv: String) {
        V2("V2"),
        V3("V3"),
        UNKNOWN("unknown");
    }

    /**
     * Protocol column values.
     */
    enum class Protocol(val csv: String) {
        NFCA("nfca"),
        ISODEP("isodep"),
        NFCA_FORCED("nfca_forced"),
        ISODEP_FORCED("isodep_forced"),
        AUTO_V2("auto_v2"),
        AUTO_V3("auto_v3");
    }

    /** @param errorType  short snake_case label or empty string on success */
    data class Entry(
        val hardware: Hardware,
        val protocol: Protocol,
        val success: Boolean,
        val durationMs: Long,
        val retryCount: Int,
        val errorType: String = "",
    )

    fun append(context: Context, entry: Entry) {
        val file = getCsvFile(context) ?: return
        val timestamp = LocalDateTime.now().format(ISO_FMT)
        val result    = if (entry.success) "success" else "fail"
        val line      = "$timestamp,${entry.hardware.csv},${entry.protocol.csv}," +
                        "$result,${entry.durationMs},${entry.retryCount},${entry.errorType}\n"
        try {
            synchronized(file) {
                val needsHeader = !file.exists() || file.length() == 0L
                FileWriter(file, /* append = */ true).use { w ->
                    if (needsHeader) w.write(CSV_HEADER)
                    w.write(line)
                }
            }
            Log.d(TAG, "Logged: $line")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write stability log: ${e.message}")
        }
    }

    /** Returns the CSV File object (creates parent dirs if needed), or null on error. */
    fun getCsvFile(context: Context): File? {
        return try {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            File(dir, FILE_NAME).also { it.parentFile?.mkdirs() }
        } catch (e: Exception) {
            Log.e(TAG, "Cannot resolve CSV file path: ${e.message}")
            null
        }
    }

    /** True if the file exists and has content. */
    fun exists(context: Context): Boolean {
        val f = getCsvFile(context) ?: return false
        return f.exists() && f.length() > 0
    }
}
