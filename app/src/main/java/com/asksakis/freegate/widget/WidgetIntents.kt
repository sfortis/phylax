package com.asksakis.freegate.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri

/** The intents the home screen widgets open. */
object WidgetIntents {

    fun cameraIntent(context: Context, camera: String): PendingIntent =
        viewIntent(context, Uri.parse("frigate://camera?id=${Uri.encode(camera)}"), "camera:$camera")

    fun reviewIntent(context: Context, reviewId: String): PendingIntent =
        viewIntent(context, Uri.parse("frigate://review/${Uri.encode(reviewId)}"), "review")

    fun homeIntent(context: Context): PendingIntent =
        viewIntent(context, Uri.parse("frigate://home"), "home")

    /** Opens the configuration screen again for an existing widget. */
    fun configureIntent(context: Context, widgetId: Int): PendingIntent {
        val intent = Intent(context, WidgetConfigActivity::class.java)
            .setAction(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        return PendingIntent.getActivity(
            context,
            "configure:$widgetId".hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun viewIntent(context: Context, uri: Uri, key: String): PendingIntent {
        // Same launch as a notification tap (FrigateNotifier): a view intent the app's own
        // deep-link handler routes.
        val intent = Intent(Intent.ACTION_VIEW, uri).setPackage(context.packageName)
        return PendingIntent.getActivity(
            context,
            "widget:$key".hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
