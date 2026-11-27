package com.asksakis.freegate.widget

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.preference.PreferenceManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.asksakis.freegate.notifications.SnapshotDownloader
import com.asksakis.freegate.stats.FrigateStatsClient
import com.asksakis.freegate.stats.FrigateStatsRepository
import com.asksakis.freegate.utils.NetworkUtils
import java.util.concurrent.TimeUnit

/**
 * Fetches the latest frame of the cameras the image widgets show and the server stats for
 * the status widget, and redraws them.
 *
 * Runs periodically while at least one of those widgets exists, at the interval set in
 * Settings > Advanced (15 minutes by default, the shortest WorkManager allows), once for
 * one camera after an alert, and once after a widget is added. Every run needs a network,
 * so an offline phone does no work.
 */
class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val only = inputData.getString(KEY_CAMERA)
        val withStatus = only == null && HomeWidgets.hasWidgets(applicationContext, ServerStatsWidget::class.java)
        // In a fresh process the first URL is a guess, so a failure moves on to the other one.
        val reached = NetworkUtils.getInstance(applicationContext).candidateBaseUrls().any { baseUrl ->
            // A camera-specific run (after an alert) leaves the server status alone.
            val camerasOk = refreshCameras(baseUrl, only)
            val statusOk = withStatus && refreshServerStatus(baseUrl)
            camerasOk || statusOk
        }
        if (!reached && withStatus) {
            Log.d(TAG, "Server status refresh failed")
            ServerStatsWidget.markUnreachable(applicationContext)
        }
        CameraSnapshotWidget.updateAll(applicationContext)
        return Result.success()
    }

    /** True when at least one frame was downloaded. */
    private suspend fun refreshCameras(baseUrl: String, only: String?): Boolean {
        val cameras = CameraSnapshotWidget.cameras(applicationContext)
            .filter { only == null || it == only }
        if (cameras.isEmpty()) return false
        val downloader = SnapshotDownloader(applicationContext)
        val store = WidgetStore(applicationContext)
        var updated = 0
        cameras.forEach { camera ->
            val image = downloader.download(baseUrl, "/api/${Uri.encode(camera)}/latest.jpg?height=$FRAME_HEIGHT")
            if (image != null) {
                store.setSnapshot(camera, image, System.currentTimeMillis())
                updated++
            }
        }
        Log.d(TAG, "Refreshed $updated of ${cameras.size} widget camera(s)")
        return updated > 0
    }

    /** True when the stats were fetched and stored. */
    private suspend fun refreshServerStatus(baseUrl: String): Boolean {
        val stats = FrigateStatsClient(applicationContext).fetch(baseUrl)
            ?.let { FrigateStatsRepository.parse(it) } ?: return false
        ServerStatsWidget.store(applicationContext, stats)
        return true
    }

    companion object {
        private const val TAG = "WidgetRefreshWorker"
        private const val PERIODIC_WORK = "widget_snapshot_refresh"
        private const val ONE_TIME_WORK = "widget_snapshot_now_"
        private const val KEY_CAMERA = "camera"
        /** The shortest periodic interval WorkManager accepts. */
        private const val MIN_REFRESH_MINUTES = 15

        /** Frigate scales `latest.jpg` to this height, which is enough for a widget. */
        private const val FRAME_HEIGHT = 360

        private val networkConstraint = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** The refresh setting in Settings > Advanced, stored as a slider step. */
        const val PREF_REFRESH_STEP = "widget_refresh_step"

        /** The slider's last step: no periodic refresh, only alerts and the open app. */
        const val REFRESH_OFF_STEP = 34
        private const val STEP_MINUTES = 5

        /** Minutes between refreshes for a slider [step], or null for [REFRESH_OFF_STEP]. */
        fun minutesForStep(step: Int): Int? =
            if (step >= REFRESH_OFF_STEP) null else MIN_REFRESH_MINUTES + step.coerceAtLeast(0) * STEP_MINUTES

        /**
         * Keep the periodic job while an image or server status widget exists and the
         * setting is not [REFRESH_OFF_STEP], and drop it otherwise. Pass [step] when the
         * setting is being changed, because the new value is not saved yet at that point.
         */
        fun updateSchedule(context: Context, step: Int? = null) {
            val minutes = minutesForStep(
                step ?: PreferenceManager.getDefaultSharedPreferences(context).getInt(PREF_REFRESH_STEP, 0),
            )
            val needed = HomeWidgets.hasWidgets(context, CameraSnapshotWidget::class.java) ||
                HomeWidgets.hasWidgets(context, ServerStatsWidget::class.java)
            val work = WorkManager.getInstance(context)
            if (!needed || minutes == null) {
                work.cancelUniqueWork(PERIODIC_WORK)
                return
            }
            val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(
                minutes.toLong(),
                TimeUnit.MINUTES,
            ).setConstraints(networkConstraint).build()
            // UPDATE keeps the job's timing when nothing changed and applies a new interval
            // when the setting moved.
            work.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /** Refresh [camera] now, or every widget camera when null. */
        fun refreshNow(context: Context, camera: String? = null) {
            val request = OneTimeWorkRequestBuilder<WidgetRefreshWorker>()
                .setConstraints(networkConstraint)
                .apply { if (camera != null) setInputData(workDataOf(KEY_CAMERA to camera)) }
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    if (camera == null) ONE_TIME_WORK + "all" else ONE_TIME_WORK + "camera:" + camera,
                    ExistingWorkPolicy.REPLACE,
                    request,
                )
        }
    }
}
