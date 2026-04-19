package com.asksakis.freegate.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.preference.PreferenceManager

/**
 * Restarts [FrigateAlertService] after device boot, but only when the user actually
 * has notifications enabled. Gating here (not at manifest level) means the receiver
 * does literally nothing when the feature is off — no service start, no wake locks.
 */
class FrigateBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) return
        val notificationsEnabled = PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean("notifications_enabled", false)
        if (!notificationsEnabled) {
            Log.d(TAG, "Boot received but notifications disabled; skipping service start")
            return
        }
        Log.d(TAG, "Boot received, starting FrigateAlertService")
        FrigateAlertService.updateForContext(context)
    }

    companion object {
        private const val TAG = "FrigateBootReceiver"
    }
}
