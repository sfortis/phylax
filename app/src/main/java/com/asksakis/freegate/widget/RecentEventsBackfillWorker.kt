package com.asksakis.freegate.widget

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.asksakis.freegate.notifications.AlertFilter
import com.asksakis.freegate.notifications.CameraMuteStore
import com.asksakis.freegate.notifications.FrigateNotifier
import com.asksakis.freegate.notifications.ReviewCatchupFetcher
import com.asksakis.freegate.notifications.SnapshotDownloader
import com.asksakis.freegate.utils.NetworkUtils
import org.json.JSONObject

/**
 * Fills an empty recent events widget with the latest reviews from Frigate, so it does not
 * start blank. The app keeps no notification history, so the reviews pass through the
 * current notification settings instead: the list shows what would notify today, which
 * can differ from what notified at the time (cooldowns, settings changed since).
 */
class RecentEventsBackfillWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        if (!HomeWidgets.hasWidgets(context, RecentEventsWidget::class.java)) return Result.success()
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val alertsOnly = !prefs.getBoolean("notify_detections", false)
        val fetcher = ReviewCatchupFetcher(context)
        // In a fresh process the first URL is a guess, so a failure moves on to the other one.
        for (baseUrl in NetworkUtils.getInstance(context).candidateBaseUrls()) {
            val reviews = fetcher.fetchLatest(baseUrl, FETCH_LIMIT, alertsOnly) ?: continue
            fill(baseUrl, reviews)
            return Result.success()
        }
        Log.d(TAG, "Backfill failed: server not reachable (attempt $runAttemptCount)")
        return if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
    }

    private suspend fun fill(baseUrl: String, reviews: List<JSONObject>) {
        val context = applicationContext
        val filter = AlertFilter.fromPreferences(PreferenceManager.getDefaultSharedPreferences(context))
        val mutes = CameraMuteStore.getInstance(context)
        val groups = mutes.loadGroupCameras()
        val alerts = reviews
            .mapNotNull { filter.evaluate(TOPIC, envelope(it)) }
            // A muted camera is one the user does not want to hear about now.
            .filterNot { mutes.isCameraMuted(it.camera, groups) }
            .sortedByDescending { it.startTimeSec ?: 0.0 }
            .take(RecentEventsStore.MAX_EVENTS)
        val notifier = FrigateNotifier(context)
        val downloader = SnapshotDownloader(context)
        val events = alerts.map { alert ->
            val image = alert.thumbnailPath?.let { downloader.download(baseUrl, it) }
            HomeWidgets.eventFor(alert, notifier.describeSubject(alert)) to image
        }
        HomeWidgets.addRecentEvents(context, events)
        Log.d(TAG, "Backfilled ${events.size} of ${reviews.size} review(s)")
    }

    companion object {
        private const val TAG = "RecentEventsBackfill"
        private const val WORK_NAME = "widget_recent_backfill"
        private const val TOPIC = "reviews"

        /** Enough reviews to find ten that pass a camera or zone filter. */
        private const val FETCH_LIMIT = 100
        private const val MAX_RETRIES = 3

        /** The same envelope the reconnect catch-up builds, so the filter reads it as a live frame. */
        private fun envelope(review: JSONObject): JSONObject = JSONObject()
            .put("topic", TOPIC)
            .put("payload", JSONObject().put("type", "end").put("after", review))

        /** Run once, when a network is available. A run that is already queued is kept. */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<RecentEventsBackfillWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
