package com.asksakis.freegate.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.view.View
import android.widget.RemoteViews
import com.asksakis.freegate.R

/**
 * Home screen widget with the latest frame of one camera and the time it was taken. A tap
 * opens the camera's live view.
 *
 * The frame refreshes when an alert for the camera arrives ([HomeWidgets.onAlert]) and
 * every 15 minutes through [WidgetRefreshWorker].
 */
class CameraSnapshotWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { render(context, manager, it) }
        WidgetRefreshWorker.updateSchedule(context)
    }

    override fun onEnabled(context: Context) {
        WidgetRefreshWorker.updateSchedule(context)
    }

    override fun onDisabled(context: Context) {
        WidgetRefreshWorker.updateSchedule(context)
        WidgetStore(context).pruneSnapshots(emptySet())
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val store = WidgetStore(context)
        appWidgetIds.forEach { store.removeWidget(it) }
        // Pictures of the user's home do not stay on disk after their widget is gone.
        store.pruneSnapshots(cameras(context))
    }

    companion object {

        fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val store = WidgetStore(context)
            val camera = store.cameras(widgetId).firstOrNull()
            val views = RemoteViews(context.packageName, R.layout.widget_camera_snapshot)
            if (camera == null) {
                views.setTextViewText(R.id.widget_title, context.getString(R.string.widget_choose_camera))
                views.setViewVisibility(R.id.widget_time, View.GONE)
                HomeWidgets.showPicture(manager, widgetId, views, null)
                views.setOnClickPendingIntent(R.id.widget_root, WidgetIntents.configureIntent(context, widgetId))
            } else {
                views.setTextViewText(R.id.widget_title, HomeWidgets.prettyCamera(camera))
                val image = store.snapshot(camera)
                HomeWidgets.showPicture(manager, widgetId, views, image)
                val takenAt = store.snapshotTime(camera)
                views.setViewVisibility(R.id.widget_time, if (takenAt > 0) View.VISIBLE else View.GONE)
                if (takenAt > 0) views.setTextViewText(R.id.widget_time, HomeWidgets.formatTime(context, takenAt))
                views.setOnClickPendingIntent(R.id.widget_root, WidgetIntents.cameraIntent(context, camera))
            }
            manager.updateAppWidget(widgetId, views)
        }

        /** Redraw every image widget, for example after the worker cached new frames. */
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            HomeWidgets.widgetIds(context, CameraSnapshotWidget::class.java).forEach { render(context, manager, it) }
        }

        /** The cameras that at least one image widget shows. */
        fun cameras(context: Context): Set<String> {
            val store = WidgetStore(context)
            return HomeWidgets.widgetIds(context, CameraSnapshotWidget::class.java)
                .asList()
                .mapNotNull { store.cameras(it).firstOrNull() }
                .toSet()
        }

        fun showsCamera(context: Context, camera: String): Boolean = camera in cameras(context)
    }
}
