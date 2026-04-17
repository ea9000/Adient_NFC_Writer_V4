package com.adient.nfcv4

const val PackageName = "com.adient.nfcv4"

const val WaveShareUID = "WSDZ10m"

// Order matches WS SDK Enum (except off by 1, due to zero-index)
val ScreenSizes = arrayOf(
    "2.13\"",
    "2.9\"",
    "4.2\"",
    "7.5\"",
    "7.5\" HD",
    "2.7\"",
    "2.9\" v.B",
)

val DefaultScreenSize = ScreenSizes[3]

val ScreenSizesInPixels = mapOf(
    "2.13\""    to Pair(250, 128),
    "2.9\""     to Pair(296, 128),
    "4.2\""     to Pair(400, 300),
    "7.5\""     to Pair(800, 480),
    "7.5\" HD"  to Pair(880, 528),
    "2.7\""     to Pair(264, 176),
    "2.9\" v.B" to Pair(296, 128),
)

object Constants {
    var Preference_File_Key = "Preferences"
    var PreferenceKeys = PrefKeys
}

object PrefKeys {
    var DisplaySize      = "Display_Size"
    var GeneratedImgPath = "Generated_Image_Path"
    var BaseUrl          = "baseUrl"
    var ProtocolMode     = "Protocol_Mode"
}

object IntentKeys {
    var GeneratedImgPath = "$PackageName.imgUri"
    var GeneratedImgMime = "$PackageName.imgMime"
    var PickListFileName = "$PackageName.picklistFile"
}

val GeneratedImageFilename = "generated.png"

// ── Protocol modes ────────────────────────────────────────────────────────────

/**
 * The five protocol modes selectable from the NfcFlasher spinner.
 *
 * AUTO      – detect V2/V3 from techList (legacy behaviour, default)
 * FORCE_V2  – always route to the NfcA / 0xCD handler regardless of techList
 * FORCE_V3  – always route to the IsoDep / 0x74 handler regardless of techList
 * PROBE     – send safe status-poll commands only; no image write
 * IDENTIFY  – pure diagnostic read: UID, manufacturer, ATQA/SAK, GET_VERSION,
 *             protocol probes — never writes anything to the tag
 */
enum class ProtocolMode(val displayName: String) {
    AUTO("🔄 Auto (detect V2/V3)"),
    FORCE_V2("⚡ Force V2 (NfcA)"),
    FORCE_V3("📡 Force V3 (IsoDep)"),
    PROBE("🔍 Probe protocols"),
    IDENTIFY("📋 Identify hardware (read-only)");

    companion object {
        fun fromOrdinal(n: Int): ProtocolMode = values().getOrElse(n) { AUTO }
    }
}
