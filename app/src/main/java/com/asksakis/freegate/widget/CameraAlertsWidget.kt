package com.asksakis.freegate.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.asksakis.freegate.R
import com.asksakis.freegate.notifications.CameraMuteStore
import java.util.concurrent.TimeUnit

/**
 * Home screen widget that turns the notifications of one to six cameras on and off. A tap
 * on a camera mutes it in [CameraMuteStore] until it is tapped again, or removes its mute.
 * It changes only this phone: Frigate keeps detecting and recording.
 */
class CameraAlertsWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { render(context, manager, it) }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val store = WidgetStore(context)
        appWidgetIds.forEach { store.removeWidget(it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_TOGGLE) return
        val camera = intent.getStringExtra(EXTRA_CAMERA)?.takeIf { it.isNotEmpty() } ?: return
        toggle(context, camera)
    }

    private fun toggle(context: Context, camera: String) {
        val mutes = CameraMuteStore.getInstance(context)
        val cameraKey = CameraMuteStore.Key(CameraMuteStore.Kind.CAMERA, camera)
        when {
            cameraKey in mutes.activeMutes() -> mutes.unmute(CameraMuteStore.Kind.CAMERA, camera)
            // A group mute covers it: removing that here would also unmute the other cameras
            // of the group, so the app's mute sheet is the place to change it.
            mutes.isCameraMuted(camera, mutes.loadGroupCameras()) ->
                Toast.makeText(context, R.string.widget_alerts_group_muted, Toast.LENGTH_LONG).show()
            else -> mutes.muteUntilTurnedOff(CameraMuteStore.Kind.CAMERA, camera)
        }
        // The store notifies the widgets of a change; a group mute changes nothing, so
        // redraw anyway to drop the pressed state.
        updateAll(context)
    }

    companion object {
        private const val TAG = "CameraAlertsWidget"
        private const val ACTION_TOGGLE = "com.asksakis.freegate.widget.TOGGLE_CAMERA_ALERTS"
        private const val EXTRA_CAMERA = "camera"
        private const val EXPIRY_WORK = "widget_mute_expiry"

        fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val cameras = WidgetStore(context).cameras(widgetId)
            val views = RemoteViews(context.packageName, R.layout.widget_camera_shortcuts)
            views.removeAllViews(R.id.widget_rows)
            if (cameras.isEmpty()) {
                views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
                views.setOnClickPendingIntent(R.id.widget_root, WidgetIntents.configureIntent(context, widgetId))
            } else {
                views.setViewVisibility(R.id.widget_empty, View.GONE)
                views.setOnClickPendingIntent(R.id.widget_root, null)
                val mutes = CameraMuteStore.getInstance(context)
                val groups = mutes.loadGroupCameras()
                var index = 0
                CameraShortcutsWidget.rowSizes(cameras.size).forEach { size ->
                    val row = RemoteViews(context.packageName, R.layout.widget_shortcut_row)
                    cameras.subList(index, index + size).forEach { camera ->
                        row.addView(R.id.widget_row, item(context, camera, mutes.isCameraMuted(camera, groups)))
                    }
                    views.addView(R.id.widget_rows, row)
                    index += size
                }
            }
            manager.updateAppWidget(widgetId, views)
        }

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = HomeWidgets.widgetIds(context, CameraAlertsWidget::class.java)
            ids.forEach { render(context, manager, it) }
            if (ids.isNotEmpty()) scheduleExpiryRedraw(context)
        }

        /** Called by [CameraMuteStore] on every change, from the app, a notification or here. */
        fun onMutesChanged(context: Context) {
            runCatching { updateAll(context) }
                .onFailure { Log.w(TAG, "Could not redraw camera alert widgets: ${it.message}") }
        }

        private fun item(context: Context, camera: String, muted: Boolean): RemoteViews {
            // One layout per state: the colors are resources, so they follow the wallpaper
            // and dark mode of the launcher rather than of this process.
            val layout = if (muted) R.layout.widget_alert_item_muted else R.layout.widget_alert_item
            return RemoteViews(context.packageName, layout).apply {
                setTextViewText(R.id.widget_item_name, HomeWidgets.prettyCamera(camera))
                setTextViewText(
                    R.id.widget_item_state,
                    context.getString(if (muted) R.string.widget_alerts_off else R.string.widget_alerts_on),
                )
                setOnClickPendingIntent(R.id.widget_item, toggleIntent(context, camera))
            }
        }

        private fun toggleIntent(context: Context, camera: String): PendingIntent {
            val intent = Intent(context, CameraAlertsWidget::class.java)
                .setAction(ACTION_TOGGLE)
                .putExtra(EXTRA_CAMERA, camera)
            return PendingIntent.getBroadcast(
                context,
                "alerts:$camera".hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * A timed mute from the app or a notification ends without any change event, so
         * redraw when the next one expires. Mutes from this widget never expire.
         */
        private fun scheduleExpiryRedraw(context: Context) {
            val now = System.currentTimeMillis()
            val next = CameraMuteStore.getInstance(context).activeMutes(now).values
                .filter { it != CameraMuteStore.UNTIL_TURNED_OFF }
                .minOrNull()
            val work = WorkManager.getInstance(context)
            if (next == null) {
                work.cancelUniqueWork(EXPIRY_WORK)
                return
            }
            val request = OneTimeWorkRequestBuilder<MuteExpiryWorker>()
                .setInitialDelay(next - now + EXPIRY_SLACK_MS, TimeUnit.MILLISECONDS)
                .build()
            work.enqueueUniqueWork(EXPIRY_WORK, ExistingWorkPolicy.REPLACE, request)
        }

        /** Redraw a moment after the expiry, so the mute is already gone when it runs. */
        private const val EXPIRY_SLACK_MS = 1_000L
    }
}

/** Redraws [CameraAlertsWidget] after a timed mute has expired. */
class MuteExpiryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        CameraAlertsWidget.updateAll(applicationContext)
        return Result.success()
    }
}
