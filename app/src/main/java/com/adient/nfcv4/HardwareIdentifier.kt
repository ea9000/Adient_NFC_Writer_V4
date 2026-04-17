package com.adient.nfcv4

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.NfcA
import android.os.SystemClock
import android.util.Log
import java.io.IOException

/**
 * Pure read-only hardware identification for Waveshare NFC e-paper tags.
 *
 * Reads UID, manufacturer, ATQA, SAK, tech list, historical bytes,
 * NXP GET_VERSION, and sends safe status-poll probes to determine
 * which protocol(s) the tag supports.
 *
 * CRITICAL: No writes. No configuration commands. No state changes.
 * Only status reads and identification commands.
 */
class HardwareIdentifier {

    companion object {
        private const val TAG = "HardwareIdentifier"

        // ── NXP GET_VERSION command (byte 0x60) ───────────────────────────────
        private val CMD_GET_VERSION = byteArrayOf(0x60.toByte())

        // ── Safe status-poll probes ───────────────────────────────────────────
        /** V2 status poll: 0xCD 0x0A — safe, read-only BUSY query */
        private val CMD_V2_PROBE = byteArrayOf(0xCD.toByte(), 0x0A.toByte())

        /** V3 status poll: 0x74 0x9B — safe, read-only BUSY query */
        private val CMD_V3_PROBE = byteArrayOf(0x74.toByte(), 0x9B.toByte(), 0x00, 0x0F, 0x01)

        // ── Manufacturer lookup ───────────────────────────────────────────────
        private val MANUFACTURERS = mapOf(
            0x01 to "Motorola",
            0x02 to "ST Microelectronics",
            0x03 to "Hitachi",
            0x04 to "NXP Semiconductors",
            0x05 to "Infineon Technologies",
            0x06 to "Cylink",
            0x07 to "Texas Instruments",
            0x08 to "Fujitsu",
            0x09 to "Matsushita",
            0x0A to "NEC",
            0x0B to "Oki",
            0x0C to "Toshiba",
            0x0D to "Mitsubishi",
            0x0E to "Samsung",
            0x0F to "Hyundai",
            0x10 to "LG",
            0x16 to "Gemalto",
            0x1F to "Melexis",
            0x28 to "AMS",
            0x2B to "Sony",
            0x33 to "Microchip",
            0x44 to "Broadcom",
            0x46 to "Legic",
            0x4F to "Renesas",
            0x54 to "Oberthur Technologies",
        )

        // ── SAK decode ───────────────────────────────────────────────────────
        private fun decodeSak(sak: Byte): String {
            val s = sak.toInt() and 0xFF
            return when (s) {
                0x00 -> "NfcA only (no ISO 14443-4)"
                0x08 -> "MIFARE Classic 1K"
                0x09 -> "MIFARE Mini"
                0x18 -> "MIFARE Classic 4K"
                0x20 -> "ISO 14443-4 compliant (IsoDep)"
                0x28 -> "MIFARE Classic + ISO 14443-4"
                0x60 -> "MIFARE Plus (SL1 / SL2)"
                0x78 -> "Smartcard (JCOP/JavaCard)"
                else -> "Unknown (0x${s.toString(16).uppercase().padStart(2, '0')})"
            }
        }

        // ── GET_VERSION storage byte → product name ───────────────────────────
        private fun decodeNxpStorage(storageByte: Byte): String {
            return when (storageByte.toInt() and 0xFF) {
                0x0B -> "NTAG210"
                0x0E -> "NTAG213"
                0x11 -> "NTAG215"
                0x13 -> "NTAG216"
                0x15 -> "NTAG I2C 1K / Plus 1K"
                0x17 -> "NTAG I2C 2K"
                0x19 -> "NTAG I2C Plus 2K"
                0x1B -> "NTAG I2C Plus 8K"
                else -> "Unknown (0x${(storageByte.toInt() and 0xFF).toString(16).uppercase()})"
            }
        }

        // ── GET_VERSION type byte → IC type ──────────────────────────────────
        private fun decodeNxpType(typeByte: Byte): String {
            return when (typeByte.toInt() and 0xFF) {
                0x03 -> "Ultralight"
                0x04 -> "NTAG"
                0x05 -> "Ultralight C"
                0x06 -> "NTAG I2C"
                else -> "Unknown"
            }
        }
    }

