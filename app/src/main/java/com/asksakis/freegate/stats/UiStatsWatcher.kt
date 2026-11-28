package com.asksakis.freegate.stats

import android.content.Context
import com.asksakis.freegate.utils.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Lifecycle-scoped HTTP poller for the Frigate `/api/stats` endpoint. Runs only while
 * the Activity is visible so we don't pay any battery for a poll loop in the background
 * (the notification service does its own thing).
 *
 * HTTP is preferred over the `stats` WS topic for this UI because Frigate only publishes
 * that topic every ~15 seconds, which looks frozen on a live metrics chip. The REST
 * endpoint returns the same payload and we can sample it as often as we like.
 */
class UiStatsWatcher(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val statsClient = FrigateStatsClient(context)

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { pollLoop() }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }

    private suspend fun pollLoop() {
        while (scope.isActive) {
            val baseUrl = resolveBaseUrl()
            if (baseUrl == null) {
                delay(IDLE_INTERVAL_MS)
                continue
            }
            val ok = pollOnce(baseUrl)
            delay(if (ok) POLL_INTERVAL_MS else BACKOFF_INTERVAL_MS)
        }
    }

    private suspend fun pollOnce(baseUrl: String): Boolean {
        val json = statsClient.fetch(baseUrl) ?: return false
        FrigateStatsRepository.ingest(json)
        // Keep the server status widget current while the app is open; it throttles itself.
        com.asksakis.freegate.widget.ServerStatsWidget.onLiveStats(context, json)
        return true
    }

    private fun resolveBaseUrl(): String? =
        NetworkUtils.getInstance(context).bestKnownBaseUrl()

    companion object {
        private const val POLL_INTERVAL_MS = 2_000L
        private const val BACKOFF_INTERVAL_MS = 5_000L
        private const val IDLE_INTERVAL_MS = 3_000L
    }
}
