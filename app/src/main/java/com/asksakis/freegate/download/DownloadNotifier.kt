package com.asksakis.freegate.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import com.asksakis.freegate.R
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Progress and completion notifications for downloads the app performs itself.
 *
 * Only the in-process path needs this. When the system DownloadManager handles a
 * download it posts its own notification, and posting a second one beside it would show
 * the user the same transfer twice.
 *
 * The channel is deliberately low importance. A download the user just asked for does
 * not need to make a sound or push itself in front of what they are doing; it needs to
 * be somewhere they can watch it and tap it when it is done.
 */
class DownloadNotifier(context: Context) {

    private val appContext = context.applicationContext
    private val manager = NotificationManagerCompat.from(appContext)

    /** Allocate a notification id per download so concurrent transfers do not collide. */
    fun newId(): Int = nextId.getAndIncrement()

    fun started(id: Int, fileName: String) {
        ensureChannel()
        notify(
            id,
            base(fileName)
                .setContentText(appContext.getString(R.string.download_notification_running))
                .setProgress(0, 0, true)
                .setOngoing(true),
        )
    }

    /**
     * Update the bar. [total] is the Content-Length, or a value below zero when the
     * server did not send one, in which case the bar stays indeterminate rather than
     * inventing a percentage.
     */
    fun progress(id: Int, fileName: String, downloaded: Long, total: Long) {
        val builder = base(fileName).setOngoing(true)
        if (total > 0) {
            val percent = ((downloaded * PERCENT) / total).toInt().coerceIn(0, PERCENT)
            builder.setContentText("$percent%").setProgress(PERCENT, percent, false)
        } else {
            builder
                .setContentText(appContext.getString(R.string.download_notification_running))
                .setProgress(0, 0, true)
        }
        notify(id, builder)
    }

    /** Replace the progress bar with a finished notification that opens the file. */
    fun completed(id: Int, fileName: String, file: File) {
        notify(
            id,
            base(fileName)
                .setContentText(appContext.getString(R.string.download_notification_complete))
                .setAutoCancel(true)
                .setContentIntent(openIntent(id, file)),
        )
    }

    fun failed(id: Int, fileName: String, error: String) {
        notify(
            id,
            base(fileName)
                .setContentText(
                    appContext.getString(R.string.download_notification_failed, error)
                )
                .setAutoCancel(true),
        )
    }

    private fun base(fileName: String) =
        NotificationCompat.Builder(appContext, CHANNEL_DOWNLOADS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(fileName)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

    /**
     * Post the notification, tolerating a missing POST_NOTIFICATIONS grant. The download
     * itself is unaffected by the user having refused notifications, so a refusal must
     * not take the transfer down with it.
     */
    private fun notify(id: Int, builder: NotificationCompat.Builder) {
        runCatching { manager.notify(id, builder.build()) }
    }

    private fun openIntent(id: Int, file: File): PendingIntent? = runCatching {
        val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, DownloadHandler.mimeTypeFor(file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        PendingIntent.getActivity(
            appContext,
            id,
            view,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }.getOrNull()

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DOWNLOADS,
                appContext.getString(R.string.download_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = appContext.getString(R.string.download_notification_channel_desc)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    companion object {
        const val CHANNEL_DOWNLOADS = "frigate_downloads"

        private const val PERCENT = 100

        /**
         * Review notifications derive their id from a hash, which can land anywhere in the
         * Int range, so no range is formally free. Counting up from a fixed high base keeps
         * downloads clear of each other and makes an overlap with a review unlikely.
         */
        private val nextId = AtomicInteger(900_000)
    }
}