    /**
     * Result bag returned after identification completes.
     * All fields are strings ready for display.
     */
    data class IdentifyResult(
        val uid: String,
        val manufacturer: String,
        val atqa: String,
        val sak: String,
        val sakDecoded: String,
        val techList: String,
        // IsoDep fields (empty string if not present)
        val historicalBytesHex: String,
        val historicalBytesAscii: String,
        val hiLayerResponseHex: String,
        // NXP GET_VERSION fields (empty string if not supported / not NfcA)
        val getVersionRaw: String,
        val getVersionDecoded: String,
        // Protocol probe results
        val v2ProbeResult: ProbeResult,
        val v3ProbeResult: ProbeResult,
    )

    enum class ProbeResult { RESPONDED, NO_RESPONSE, ERROR, NOT_TRIED }

    /**
     * Run full identification on [tag]. Called from a background (IO) thread.
     * Never throws — all exceptions are caught and reflected in the result strings.
     */
    fun identify(tag: Tag): IdentifyResult {
        val techList = tag.techList.toList()

        // ── 1. Basic tag info (no connection needed) ─────────────────────────
        val uid        = tag.id?.toHex() ?: "(none)"
        val mfrByte    = tag.id?.firstOrNull()?.toInt()?.and(0xFF) ?: -1
        val mfrName    = if (mfrByte >= 0) {
            val name = MANUFACTURERS[mfrByte] ?: "Unknown"
            "$name (0x${mfrByte.toString(16).uppercase().padStart(2, '0')})"
        } else "(no UID)"

        var atqa = "(N/A)"
        var sak  = "(N/A)"
        var sakDecoded = ""
        val nfcaObj = NfcA.get(tag)
        if (nfcaObj != null) {
            atqa = nfcaObj.atqa?.toHex() ?: "(none)"
            val sakInt  = nfcaObj.sak.toInt().and(0xFF)
            sak  = "0x${sakInt.toString(16).uppercase().padStart(2, '0')}"
            sakDecoded = decodeSak(sakInt.toByte())
        }

        val techListStr = techList.joinToString(", ") { it.substringAfterLast('.') }

        // ── 2. IsoDep: historical bytes ──────────────────────────────────────
        var histHex  = ""
        var histAscii = ""
        var hiLayerHex = ""
        val hasIsoDep = techList.contains(IsoDep::class.java.name)
        if (hasIsoDep) {
            val iso = IsoDep.get(tag)
            if (iso != null) {
                try {
                    iso.connect()
                    iso.timeout = 1500
                    val hist = iso.historicalBytes
                    val hi   = iso.hiLayerResponse
                    histHex   = hist?.toHex() ?: "(none)"
                    histAscii = hist?.toAsciiPrintable() ?: ""
                    hiLayerHex = hi?.toHex() ?: "(none)"
                } catch (e: Exception) {
                    Log.w(TAG, "IsoDep historical bytes read failed: ${e.message}")
                    histHex = "(read error: ${e.message})"
                } finally {
                    try { iso.close() } catch (_: Exception) {}
                }
            }
        }

        // ── 3. NfcA GET_VERSION (NXP command 0x60) ───────────────────────────
        var getVersionRaw     = ""
        var getVersionDecoded = ""
        val hasNfcA = techList.contains(NfcA::class.java.name)
        if (hasNfcA) {
            val nfca = NfcA.get(tag)
            if (nfca != null) {
                try {
                    nfca.connect()
                    nfca.timeout = 1500
                    val resp = nfca.transceive(CMD_GET_VERSION)
                    if (resp != null && resp.size >= 7) {
                        getVersionRaw = resp.toHex()
                        // [0]=fixed, [1]=vendor, [2]=type, [3]=subtype,
                        // [4]=major, [5]=minor, [6]=storage, [7]=protocol
                        val vendor  = resp.getOrNull(1)?.let { "0x${(it.toInt() and 0xFF).toString(16).uppercase()}" } ?: "?"
                        val type    = resp.getOrNull(2)?.let { decodeNxpType(it) } ?: "?"
                        val major   = resp.getOrNull(4)?.toInt()?.and(0xFF) ?: 0
                        val minor   = resp.getOrNull(5)?.toInt()?.and(0xFF) ?: 0
                        val storage = resp.getOrNull(6) ?: 0
                        val product = decodeNxpStorage(storage)
                        getVersionDecoded = "$product — $type v$major.$minor (vendor $vendor)"
                    } else if (resp != null) {
                        getVersionRaw     = resp.toHex()
                        getVersionDecoded = "(short response — ${resp.size} bytes)"
                    } else {
                        getVersionDecoded = "No response"
                    }
                } catch (e: IOException) {
                    getVersionRaw     = ""
                    getVersionDecoded = "Not supported (IOException)"
                } catch (e: Exception) {
                    getVersionRaw     = ""
                    getVersionDecoded = "Error: ${e.message}"
                } finally {
                    try { nfca.close() } catch (_: Exception) {}
                }
            }
        }

        // ── 4. Protocol probes ────────────────────────────────────────────────
        val v2Result = if (hasNfcA) probeNfcA(tag) else ProbeResult.NOT_TRIED
        val v3Result = if (hasIsoDep) probeIsoDep(tag) else ProbeResult.NOT_TRIED

        return IdentifyResult(
            uid              = uid,
            manufacturer     = mfrName,
            atqa             = atqa,
            sak              = sak,
            sakDecoded       = sakDecoded,
            techList         = techListStr,
            historicalBytesHex   = histHex,
            historicalBytesAscii = histAscii,
            hiLayerResponseHex   = hiLayerHex,
            getVersionRaw    = getVersionRaw,
            getVersionDecoded = getVersionDecoded,
            v2ProbeResult    = v2Result,
            v3ProbeResult    = v3Result,
        )
    }

