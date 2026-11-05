package com.wellness.overlay3d.util

import android.content.Context
import android.content.SharedPreferences

/**
 * CompanionPreferences: Settings for intervals, entrance styles, and companion state.
 */
object CompanionPreferences {
    private const val PREFS_NAME = "myfrnd_preferences"

    private const val KEY_WATER_INTERVAL_MIN = "key_water_interval_min"
    private const val KEY_EYE_STRAIN_MIN = "key_eye_strain_min"
    private const val KEY_COMPANION_ENABLED = "key_companion_enabled"
    private const val KEY_POSITION_MODE = "key_position_mode"

    const val POSITION_RANDOM = "random"
    const val POSITION_AUTO = "random"
    const val POSITION_THREAD_DROP = "thread_drop"
    const val POSITION_LEFT_RIGHT = "left_right"
    const val POSITION_RIGHT_LEFT = "right_left"
    const val POSITION_TOP_RIGHT = "top_right"
    const val POSITION_BOTTOM = "bottom"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getWaterIntervalMinutes(context: Context): Int {
        return getPrefs(context).getInt(KEY_WATER_INTERVAL_MIN, 30) // Default 30 min
    }

    fun setWaterIntervalMinutes(context: Context, minutes: Int) {
        getPrefs(context).edit().putInt(KEY_WATER_INTERVAL_MIN, minutes).apply()
    }

    fun getEyeStrainMinutes(context: Context): Int {
        return getPrefs(context).getInt(KEY_EYE_STRAIN_MIN, 15) // Default 15 min
    }

    fun setEyeStrainMinutes(context: Context, minutes: Int) {
        getPrefs(context).edit().putInt(KEY_EYE_STRAIN_MIN, minutes).apply()
    }

    fun isCompanionEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_COMPANION_ENABLED, true)
    }

    fun setCompanionEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_COMPANION_ENABLED, enabled).apply()
    }

    fun getPositionMode(context: Context): String {
        return getPrefs(context).getString(KEY_POSITION_MODE, POSITION_RANDOM) ?: POSITION_RANDOM
    }

    fun setPositionMode(context: Context, mode: String) {
        getPrefs(context).edit().putString(KEY_POSITION_MODE, mode).apply()
    }
}
