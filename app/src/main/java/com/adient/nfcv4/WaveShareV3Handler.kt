package com.adient.nfcv4

import android.graphics.Bitmap
import android.nfc.tech.IsoDep
import android.os.SystemClock
import android.util.Log
import waveshare.feng.nfctag.activity.FlashResult
import java.io.IOException

/**
 * V3 NFC e-paper handler — IsoDep (ISO 14443-4) protocol.
 *
 * Protocol details reverse-engineered from the WaveShare V3 demo app (EPD_send.java).
 * Supports the 7.5" 800×480 display (ePaperSize == 4 in the WaveShare enum).
 *
 * Key differences from V2 (NfcA, 0xCD commands):
 *  - Transport : IsoDep instead of NfcA
 *  - Commands  : 0x74 prefix, APDU-style register-write init sequence
 *  - Pixels    : bytes are BIT-INVERTED before transmission (~packed_pix)
 *  - Encoding  : raw 48 kB image -> RLE-compressed -> 1016-byte chunks via 0x74 0x9E
 *  - Poll      : 0x74 0x9B, success when response[0] != 0x00  (inverted vs V2)
 *  - Success   : {0x90, 0x00}  (ISO-DEP standard, vs V2's {0x00, 0x00})
 */
class WaveShareV3Handler {

    // Progress 0..100, polled by NfcFlasher every 50 ms
    @Volatile
    var progress: Int = 0
        private set

    companion object {
        private const val TAG = "WaveShareV3Handler"

        /** Number of transceive retries on IO failure before giving up. */
        private const val MAX_RETRIES = 15

        /** Max NFC data chunk size to send per 0x9E command (hardware ceiling). */
        private const val MAX_CHUNK = 1016

        /**
         * ISO-DEP transceive timeout in milliseconds.
         * Matches WaveShare EPD_send.java Init_EPD() which calls setTimeout(5000).
         */
        private const val TIMEOUT_MS = 5000

        /** Expected success response bytes for IsoDep (ISO 14443-4 standard). */
        private val OK = byteArrayOf(0x90.toByte(), 0x00)

        /** Validate a response is {0x90, 0x00}. */
        private fun isOk(r: ByteArray) = r.size >= 2 && r[0] == 0x90.toByte() && r[1] == 0x00.toByte()

        // Screen-size tables (index = WaveShare enum 1..7)
        // These are identical to V2; only the transport changes.
        private val EPD_WIDTH  = intArrayOf(0, 250, 296, 400, 800, 880, 264, 296)
        private val EPD_HEIGHT = intArrayOf(0, 122, 128, 300, 480, 528, 176, 128)
    }