    // ── Protocol probe helpers ────────────────────────────────────────────────

    /** Send CD 0A (V2 status poll) via NfcA.  Returns responded/no-response/error. */
    private fun probeNfcA(tag: Tag): ProbeResult {
        val nfca = NfcA.get(tag) ?: return ProbeResult.ERROR
        return try {
            nfca.connect()
            nfca.timeout = 1000
            val resp = nfca.transceive(CMD_V2_PROBE)
            if (resp != null && resp.isNotEmpty()) ProbeResult.RESPONDED
            else ProbeResult.NO_RESPONSE
        } catch (e: IOException) {
            Log.d(TAG, "NfcA probe: ${e.message}")
            ProbeResult.NO_RESPONSE
        } catch (e: Exception) {
            Log.w(TAG, "NfcA probe error: ${e.message}")
            ProbeResult.ERROR
        } finally {
            try { nfca.close() } catch (_: Exception) {}
        }
    }

    /** Send 74 9B (V3 busy-poll) via IsoDep.  Returns responded/no-response/error. */
    private fun probeIsoDep(tag: Tag): ProbeResult {
        val iso = IsoDep.get(tag) ?: return ProbeResult.ERROR
        return try {
            iso.connect()
            iso.timeout = 1500
            val resp = iso.transceive(CMD_V3_PROBE)
            if (resp != null && resp.isNotEmpty()) ProbeResult.RESPONDED
            else ProbeResult.NO_RESPONSE
        } catch (e: IOException) {
            Log.d(TAG, "IsoDep probe: ${e.message}")
            ProbeResult.NO_RESPONSE
        } catch (e: Exception) {
            Log.w(TAG, "IsoDep probe error: ${e.message}")
            ProbeResult.ERROR
        } finally {
            try { iso.close() } catch (_: Exception) {}
        }
    }

