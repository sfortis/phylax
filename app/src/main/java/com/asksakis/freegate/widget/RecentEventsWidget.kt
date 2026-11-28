package com.asksakis.freegate.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import com.asksakis.freegate.MainActivity
import com.asksakis.freegate.R

/**
 * Home screen widget with a list of the latest notified events. A tap on a row opens that
 * review. Like the last event widget, the alert pipeline fills it ([HomeWidgets.onAlert])
 * with the pictures the notifications already downloaded. While the list is empty, the
 * latest reviews are fetched from Frigate once ([RecentEventsBackfillWorker]).
 */
class RecentEventsWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { render(context, manager, it) }
        if (RecentEventsStore(context).events().isEmpty()) RecentEventsBackfillWorker.enqueue(context)
    }

    override fun onDisabled(context: Context) {
        RecentEventsBackfillWorker.cancel(context)
        RecentEventsStore(context).clear()
    }

    companion object {

        fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_recent_events)
            // A distinct data URI per widget, so the system keeps one list adapter for each.
            val adapter = Intent(context, RecentEventsService::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                .setData(Uri.parse("phylax-widget://recent/$widgetId"))
            @Suppress("DEPRECATION") // The replacement, RemoteCollectionItems, needs API 31.
            views.setRemoteAdapter(R.id.widget_list, adapter)
            views.setEmptyView(R.id.widget_list, R.id.widget_empty)
            views.setPendingIntentTemplate(R.id.widget_list, rowTemplate(context))
            views.setOnClickPendingIntent(R.id.widget_header, WidgetIntents.homeIntent(context))
            manager.updateAppWidget(widgetId, views)
            manager.notifyAppWidgetViewDataChanged(widgetId, R.id.widget_list)
        }

        fun updateAll(context: Context) {
            val ids = HomeWidgets.widgetIds(context, RecentEventsWidget::class.java)
            if (ids.isNotEmpty()) {
                AppWidgetManager.getInstance(context).notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
            }
        }

        /**
         * The launch every row shares. Each row adds its review URI as fill-in data, which
         * needs a mutable intent. The activity is fixed, so a fill-in can change only the link.
         */
        private fun rowTemplate(context: Context): PendingIntent {
            val intent = Intent(Intent.ACTION_VIEW).setClass(context, MainActivity::class.java)
            return PendingIntent.getActivity(
                context,
                "widget:recent".hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
        }

        /** The fill-in a row adds to [rowTemplate]: the same link as a notification tap. */
        internal fun rowFillIn(reviewId: String): Intent =
            Intent().setData(Uri.parse("frigate://review/${Uri.encode(reviewId)}"))
    }
}
