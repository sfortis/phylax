package com.asksakis.freegate.notifications

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Utility for the "ignore battery optimizations" workflow. We need the user to grant
 * this once, otherwise Samsung / MIUI / generic doze will kill the alert listener
 * after ~a few minutes in the background.
 */
object BatteryOptHelper {

    fun isIgnoringOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Direct request dialog — preferred path. Requires REQUEST_IGNORE_BATTERY_OPTIMIZATIONS. */
    fun requestIgnore(context: Context) {
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            // Fallback to the generic battery-optimization settings page.
            openSettings(context)
        }
    }

    /** Fallback: open the system battery-optimization screen so the user can pick the app manually. */
    fun openSettings(context: Context) {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            // Nothing else to try.
        }
    }
}