    /**
     * Connect, init, encode and send [bitmap] to a V3 IsoDep tag.
     *
     * @param isoDepTag  IsoDep instance obtained from the tag (not yet connect()-ed)
     * @param ePaperSize WaveShare screen-size enum (1-7); 4 = 7.5" 800x480
     * @param bitmap     Bitmap exactly matching the display resolution
     * @return FlashResult with success/failure details
     */
    fun sendBitmap(isoDepTag: IsoDep, ePaperSize: Int, bitmap: Bitmap): FlashResult {
        progress = 0

        // Dimension validation (CPU-only, before any NFC work)
        val expectedW = EPD_WIDTH.getOrElse(ePaperSize) { 0 }
        val expectedH = EPD_HEIGHT.getOrElse(ePaperSize) { 0 }
        val bw = bitmap.width;  val bh = bitmap.height
        if (!((bw == expectedW && bh == expectedH) || (bw == expectedH && bh == expectedW))) {
            return fail("Incorrect image resolution ${bw}x${bh}, expected ${expectedW}x${expectedH}")
        }

        // Encode + compress once (CPU-only — no need to redo on NFC retry)
        val rawBytes   = encodePixels(bitmap, ePaperSize)   // 48 000 bytes for 7.5"
        val compressed = rleCompress(rawBytes)
        progress = 10
        Log.d(TAG, "Encoded ${rawBytes.size} raw bytes -> ${compressed.size} compressed bytes")

        // Full-sequence retry loop
        // On an IOException during connect, init, or transfer: close the tag,
        // wait 1000 ms (lets the e-paper capacitor recharge), reconnect the same
        // IsoDep handle, and retry from scratch.
        // V3ProtocolException (unexpected NACK) is not retried — it indicates a
        // firmware mismatch, not a transient RF dropout.
        val MAX_SEQUENCE_RETRIES = 5
        var lastFailMsg = "unknown error"

        for (attempt in 1..MAX_SEQUENCE_RETRIES) {
            if (attempt > 1) {
                Log.w(TAG, "Full-sequence retry $attempt/$MAX_SEQUENCE_RETRIES — waiting 1000 ms")
                SystemClock.sleep(1000)
            }

            // Connect
            val maxLen: Int
            try {
                isoDepTag.connect()
                isoDepTag.timeout = TIMEOUT_MS
                maxLen = isoDepTag.maxTransceiveLength
                Log.d(TAG, "IsoDep connected (attempt $attempt). " +
                           "timeout=${TIMEOUT_MS}ms maxTransceiveLength=$maxLen")
                if (maxLen < 7 + 64) {
                    // Hardware limit too small — no point retrying
                    return fail("IsoDep maxTransceiveLength too small ($maxLen) — cannot send data")
                }
                // Demo (EPD_send.java Init_EPD) goes straight from connect() into the first
                // transceive with no settle. Earlier added 500 ms here was speculative and
                // likely caused the tag controller to time out before the first 0x97.
                if (!isoDepTag.isConnected) {
                    lastFailMsg = "IsoDep disconnected immediately after connect"
                    Log.w(TAG, "Attempt $attempt: $lastFailMsg")
                    try { isoDepTag.close() } catch (_: Exception) { }
                    continue
                }
            } catch (e: IOException) {
                lastFailMsg = "Failed to connect to V3 tag: ${e.message}"
                Log.w(TAG, "Attempt $attempt: $lastFailMsg")
                try { isoDepTag.close() } catch (_: Exception) { }
                continue
            }

            // Init + transfer
            try {
                // Phase 1: Init sequence
                initSequence(isoDepTag, ePaperSize)

                // Phase 2: Signal data start — register 0x13 = DTM1
                transceive(isoDepTag, byteArrayOf(0x74, 0x99.toByte(), 0x00, 0x0D, 0x01, 0x13),
                    "data-start cmd 0x13")

                // Phase 3: Send compressed data in chunks
                val effectiveChunk = minOf(MAX_CHUNK, maxLen - 7)
                Log.d(TAG, "Compressed size=${compressed.size} bytes, chunk=$effectiveChunk, " +
                           "chunks=${(compressed.size + effectiveChunk - 1) / effectiveChunk}")
                sendCompressedData(isoDepTag, compressed, effectiveChunk)

                // Phase 4: Trigger display refresh — register 0x12 = DRF
                transceive(isoDepTag, byteArrayOf(0x74, 0x99.toByte(), 0x00, 0x0D, 0x01, 0x12),
                    "refresh cmd 0x12")

                // Phase 5: Poll BUSY until display is done
                waitUntilReady(isoDepTag, ePaperSize)

                progress = 100
                return success()

            } catch (e: IOException) {
                lastFailMsg = "NFC IO error: ${e.message}"
                Log.w(TAG, "Attempt $attempt/$MAX_SEQUENCE_RETRIES IO failure — ${e.message}")
            } catch (e: V3ProtocolException) {
                // Unexpected NACK — retrying the same sequence won't help
                return fail(e.message ?: "V3 protocol error")
            } finally {
                try { isoDepTag.close() } catch (_: Exception) { }
            }
        }

        return fail("$lastFailMsg (failed after $MAX_SEQUENCE_RETRIES full-sequence attempts)")
    }

    // -------------------------------------------------------------------------
    //  Init sequence
    // -------------------------------------------------------------------------

