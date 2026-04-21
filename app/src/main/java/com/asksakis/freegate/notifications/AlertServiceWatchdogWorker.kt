package com.asksakis.freegate.notifications

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Periodic safety net that re-starts the alert foreground service if the OS killed it
 * (OEM doze, memory pressure, Samsung's aggressive background policies). WorkManager's
 * minimum period of 15 minutes is fine here because the primary liveness mechanism is
 * still START_STICKY + the service's own wakelocks; this worker only catches cases where
 * the process was terminated outright.
 */
class AlertServiceWatchdogWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    override fun doWork(): Result {
        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        if (!prefs.getBoolean("notifications_enabled", false)) {
            Log.d(TAG, "Notifications disabled; skipping revive")
            return Result.success()
        }
        Log.d(TAG, "Watchdog tick — ensuring alert FGS is running")
        FrigateAlertService.updateForContext(applicationContext)
        return Result.success()
    }

    companion object {
        private const val TAG = "AlertWatchdog"
        private const val WORK_NAME = "frigate_alert_watchdog"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<AlertServiceWatchdogWorker>(
                15, TimeUnit.MINUTES,
            ).setConstraints(constraints).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
