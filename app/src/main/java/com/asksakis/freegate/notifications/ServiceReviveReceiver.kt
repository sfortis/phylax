package com.asksakis.freegate.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Broadcast receiver that revives [FrigateAlertService] when fired. Scheduled via
 * [AlarmManager.setAndAllowWhileIdle] so the kick is cheap-power and bypasses Doze.
 *
 * Purpose: the 15-minute [AlertServiceWatchdogWorker] is too slow after an OEM kill,
 * and the `onTaskRemoved()` path needs an immediate revive rather than waiting on
 * WorkManager. This alarm fires every [SHORT_INTERVAL_MS] / [LONG_INTERVAL_MS] as a
 * second layer of reliability.
 */
class ServiceReviveReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        Log.d(TAG, "Alarm fired — attempting service revive")
        FrigateAlertService.updateForContext(context.applicationContext)
        // Re-arm the next periodic tick. `setAndAllowWhileIdle` is one-shot, so each
        // fire has to schedule the next one explicitly.
        scheduleNext(context.applicationContext)
    }

    companion object {
        private const val TAG = "ServiceRevive"
        private const val REQUEST_CODE = 42_001

        // Short interval: tight revive loop immediately after process termination.
        // `setAndAllowWhileIdle` is throttled to 1 fire per 9-15min in Doze depending on
        // OEM, but outside Doze (most of the day) this fires on schedule.
        private const val SHORT_INTERVAL_MS = 5L * 60 * 1000L

        fun scheduleNext(context: Context, delayMs: Long = SHORT_INTERVAL_MS) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, ServiceReviveReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pi = PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
            val trigger = System.currentTimeMillis() + delayMs
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
                } else {
                    am.set(AlarmManager.RTC_WAKEUP, trigger, pi)
                }
            }.onFailure { Log.w(TAG, "Could not schedule alarm: ${it.message}") }
        }

        /** Kicks an immediate revive, e.g. after onTaskRemoved. */
        fun scheduleImmediate(context: Context) {
            scheduleNext(context, IMMEDIATE_DELAY_MS)
        }

        fun cancel(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, ServiceReviveReceiver::class.java)
            val pi = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            ) ?: return
            runCatching { am.cancel(pi) }
        }

        // Half a second: OS has already committed to killing the process when
        // onTaskRemoved fires; schedule fires just after we're gone so the alarm lands
        // on a fresh process.
        private const val IMMEDIATE_DELAY_MS = 500L
    }
}