    private fun initSequence(tag: IsoDep, epd: Int) {
        // Step 1: Soft-reset. Demo (canSendPic) tolerates NACK on 0xB1 and proceeds
        // immediately to the first 0x97 with no inter-command sleep.
        try {
            val resp = tag.transceive(byteArrayOf(0x74, 0xB1.toByte(), 0x00, 0x00, 0x08,
                0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77))
            Log.d(TAG, "0xB1 reset response: ${resp.joinToString(" ") { "%02X".format(it) }}")
        } catch (e: IOException) {
            Log.w(TAG, "0xB1 reset threw IOException: ${e.message}")
        }

        // Steps 2-4: Three-phase power-on handshake
        transceive(tag, byteArrayOf(0x74, 0x97.toByte(), 0x01, 0x08, 0x00), "init 0x97 P1=01")
        SystemClock.sleep(50)
        transceive(tag, byteArrayOf(0x74, 0x97.toByte(), 0x00, 0x08, 0x00), "init 0x97 P1=00")
        SystemClock.sleep(50)
        transceive(tag, byteArrayOf(0x74, 0x97.toByte(), 0x01, 0x08, 0x00), "init 0x97 P1=01")
        SystemClock.sleep(10)

        // Steps 5+: Per-display register writes
        when (epd) {
            4 -> initEpd4(tag)   // 7.5" 800x480
            3 -> initEpd3(tag)   // 4.2" 400x300
            5 -> initEpd5(tag)   // 7.5" HD 880x528
            else -> initEpdGeneric(tag, epd)
        }
    }

    /** Init registers for 7.5" 800x480 (fast-refresh mode). */
    private fun initEpd4(tag: IsoDep) {
        // PWR: 0x00 -> 0x1F
        regWrite(tag, 0x00, byteArrayOf(0x1F))
        // TRES (resolution): 0x50 -> 0x10, 0x07  (0x0320 = 800, 0x01E0 = 480 packed)
        regWrite(tag, 0x50, byteArrayOf(0x10, 0x07))
        // PSON (power on)
        transceive(tag, byteArrayOf(0x74, 0x99.toByte(), 0x00, 0x0D, 0x01, 0x04), "PSON")
        SystemClock.sleep(150)
        // PFS (panel frame setting): 0x06
        regWrite(tag, 0x06, byteArrayOf(0x27, 0x27, 0x18, 0x17))
        // CCSET: 0xE0 -> 0x02
        regWrite(tag, 0xE0.toByte(), byteArrayOf(0x02))
        // TSSET: 0xE5 -> 0x5A
        regWrite(tag, 0xE5.toByte(), byteArrayOf(0x5A))
    }

    /** Init registers for 4.2" 400x300. */
    private fun initEpd3(tag: IsoDep) {
        regWrite(tag, 0x00, byteArrayOf(0x1F))
        regWrite(tag, 0x50, byteArrayOf(0x10, 0x07))
        transceive(tag, byteArrayOf(0x74, 0x99.toByte(), 0x00, 0x0D, 0x01, 0x04), "PSON")
        SystemClock.sleep(100)
        regWrite(tag, 0x06, byteArrayOf(0x27, 0x27, 0x18, 0x17))
        regWrite(tag, 0xE0.toByte(), byteArrayOf(0x02))
        regWrite(tag, 0xE5.toByte(), byteArrayOf(0x5A))
    }

    /** Init registers for 7.5" HD 880x528. */
    private fun initEpd5(tag: IsoDep) {
        regWrite(tag, 0x00, byteArrayOf(0x1F))
        regWrite(tag, 0x50, byteArrayOf(0x10, 0x07))
        transceive(tag, byteArrayOf(0x74, 0x99.toByte(), 0x00, 0x0D, 0x01, 0x04), "PSON")
        SystemClock.sleep(150)
        regWrite(tag, 0x06, byteArrayOf(0x27, 0x27, 0x18, 0x17))
        regWrite(tag, 0xE0.toByte(), byteArrayOf(0x02))
        regWrite(tag, 0xE5.toByte(), byteArrayOf(0x5A))
    }

    /** Fallback init for any other supported size — uses the common register set. */
    private fun initEpdGeneric(tag: IsoDep, epd: Int) {
        Log.w(TAG, "initEpdGeneric called for EPD=$epd — using default 7.5\" init registers")
        initEpd4(tag)
    }

    // -------------------------------------------------------------------------
    //  Register-write helpers
    // -------------------------------------------------------------------------

