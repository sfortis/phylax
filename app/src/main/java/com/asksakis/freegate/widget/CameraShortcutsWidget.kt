package com.asksakis.freegate.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.view.View
import android.widget.RemoteViews
import com.asksakis.freegate.R

/**
 * Home screen widget with shortcuts to one to six cameras. A tap opens that camera's live
 * view. It needs no network: it only shows the names chosen in [WidgetConfigActivity].
 */
class CameraShortcutsWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { render(context, manager, it) }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val store = WidgetStore(context)
        appWidgetIds.forEach { store.removeWidget(it) }
    }

    companion object {
        /** Most cameras one widget holds, in a grid of at most three per row. */
        const val MAX_CAMERAS = 6
        private const val MAX_PER_ROW = 3

        fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val cameras = WidgetStore(context).cameras(widgetId)
            val views = RemoteViews(context.packageName, R.layout.widget_camera_shortcuts)
            views.removeAllViews(R.id.widget_rows)
            if (cameras.isEmpty()) {
                views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
                views.setOnClickPendingIntent(R.id.widget_root, WidgetIntents.configureIntent(context, widgetId))
            } else {
                views.setViewVisibility(R.id.widget_empty, View.GONE)
                // The launcher reuses the view, so clear the configure click of the empty state.
                views.setOnClickPendingIntent(R.id.widget_root, null)
                var index = 0
                rowSizes(cameras.size).forEach { size ->
                    val row = RemoteViews(context.packageName, R.layout.widget_shortcut_row)
                    cameras.subList(index, index + size).forEach { camera ->
                        row.addView(R.id.widget_row, item(context, camera))
                    }
                    views.addView(R.id.widget_rows, row)
                    index += size
                }
            }
            manager.updateAppWidget(widgetId, views)
        }

        private fun item(context: Context, camera: String): RemoteViews =
            RemoteViews(context.packageName, R.layout.widget_shortcut_item).apply {
                setTextViewText(R.id.widget_item_name, HomeWidgets.prettyCamera(camera))
                setOnClickPendingIntent(R.id.widget_item, WidgetIntents.cameraIntent(context, camera))
            }

        /**
         * How many cameras go in each row: one row for up to three cameras, two balanced
         * rows for four to six (4 is 2 and 2, 5 is 3 and 2, 6 is 3 and 3).
         */
        internal fun rowSizes(count: Int): List<Int> {
            val n = count.coerceIn(0, MAX_CAMERAS)
            if (n <= MAX_PER_ROW) return if (n == 0) emptyList() else listOf(n)
            val first = (n + 1) / 2
            return listOf(first, n - first)
        }
    }
}
