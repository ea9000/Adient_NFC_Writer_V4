package com.adient.nfcv4

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import com.adient.nfcv4.Constants.PreferenceKeys
import com.adient.nfcv4.Constants.Preference_File_Key

class Preferences {
    private var mActivity: Activity
    private var mAppContext: Context

    constructor(activity: Activity) {
        this.mActivity = activity
        this.mAppContext = activity.applicationContext
    }

    fun getPreferences(): SharedPreferences {
        return this.mAppContext.getSharedPreferences(Preference_File_Key, Context.MODE_PRIVATE)
    }

    fun getScreenSize(): String {
        val screenSize = this.getPreferences().getString(PreferenceKeys.DisplaySize, DefaultScreenSize)
        return screenSize ?: DefaultScreenSize
    }

    fun getScreenSizeEnum(): Int {
        val screenSize: String = this.getPreferences().getString(PreferenceKeys.DisplaySize, DefaultScreenSize)!!
        return (ScreenSizes.indexOf(screenSize) + 1)
    }

    fun getScreenSizePixels(): Pair<Int, Int> {
        val screenSize: String = this.getPreferences().getString(PreferenceKeys.DisplaySize, DefaultScreenSize)!!
        return ScreenSizesInPixels[screenSize]!!
    }

    fun getProtocolMode(): ProtocolMode {
        val ordinal = getPreferences().getInt(PrefKeys.ProtocolMode, 0)
        return ProtocolMode.fromOrdinal(ordinal)
    }

    fun saveProtocolMode(mode: ProtocolMode) {
        getPreferences().edit().putInt(PrefKeys.ProtocolMode, mode.ordinal).apply()
    }

    fun showScreenSizePicker(callback: (String) -> Void?) {
        val alertBuilder = AlertDialog.Builder(this.mActivity)
        alertBuilder
            .setTitle("Pick Your Screen Size")
            .setItems(ScreenSizes) { _, which ->
                val selectedSize = ScreenSizes[which]
                with(this.getPreferences().edit()) {
                    putString(PreferenceKeys.DisplaySize, selectedSize)
                    apply()
                }
                callback(selectedSize)
            }
        alertBuilder.show()
    }
}