package com.asksakis.freegate.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.asksakis.freegate.R
import com.asksakis.freegate.notifications.AlertFilter
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
        val store = WidgetStore(context)
        val timeMs = alert.startTimeSec?.let { (it * MILLIS_PER_SECOND).toLong() } ?: System.currentTimeMillis()
        // Snapshot downloads finish in any order, so an older alert can arrive after a newer
        // one. The lock keeps the text and the picture of one event together.
        if (hasWidgets(context, LastEventWidget::class.java)) synchronized(lastEventLock) {
            val stored = store.lastEvent()
            if (stored != null && stored.reviewId != alert.id && stored.timeMs > timeMs) return@synchronized
            val place = (listOf(alert.camera) + alert.zones).map(::prettyCamera).filter { it.isNotEmpty() }
            val event = WidgetStore.LastEvent(
                reviewId = alert.id,
                camera = alert.camera,
                title = subject,
                subtitle = place.joinToString(" · "),
                isAlert = alert.severity == AlertFilter.Severity.ALERT,
                timeMs = timeMs,
            )
            store.setLastEvent(event, image)
            LastEventWidget.updateAll(context)
        }
        if (CameraSnapshotWidget.showsCamera(context, alert.camera)) {
            WidgetRefreshWorker.refreshNow(context, alert.camera)
        }
    }

    /** The clock time for today, the date and time for anything older. */
    fun formatTime(context: Context, timeMs: Long): String {
        val time = DateFormat.getTimeFormat(context).format(Date(timeMs))
        if (DateUtils.isToday(timeMs)) return time
        return "${DateFormat.getMediumDateFormat(context).format(Date(timeMs))} $time"
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
        val ratio = widthDp.toFloat() / heightDp
        val cropWidth: Int
        val cropHeight: Int
        if (image.width.toFloat() / image.height > ratio) {
            cropHeight = image.height
            cropWidth = (image.height * ratio).toInt().coerceAtLeast(1)
        } else {
            cropWidth = image.width
            cropHeight = (image.width / ratio).toInt().coerceAtLeast(1)
        }
        val shader = BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(
                Matrix().apply {
                    setTranslate(-(image.width - cropWidth) / 2f, -(image.height - cropHeight) / 2f)
                },
            )
        }
        val radius = CORNER_RADIUS_DP * cropWidth / widthDp
        val out = Bitmap.createBitmap(cropWidth, cropHeight, Bitmap.Config.ARGB_8888)
        Canvas(out).drawRoundRect(
            RectF(0f, 0f, cropWidth.toFloat(), cropHeight.toFloat()),
            radius,
            radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader },
        )
        return out
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
    /** Matches the corner radius of widget_background. */
    private const val CORNER_RADIUS_DP = 16f
    private const val MILLIS_PER_SECOND = 1000.0
    private val lastEventLock = Any()
}