    /**
     * Issue a command register select (0x74 0x99) followed by a data write (0x74 0x9A).
     * [reg] is the register address byte; [data] is the payload (1-4 bytes typically).
     */
    private fun regWrite(tag: IsoDep, reg: Byte, data: ByteArray) {
        // Select register
        transceive(tag,
            byteArrayOf(0x74, 0x99.toByte(), 0x00, 0x0D, 0x01, reg),
            "regSelect 0x${reg.toInt().and(0xFF).toString(16)}")
        SystemClock.sleep(5)
        // Write data
        val cmd = ByteArray(5 + data.size)
        cmd[0] = 0x74; cmd[1] = 0x9A.toByte(); cmd[2] = 0x00; cmd[3] = 0x0E
        cmd[4] = data.size.toByte()
        data.copyInto(cmd, 5)
        transceive(tag, cmd, "regData")
    }

    private fun regWrite(tag: IsoDep, reg: Int, data: ByteArray) = regWrite(tag, reg.toByte(), data)

    // -------------------------------------------------------------------------
    //  Pixel encoding
    // -------------------------------------------------------------------------

    /**
     * Convert [bitmap] to a 1-bit packed, MSB-first byte array suitable for V3 IsoDep.
     *
     * Pipeline:
     *   1. Rotate 270 degrees for narrow displays (EPD 1,2,6,7) — same requirement as V2.
     *   2. Convert each pixel to luminance using the ITU-R BT.601 coefficients
     *      (0.299 R + 0.587 G + 0.114 B).  The demo app takes only the blue channel
     *      which gives poor results on colour images.
     *   3. Apply Floyd-Steinberg error-diffusion dithering so gradients and photos
     *      render at the same quality as the V2 path (which dithers inside the JAR).
     *   4. Pack 8 dithered pixels per byte, MSB-first.
     *   5. Invert every byte: V3 IsoDep expects bit-1 = black, bit-0 = white
     *      (confirmed in EPD_send.java line 1250: pic_send[...] = (byte)~packed_pix).
     */
    private fun encodePixels(bmp: Bitmap, epd: Int): ByteArray {
        val width  = EPD_WIDTH[epd]
        val height = EPD_HEIGHT[epd]

        // Step 1: optional rotation
        val src = if (epd == 1 || epd == 2 || epd == 6 || epd == 7) {
            android.graphics.Matrix().let { m ->
                m.setRotate(270f)
                Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, false)
            }
        } else bmp

        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        // Step 2: ARGB -> luminance (float, 0..255)
        val gray = FloatArray(width * height) { i ->
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr  8) and 0xFF
            val b =  c         and 0xFF
            0.299f * r + 0.587f * g + 0.114f * b
        }

        // Step 3: Floyd-Steinberg dithering
        // Scan left-to-right, top-to-bottom.  For each pixel decide black/white
        // at threshold 128, compute the quantisation error, and distribute it to
        // the four standard neighbours with the classic 7/16 3/16 5/16 1/16 weights.
        for (row in 0 until height) {
            for (col in 0 until width) {
                val idx = row * width + col
                val old = gray[idx].coerceIn(0f, 255f)
                val new = if (old >= 128f) 255f else 0f
                gray[idx] = new
                val err = old - new

                if (col + 1 < width)
                    gray[idx + 1]             = (gray[idx + 1]             + err * 7f / 16f).coerceIn(0f, 255f)
                if (row + 1 < height) {
                    if (col - 1 >= 0)
                        gray[idx + width - 1] = (gray[idx + width - 1]     + err * 3f / 16f).coerceIn(0f, 255f)
                    gray[idx + width]         = (gray[idx + width]          + err * 5f / 16f).coerceIn(0f, 255f)
                    if (col + 1 < width)
                        gray[idx + width + 1] = (gray[idx + width + 1]      + err * 1f / 16f).coerceIn(0f, 255f)
                }
            }
        }

        // Steps 4 & 5: pack 8 pixels/byte MSB-first, then invert
        val bytesPerRow = width / 8
        val result = ByteArray(height * bytesPerRow)
        for (row in 0 until height) {
            for (col in 0 until bytesPerRow) {
                var packed = 0
                for (bit in 0 until 8) {
                    packed = packed shl 1
                    if (gray[row * width + col * 8 + bit] >= 128f) packed = packed or 1
                }
                // V3 IsoDep: bit-1 = black, bit-0 = white  ->  invert the packed byte
                result[row * bytesPerRow + col] = packed.inv().toByte()
            }
        }
        return result
    }

    // -------------------------------------------------------------------------
    //  RLE compression
    // -------------------------------------------------------------------------

    /**
     * WaveShare V3 RLE:  runs of > 5 identical bytes are encoded as
     * {0xAB, 0xCD, 0xEF, count, value}.  Short runs are stored literally.
     *
     * The [input] buffer is iterated to [input.size - 1] to safely read [ccnt+1].
     */
    private fun rleCompress(input: ByteArray): ByteArray {
        // Worst case: no compression — output same size as input
        val out = ByteArray(input.size + input.size / 4 + 16)
        var ccnt = 0   // read cursor
        var tmpcnt = 0 // write cursor
        val limit = input.size - 1  // safe upper bound for look-ahead

        while (ccnt < limit) {
            // Flush boundary: every 254 output bytes the encoder resets its
            // "is this a run?" window in the original Java. We replicate that
            // by checking (tmpcnt % 254) & 0xFF < 250.
            if ((tmpcnt % 254) and 0xFF < 250) {
                if (input[ccnt] == input[ccnt + 1]) {
                    // Count run length (capped at 255)
                    var scnt = 1
                    while (scnt < 255) {
                        if (ccnt + scnt >= limit) break
                        scnt++
                        if (input[ccnt] != input[ccnt + scnt]) break
                    }
                    if (scnt > 5) {
                        // Emit RLE token
                        out[tmpcnt]     = 0xAB.toByte()
                        out[tmpcnt + 1] = 0xCD.toByte()
                        out[tmpcnt + 2] = 0xEF.toByte()
                        out[tmpcnt + 3] = (scnt and 0xFF).toByte()
                        out[tmpcnt + 4] = input[ccnt]
                        ccnt   += scnt
                        tmpcnt += 5
                    } else {
                        // Short run — copy literally
                        for (j in 0 until scnt) out[tmpcnt + j] = input[ccnt + j]
                        ccnt   += scnt
                        tmpcnt += scnt
                    }
                } else {
                    out[tmpcnt++] = input[ccnt++]
                }
            } else {
                out[tmpcnt++] = input[ccnt++]
            }
        }
        return out.copyOf(tmpcnt) // trim to actual size
    }

    // -------------------------------------------------------------------------
    //  Chunked data transfer  (0x74 0x9E)
    // -------------------------------------------------------------------------

    /**
     * Send [compressed] data to the tag in chunks of up to [chunkSize] bytes each.
     *
     * Packet format (matches WaveShare EPD_send.java EPD==4 loop):
     *   byte[0..1] = {0x74, 0x9E}   command
     *   byte[2..4] = {0x00, 0x00, 0x00}
     *   byte[5]    = len_high
     *   byte[6]    = len_low
     *   byte[7..]  = data (up to [chunkSize] bytes)
     */
    private fun sendCompressedData(tag: IsoDep, compressed: ByteArray, chunkSize: Int) {
        val header = ByteArray(7)
        header[0] = 0x74; header[1] = 0x9E.toByte()
        // bytes 2-4 are 0x00

        var sent = 0
        val total = compressed.size
        val totalChunks = (total + chunkSize - 1) / chunkSize
        var chunkIdx = 0

        while (sent < total) {
            val chunk = minOf(chunkSize, total - sent)
            chunkIdx++

            val cmd = ByteArray(7 + chunk)
            header.copyInto(cmd)
            cmd[5] = ((chunk shr 8) and 0xFF).toByte()
            cmd[6] = (chunk and 0xFF).toByte()
            compressed.copyInto(cmd, 7, sent, sent + chunk)

            // Exponential-backoff retry for transient RF drops
            var ok = false
            for (attempt in 1..MAX_RETRIES) {
                try {
                    val resp = tag.transceive(cmd)
                    if (isOk(resp)) {
                        Log.d(TAG, "Chunk $chunkIdx/$totalChunks offset=$sent size=$chunk OK")
                        ok = true
                        break
                    } else {
                        Log.w(TAG, "Chunk $chunkIdx/$totalChunks offset=$sent NACK " +
                                   "(attempt $attempt): ${resp.toHex()}")
                        if (attempt == MAX_RETRIES) throw V3ProtocolException(
                            "Data chunk $chunkIdx at offset $sent rejected after $attempt tries " +
                            "(response: ${resp.toHex()})")
                        SystemClock.sleep(20L * attempt)
                    }
                } catch (e: IOException) {
                    Log.w(TAG, "Chunk $chunkIdx/$totalChunks offset=$sent IOException " +
                               "(attempt $attempt): ${e.message}")
                    if (attempt == MAX_RETRIES) throw e
                    SystemClock.sleep(50L * attempt)  // 50ms, 100ms, 150ms, ... back-off
                }
            }

            sent += chunk
            // Map 10..99 to data-transfer progress band
            progress = 10 + (sent * 89L / total).toInt()
        }
        Log.d(TAG, "sendCompressedData complete: $totalChunks chunks, $total bytes sent")
    }

    // -------------------------------------------------------------------------
    //  Busy-wait / refresh poll
    // -------------------------------------------------------------------------

    /**
     * Poll the BUSY register (0x74 0x9B) until the display signals it is ready.
     *
     * For the 7.5" display (EPD=4): done when response[0] != 0x00  (inverted sense).
     * For all other sizes:          done when response[0] == 0x00.
     *
     * Max wait ~26 seconds (100 x 250 ms + 1 s initial sleep) — same as V3 demo.
     */
    private fun waitUntilReady(tag: IsoDep, epd: Int) {
        val busyCmd = byteArrayOf(0x74, 0x9B.toByte(), 0x00, 0x0F, 0x01)
        SystemClock.sleep(1000)   // display needs at least 1 s before BUSY goes low

        for (i in 1..100) {
            val resp = try {
                tag.transceive(busyCmd)
            } catch (e: IOException) {
                Log.w(TAG, "BUSY poll IOException (attempt $i): ${e.message}")
                SystemClock.sleep(250)
                continue
            }
            val done = if (epd == 4) resp[0] != 0x00.toByte()   // 7.5": non-zero = done
                       else          resp[0] == 0x00.toByte()    // others: zero = done
            if (done) {
                Log.d(TAG, "Display ready after ${i * 250 + 1000} ms")
                return
            }
            SystemClock.sleep(250)
        }
        // Timeout — not fatal, display may still refresh successfully
        Log.w(TAG, "BUSY poll timed out (26 s) — continuing anyway")
    }

    // -------------------------------------------------------------------------
    //  Low-level transceive with retry
    // -------------------------------------------------------------------------

    /**
     * Send [cmd] and require {0x90, 0x00} back.
     * Retries up to [MAX_RETRIES] times on IO failures; throws [V3ProtocolException]
     * on protocol-level failures.
     */
    private fun transceive(tag: IsoDep, cmd: ByteArray, label: String): ByteArray {
        var lastEx: Exception? = null
        for (attempt in 1..MAX_RETRIES) {
            try {
                val resp = tag.transceive(cmd)
                if (isOk(resp)) return resp
                // Non-OK response — no point retrying a protocol rejection
                throw V3ProtocolException(
                    "[$label] NACK: ${resp.toHex()} (cmd: ${cmd.toHex()})")
            } catch (e: V3ProtocolException) {
                throw e   // propagate immediately
            } catch (e: IOException) {
                lastEx = e
                Log.w(TAG, "[$label] IO error attempt $attempt: ${e.message}")
                if (attempt < MAX_RETRIES) SystemClock.sleep(30L * attempt)
            }
        }
        throw lastEx ?: IOException("[$label] failed after $MAX_RETRIES retries")
    }

    // -------------------------------------------------------------------------
    //  Helpers
    // -------------------------------------------------------------------------

    private fun ByteArray.toHex() = joinToString(" ") { "%02X".format(it) }

    private fun success() = object : FlashResult {
        override val success = true
        override val errMessage = ""
    }

    private fun fail(msg: String): FlashResult {
        Log.e(TAG, msg)
        return object : FlashResult {
            override val success = false
            override val errMessage = msg
        }
    }
}

/** Thrown when the V3 tag returns an unexpected protocol response. */
class V3ProtocolException(message: String) : Exception(message)
