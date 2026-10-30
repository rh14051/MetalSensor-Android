package com.metalsensor.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Simple SharedPreferences store:
 *  - last connected device (MAC + display name) for automatic reconnection
 *  - last applied parameter values, restored when the app starts
 */
class PrefsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("metal_sensor_prefs", Context.MODE_PRIVATE)

    var savedMac: String?
        get() = prefs.getString(KEY_MAC, null)
        set(value) = prefs.edit().putString(KEY_MAC, value).apply()

    var savedName: String?
        get() = prefs.getString(KEY_NAME, null)
        set(value) = prefs.edit().putString(KEY_NAME, value).apply()

    fun loadParam(key: String, def: String): String = prefs.getString(key, def) ?: def

    fun saveParam(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    /** Whether the chart draws the orange motion bands (default: on). */
    var showMotionChart: Boolean
        get() = prefs.getBoolean(KEY_SHOW_MOTION_CHART, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_MOTION_CHART, value).apply()

    companion object {
        private const val KEY_MAC = "device_mac"
        private const val KEY_NAME = "device_name"
        private const val KEY_SHOW_MOTION_CHART = "show_motion_chart"

        const val PARAM_THRESHOLD_ON = "param_threshold_on"
        const val PARAM_THRESHOLD_OFF = "param_threshold_off"
        const val PARAM_ON_TIME = "param_on_time"
        const val PARAM_OFF_TIME = "param_off_time"
        const val PARAM_ALPHA = "param_alpha"
    }
}
