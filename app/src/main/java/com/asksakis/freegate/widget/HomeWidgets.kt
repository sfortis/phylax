package com.asksakis.freegate.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.asksakis.freegate.R
import com.asksakis.freegate.notifications.AlertFilter
import java.util.Calendar
import java.util.Date

/**
 * Entry point for the home screen widgets (issue #38): the update the alert pipeline
 * triggers, and the helpers the widgets share. The intents they open are [WidgetIntents].
 */
object HomeWidgets {

    /**
     * Called by the alert pipeline after a notification's snapshot download. Updates the
     * last event widget with the image it already has, and asks for a fresh frame for the
     * image widgets that show the same camera.
     */
    fun onAlert(context: Context, alert: AlertFilter.Alert, subject: String, image: Bitmap?) {
        // Runs on the alert path: a widget failure must never cost the notification.
        runCatching { updateForAlert(context, alert, subject, image) }
            .onFailure { Log.w(TAG, "Widget update for alert ${alert.id} failed: ${it.message}") }
    }

    private fun updateForAlert(context: Context, alert: AlertFilter.Alert, subject: String, image: Bitmap?) {
        val event = eventFor(alert, subject)
        val timeMs = event.timeMs
        // Snapshot downloads finish in any order, so an older alert can arrive after a newer
        // one. The lock keeps the text and the picture of one event together.
        synchronized(eventLock) {
            if (hasWidgets(context, LastEventWidget::class.java)) {
                val store = WidgetStore(context)
                val stored = store.lastEvent()
                if (stored == null || stored.reviewId == alert.id || stored.timeMs <= timeMs) {
                    store.setLastEvent(event, image)
                    LastEventWidget.updateAll(context)
                }
            }
            if (hasWidgets(context, RecentEventsWidget::class.java)) {
                RecentEventsStore(context).add(event, image)
                RecentEventsWidget.updateAll(context)
            }
        }
        if (CameraSnapshotWidget.showsCamera(context, alert.camera)) {
            WidgetRefreshWorker.refreshNow(context, alert.camera)
        }
    }

    /** The widget entry for [alert], with the notification headline as its title. */
    fun eventFor(alert: AlertFilter.Alert, subject: String): WidgetStore.LastEvent {
        val place = (listOf(alert.camera) + alert.zones).map(::prettyCamera).filter { it.isNotEmpty() }
        return WidgetStore.LastEvent(
            reviewId = alert.id,
            camera = alert.camera,
            title = subject,
            subtitle = place.joinToString(" · "),
            isAlert = alert.severity == AlertFilter.Severity.ALERT,
            timeMs = alert.startTimeSec?.let { (it * MILLIS_PER_SECOND).toLong() } ?: System.currentTimeMillis(),
        )
    }

    /**
     * Add earlier events to the recent events list, when the widget is added. An event
     * the alert pipeline stored in the meantime is kept as it is, because it may already
     * carry a name or plate that Frigate recognised later.
     */
    fun addRecentEvents(context: Context, events: List<Pair<WidgetStore.LastEvent, Bitmap?>>) {
        synchronized(eventLock) {
            // The widget may have been removed while the reviews were downloading.
            if (!hasWidgets(context, RecentEventsWidget::class.java)) return
            val store = RecentEventsStore(context)
            val known = store.events().map { it.reviewId }.toSet()
            events.filter { it.first.reviewId !in known }.forEach { (event, image) -> store.add(event, image) }
        }
        RecentEventsWidget.updateAll(context)
    }

    /**
     * The clock time for today, "Yesterday 19:51", then "Nov 26 19:51", and the year only
     * for an earlier year. Short, so a row keeps room for its title.
     */
    fun formatTime(context: Context, timeMs: Long): String {
        val time = DateFormat.getTimeFormat(context).format(Date(timeMs))
        if (DateUtils.isToday(timeMs)) return time
        if (DateUtils.isToday(timeMs + DateUtils.DAY_IN_MILLIS)) {
            return context.getString(R.string.widget_time_yesterday, time)
        }
        var flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
        if (Calendar.getInstance().apply { timeInMillis = timeMs }.get(Calendar.YEAR) ==
            Calendar.getInstance().get(Calendar.YEAR)
        ) {
            flags = flags or DateUtils.FORMAT_NO_YEAR
        }
        return "${DateUtils.formatDateTime(context, timeMs, flags)} $time"
    }

    /** Show [image] in a picture widget, or the small camera icon while there is none. */
    fun showPicture(manager: AppWidgetManager, widgetId: Int, views: RemoteViews, image: Bitmap?) {
        if (image != null) {
            val oldLauncher = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
            val shown = if (oldLauncher) roundedCrop(manager, widgetId, image) else image
            views.setImageViewBitmap(R.id.widget_image, shown)
        }
        views.setViewVisibility(R.id.widget_image, if (image != null) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widget_placeholder, if (image != null) View.GONE else View.VISIBLE)
    }

    /**
     * Before Android 12 a widget ignores `clipToOutline`, so the picture would show square
     * corners over the rounded card. Crop it to the widget's shape and round it here.
     */
    private fun roundedCrop(manager: AppWidgetManager, widgetId: Int, image: Bitmap): Bitmap {
        val options = manager.getAppWidgetOptions(widgetId)
        val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        if (widthDp <= 0 || heightDp <= 0) return image
        return WidgetBitmaps.cropRounded(image, widthDp.toFloat() / heightDp, CORNER_RADIUS_DP / widthDp)
    }

    fun hasWidgets(context: Context, provider: Class<*>): Boolean = widgetIds(context, provider).isNotEmpty()

    fun widgetIds(context: Context, provider: Class<*>): IntArray =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, provider))

    /** Camera names as the notifications show them: `front_door` becomes `Front Door`. */
    fun prettyCamera(raw: String): String = raw.replace('_', ' ')
        .split(' ')
        .filter { it.isNotEmpty() }
        .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }

    private const val TAG = "HomeWidgets"
    /** Matches widget_radius, the card corners below Android 12. */
    private const val CORNER_RADIUS_DP = 16f
    private const val MILLIS_PER_SECOND = 1000.0
    private val eventLock = Any()
}
