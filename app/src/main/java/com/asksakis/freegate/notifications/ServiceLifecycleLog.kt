package com.asksakis.freegate.notifications

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * Minimal persistent lifecycle telemetry for [FrigateAlertService]. Writes single-key
 * timestamps to SharedPreferences so the Reliability section of Settings can show a
 * real timeline, independent of logcat's ring buffer.
 *
 * We record the previous `service_started_ms` as `service_previous_started_ms` just
 * before overwriting — that lets the UI show "previous run lasted X", useful for
 * spotting OEM kills (the previous run will end far in the past without an orderly
 * destroy in between).
 */
object ServiceLifecycleLog {

    const val PREF_SERVICE_STARTED_MS = "svc_started_ms"
    const val PREF_SERVICE_PREV_STARTED_MS = "svc_prev_started_ms"
    const val PREF_SERVICE_DESTROYED_MS = "svc_destroyed_ms"
    const val PREF_WS_CONNECTED_MS = "svc_ws_connected_ms"
    const val PREF_WS_DISCONNECTED_MS = "svc_ws_disconnected_ms"

    fun recordStart(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val previous = prefs.getLong(PREF_SERVICE_STARTED_MS, 0L)
        prefs.edit().apply {
            if (previous > 0) putLong(PREF_SERVICE_PREV_STARTED_MS, previous)
            putLong(PREF_SERVICE_STARTED_MS, System.currentTimeMillis())
        }.apply()
    }

    fun recordDestroy(context: Context) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putLong(PREF_SERVICE_DESTROYED_MS, System.currentTimeMillis())
            .apply()
    }

    fun recordWsConnected(context: Context) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putLong(PREF_WS_CONNECTED_MS, System.currentTimeMillis())
            .apply()
    }

    fun recordWsDisconnected(context: Context) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putLong(PREF_WS_DISCONNECTED_MS, System.currentTimeMillis())
            .apply()
    }
}
