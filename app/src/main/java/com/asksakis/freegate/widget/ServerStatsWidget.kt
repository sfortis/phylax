package com.asksakis.freegate.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.SystemClock
import android.widget.RemoteViews
import com.asksakis.freegate.R
import com.asksakis.freegate.stats.FrigateStats
import com.asksakis.freegate.stats.FrigateStatsRepository
import org.json.JSONObject

/**
 * Home screen widget with the health of the Frigate server: whether it answers, CPU, GPU,
 * memory, detector inference time, how many cameras deliver frames, and uptime. A tap
 * opens the app.
 *
 * It refreshes with [WidgetRefreshWorker] every 15 minutes, and from the toolbar's stats
 * poller while the app is open ([onLiveStats]), at most once a minute. The live path adds
 * no request of its own.
 */
class ServerStatsWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { render(context, manager, it) }
        WidgetRefreshWorker.updateSchedule(context)
    }

    override fun onEnabled(context: Context) {
        WidgetRefreshWorker.updateSchedule(context)
        WidgetRefreshWorker.refreshNow(context)
    }

    override fun onDisabled(context: Context) {
        WidgetRefreshWorker.updateSchedule(context)
        WidgetStore(context).clearServerStatus()
    }

    companion object {
        private const val LIVE_UPDATE_INTERVAL_MS = 60_000L

        @Volatile
        private var lastLiveUpdateMs = 0L

        /** Called with every stats body the toolbar poller receives. */
        fun onLiveStats(context: Context, json: JSONObject) {
            // Throttled before the widget lookup, which is a call into the system: the poller
            // runs every 2 seconds, with or without a status widget.
            val now = SystemClock.elapsedRealtime()
            if (lastLiveUpdateMs != 0L && now - lastLiveUpdateMs < LIVE_UPDATE_INTERVAL_MS) return
            lastLiveUpdateMs = now
            if (!HomeWidgets.hasWidgets(context, ServerStatsWidget::class.java)) return
            FrigateStatsRepository.parse(json)?.let { store(context, it) }
        }

        /** Save a successful fetch and redraw. */
        fun store(context: Context, stats: FrigateStats) {
            val online = stats.cameras.count { (it.cameraFps ?: 0.0) > 0.0 }
            WidgetStore(context).setServerStatus(
                WidgetStore.ServerStatus(
                    cpuPercent = stats.cpuPercent,
                    gpuPercent = stats.gpuPercent,
                    memoryPercent = stats.memoryPercent,
                    inferenceMs = stats.detectors.mapNotNull { it.inferenceMs }.minOrNull(),
                    camerasOnline = online,
                    camerasTotal = stats.cameras.size,
                    uptimeSeconds = stats.uptimeSeconds,
                    timeMs = stats.receivedAtMs,
                    reachable = true,
                ),
            )
            updateAll(context)
        }

        /** Keep the last values but show that the latest attempt failed. */
        fun markUnreachable(context: Context) {
            val store = WidgetStore(context)
            val last = store.serverStatus()
            store.setServerStatus(
                last?.copy(reachable = false, timeMs = System.currentTimeMillis())
                    ?: WidgetStore.ServerStatus(null, null, null, null, 0, 0, null, System.currentTimeMillis(), false),
            )
            updateAll(context)
        }

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            HomeWidgets.widgetIds(context, ServerStatsWidget::class.java).forEach { render(context, manager, it) }
        }

        fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val status = WidgetStore(context).serverStatus()
            val views = RemoteViews(context.packageName, R.layout.widget_server_stats)
            views.setOnClickPendingIntent(R.id.widget_root, WidgetIntents.homeIntent(context))
            // No status yet: a grey dot and a waiting text, not a red "Unreachable" before
            // anything was tried.
            val summary = when {
                status == null -> context.getString(R.string.widget_stats_no_data)
                status.reachable -> summary(context, status)
                else -> context.getString(R.string.widget_stats_unreachable)
            }
            val dot = when {
                status == null -> COLOR_UNKNOWN
                status.reachable -> COLOR_ONLINE
                else -> COLOR_OFFLINE
            }
            views.setTextViewText(R.id.widget_status, summary)
            views.setTextColor(R.id.widget_status_dot, dot)
            views.setTextViewText(R.id.widget_time, status?.let { HomeWidgets.formatTime(context, it.timeMs) } ?: "")
            views.setTextViewText(R.id.widget_cpu, percent(status?.cpuPercent))
            views.setTextViewText(R.id.widget_gpu, percent(status?.gpuPercent))
            views.setTextViewText(R.id.widget_mem, percent(status?.memoryPercent))
            views.setTextViewText(R.id.widget_inference, inference(status?.inferenceMs))
            manager.updateAppWidget(widgetId, views)
        }

        /** "6/6 cameras · up 3d 4h", or a waiting text before the first fetch. */
    private fun summary(context: Context, status: WidgetStore.ServerStatus?): String {
            if (status == null) return context.getString(R.string.widget_stats_no_data)
            val cameras = context.getString(R.string.widget_stats_cameras, status.camerasOnline, status.camerasTotal)
            val uptime = status.uptimeSeconds?.let { context.getString(R.string.widget_stats_uptime, formatUptime(it)) }
            return listOfNotNull(cameras, uptime).joinToString(" · ")
        }

        internal fun percent(value: Int?): String = value?.let { "$it%" } ?: NO_VALUE

        internal fun inference(ms: Double?): String = ms?.let { "${Math.round(it)} ms" } ?: NO_VALUE

        /** "3d 4h", "5h 12m" or "8m": the two largest units. */
        internal fun formatUptime(seconds: Long): String {
            val minutes = seconds / SECONDS_PER_MINUTE
            val hours = minutes / MINUTES_PER_HOUR
            val days = hours / HOURS_PER_DAY
            return when {
                days > 0 -> "${days}d ${hours % HOURS_PER_DAY}h"
                hours > 0 -> "${hours}h ${minutes % MINUTES_PER_HOUR}m"
                else -> "${minutes}m"
            }
        }

        private const val NO_VALUE = "--"
        private const val COLOR_ONLINE = 0xFF4CAF50.toInt()
        private const val COLOR_OFFLINE = 0xFFE53935.toInt()
        private const val COLOR_UNKNOWN = 0xFF9E9E9E.toInt()
        private const val SECONDS_PER_MINUTE = 60L
        private const val MINUTES_PER_HOUR = 60L
        private const val HOURS_PER_DAY = 24L
    }
}
