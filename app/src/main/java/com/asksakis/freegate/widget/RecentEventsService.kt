package com.asksakis.freegate.widget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.asksakis.freegate.R

/** Supplies the rows of [RecentEventsWidget]. Only the system can bind it. */
class RecentEventsService : RemoteViewsService() {

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Rows(applicationContext)

    private class Rows(private val context: Context) : RemoteViewsFactory {

        private var events: List<WidgetStore.LastEvent> = emptyList()

        override fun onCreate() = Unit

        override fun onDataSetChanged() {
            events = RecentEventsStore(context).events()
        }

        override fun onDestroy() {
            events = emptyList()
        }

        override fun getCount(): Int = events.size

        override fun getViewAt(position: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_recent_event_row)
            val event = events.getOrNull(position) ?: return views
            views.setTextViewText(R.id.widget_row_title, event.title)
            views.setTextViewText(R.id.widget_row_subtitle, event.subtitle)
            views.setTextViewText(R.id.widget_row_time, HomeWidgets.formatTime(context, event.timeMs))
            views.setInt(
                R.id.widget_row_severity,
                "setBackgroundResource",
                if (event.isAlert) R.drawable.widget_pill_alert else R.drawable.widget_pill_detection,
            )
            val image = RecentEventsStore(context).image(event.reviewId)
            if (image != null) {
                views.setImageViewBitmap(
                    R.id.widget_row_image,
                    WidgetBitmaps.cropRounded(image, THUMBNAIL_RATIO, THUMBNAIL_RADIUS_PER_WIDTH),
                )
            } else {
                views.setImageViewResource(R.id.widget_row_image, R.drawable.widget_thumbnail_placeholder)
            }
            views.setOnClickFillInIntent(R.id.widget_row, RecentEventsWidget.rowFillIn(event.reviewId))
            return views
        }

        override fun getLoadingView(): RemoteViews? = null

        override fun getViewTypeCount(): Int = 1

        override fun getItemId(position: Int): Long =
            events.getOrNull(position)?.reviewId?.hashCode()?.toLong() ?: position.toLong()

        override fun hasStableIds(): Boolean = true
    }

    private companion object {
        /** The row picture is 96 by 54 dp: a 16:9 frame. */
        const val THUMBNAIL_RATIO = 16f / 9f

        /** 10 dp corners on a 96 dp wide picture, like widget_thumbnail_placeholder. */
        const val THUMBNAIL_RADIUS_PER_WIDTH = 10f / 96f
    }
}