    // ── Formatting helpers ────────────────────────────────────────────────────

    /**
     * Build the full scrollable report string from an [IdentifyResult].
     * This is what gets displayed in the AlertDialog and copied to clipboard.
     */
    fun formatReport(r: IdentifyResult): String {
        val sb = StringBuilder()
        val bar = "═".repeat(45)

        sb.appendLine(bar)
        sb.appendLine("   HARDWARE IDENTIFICATION")
        sb.appendLine(bar)
        sb.appendLine()
        sb.appendLine("UID:          ${r.uid}")
        sb.appendLine("Manufacturer: ${r.manufacturer}")
        sb.appendLine("ATQA:         ${r.atqa}")
        sb.appendLine("SAK:          ${r.sak}  ${r.sakDecoded}")
        sb.appendLine("Tech list:    ${r.techList}")

        if (r.historicalBytesHex.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Historical bytes (ATS):")
            sb.appendLine("  Hex:   ${r.historicalBytesHex}")
            if (r.historicalBytesAscii.isNotEmpty())
                sb.appendLine("  ASCII: \"${r.historicalBytesAscii}\"")
            if (r.hiLayerResponseHex.isNotEmpty() && r.hiLayerResponseHex != "(none)")
                sb.appendLine("  HiLayer: ${r.hiLayerResponseHex}")
        }

        sb.appendLine()
        sb.appendLine("NXP GET_VERSION (0x60):")
        if (r.getVersionRaw.isNotEmpty())
            sb.appendLine("  Raw:     ${r.getVersionRaw}")
        sb.appendLine("  Result:  ${r.getVersionDecoded.ifEmpty { "Not attempted" }}")

        sb.appendLine()
        sb.appendLine("Protocol probes:")
        sb.appendLine("  NfcA  (CD 0A): ${r.v2ProbeResult.label()}")
        sb.appendLine("  IsoDep (74 9B): ${r.v3ProbeResult.label()}")

        sb.appendLine()
        sb.appendLine(bar)
        sb.appendLine("VERDICT:")

        val v2 = r.v2ProbeResult == ProbeResult.RESPONDED
        val v3 = r.v3ProbeResult == ProbeResult.RESPONDED
        val verdict = when {
            v2 && v3  -> "Hardware supports BOTH protocols"
            v2        -> "V2 hardware detected"
            v3        -> "V3 hardware detected"
            else      -> "Unknown — tag may not be a Waveshare EPD"
        }
        sb.appendLine("  $verdict")
        sb.appendLine()
        val recommended = when {
            v2 && v3  -> "Recommended: use Auto mode"
            v2        -> "Recommended: Force V2 (NfcA)"
            v3        -> "Recommended: Force V3 (IsoDep)"
            else      -> "Recommended: check tag type"
        }
        sb.appendLine("  $recommended")
        sb.appendLine(bar)

        return sb.toString().trimEnd()
    }

    private fun ProbeResult.label(): String = when (this) {
        ProbeResult.RESPONDED    -> "responded ✓"
        ProbeResult.NO_RESPONSE  -> "no response ✗"
        ProbeResult.ERROR        -> "error ✗"
        ProbeResult.NOT_TRIED    -> "not tried (tech absent)"
    }

    private fun ByteArray.toHex(): String =
        joinToString(" ") { "%02X".format(it) }

    /** Return printable ASCII characters; replace non-printable with '.' */
    private fun ByteArray.toAsciiPrintable(): String {
        val sb = StringBuilder()
        for (b in this) {
            val c = b.toInt() and 0xFF
            if (c in 0x20..0x7E) sb.append(c.toChar()) else sb.append('.')
        }
        // Only return if there are real printable characters (not just dots)
        val printable = sb.toString().replace(".", "")
        return if (printable.length >= 2) sb.toString() else ""
    }
}
