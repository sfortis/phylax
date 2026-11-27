package com.asksakis.freegate.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.view.View
import android.widget.RemoteViews
import com.asksakis.freegate.R

/**
 * Home screen widget with the picture, title and time of the last alert. A tap opens that
 * review. The alert pipeline updates it ([HomeWidgets.onAlert]) with the image the
 * notification already downloaded, so it costs no network of its own.
 */
class LastEventWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { render(context, manager, it) }
    }

    override fun onDisabled(context: Context) {
        WidgetStore(context).clearLastEvent()
    }

    companion object {

        fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val store = WidgetStore(context)
            val event = store.lastEvent()
            val views = RemoteViews(context.packageName, R.layout.widget_last_event)
            if (event == null) {
                views.setTextViewText(R.id.widget_title, context.getString(R.string.widget_no_events))
                views.setViewVisibility(R.id.widget_time, View.GONE)
                views.setViewVisibility(R.id.widget_badge, View.GONE)
                views.setViewVisibility(R.id.widget_subtitle, View.GONE)
                HomeWidgets.showPicture(manager, widgetId, views, null)
                views.setOnClickPendingIntent(R.id.widget_root, WidgetIntents.homeIntent(context))
            } else {
                views.setTextViewText(R.id.widget_title, event.title)
                val subtitleVisibility = if (event.subtitle.isEmpty()) View.GONE else View.VISIBLE
                views.setViewVisibility(R.id.widget_subtitle, subtitleVisibility)
                views.setTextViewText(R.id.widget_subtitle, event.subtitle)
                views.setViewVisibility(R.id.widget_badge, View.VISIBLE)
                val badge = if (event.isAlert) R.string.widget_badge_alert else R.string.widget_badge_detection
                views.setTextViewText(R.id.widget_badge, context.getString(badge))
                views.setInt(
                    R.id.widget_badge,
                    "setBackgroundResource",
                    if (event.isAlert) R.drawable.widget_pill_alert else R.drawable.widget_pill_detection,
                )
                views.setViewVisibility(R.id.widget_time, View.VISIBLE)
                views.setTextViewText(R.id.widget_time, HomeWidgets.formatTime(context, event.timeMs))
                val image = store.lastEventImage()
                HomeWidgets.showPicture(manager, widgetId, views, image)
                views.setOnClickPendingIntent(R.id.widget_root, WidgetIntents.reviewIntent(context, event.reviewId))
            }
            manager.updateAppWidget(widgetId, views)
        }

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            HomeWidgets.widgetIds(context, LastEventWidget::class.java).forEach { render(context, manager, it) }
        }
    }
}
