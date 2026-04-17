package com.adient.nfcv4

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.NfcA
import android.util.Log
import java.io.IOException

/**
 * Probe-only handler — sends safe status-poll commands to determine which
 * protocol(s) the tag responds to, without writing any image data.
 *
 * Commands used:
 *   V2: CD 0A  (NfcA, read-only BUSY poll)
 *   V3: 74 9B 00 0F 01  (IsoDep, read-only BUSY poll)
 *
 * CRITICAL: No writes. No configuration commands. No state changes.
 */
class ProbeHandler {

    companion object {
        private const val TAG = "ProbeHandler"

        private val CMD_V2_PROBE = byteArrayOf(0xCD.toByte(), 0x0A.toByte())
        private val CMD_V3_PROBE = byteArrayOf(0x74.toByte(), 0x9B.toByte(), 0x00, 0x0F, 0x01)
    }

    data class ProbeResult(
        val v2Responded: Boolean?,   // null = not tried (tech absent)
        val v3Responded: Boolean?,
        val v2Error: String = "",
        val v3Error: String = "",
    ) {
        val verdict: String get() = when {
            v2Responded == true && v3Responded == true -> "Hardware supports BOTH protocols"
            v2Responded == true                        -> "V2 hardware"
            v3Responded == true                        -> "V3 hardware"
            else                                       -> "Unknown — tag may not be a Waveshare EPD"
        }
    }

    /** Called on an IO/background thread. */
    fun probe(tag: Tag): ProbeResult {
        val techList = tag.techList.toList()
        val hasNfcA   = techList.contains(NfcA::class.java.name)
        val hasIsoDep = techList.contains(IsoDep::class.java.name)

        var v2Responded: Boolean? = null
        var v2Error = ""
        var v3Responded: Boolean? = null
        var v3Error = ""

        if (hasNfcA) {
            val nfca = NfcA.get(tag)
            try {
                nfca.connect()
                nfca.timeout = 1000
                val resp = nfca.transceive(CMD_V2_PROBE)
                v2Responded = resp != null && resp.isNotEmpty()
            } catch (e: IOException) {
                v2Responded = false
                v2Error = e.message ?: "IOException"
                Log.d(TAG, "NfcA probe IOException: ${e.message}")
            } catch (e: Exception) {
                v2Responded = false
                v2Error = e.message ?: "Error"
                Log.w(TAG, "NfcA probe error: ${e.message}")
            } finally {
                try { nfca.close() } catch (_: Exception) {}
            }
        }

        if (hasIsoDep) {
            val iso = IsoDep.get(tag)
            try {
                iso.connect()
                iso.timeout = 1500
                val resp = iso.transceive(CMD_V3_PROBE)
                v3Responded = resp != null && resp.isNotEmpty()
            } catch (e: IOException) {
                v3Responded = false
                v3Error = e.message ?: "IOException"
                Log.d(TAG, "IsoDep probe IOException: ${e.message}")
            } catch (e: Exception) {
                v3Responded = false
                v3Error = e.message ?: "Error"
                Log.w(TAG, "IsoDep probe error: ${e.message}")
            } finally {
                try { iso.close() } catch (_: Exception) {}
            }
        }

        return ProbeResult(v2Responded, v3Responded, v2Error, v3Error)
    }
}
