package com.asksakis.freegate.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.asksakis.freegate.R

/**
 * Owns the Android notification channels for Frigate alerts and turns an [AlertFilter.Alert]
 * into a user-visible notification that deep-links back into the app.
 */
class FrigateNotifier(private val context: Context) {

    init {
        ensureChannels()
    }

    /** Channel id for the persistent foreground service notification. */
    fun statusChannelId(): String = CHANNEL_STATUS

    /** Build the persistent "listening for alerts" notification shown while the service runs. */
    fun buildStatusNotification(text: String): Notification {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("frigate://home"))
            .setPackage(context.packageName)
        val pending = PendingIntent.getActivity(
            context,
            REQUEST_STATUS,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Android 14+ makes FGS notifications user-dismissable. When dismissed, fire a
        // broadcast so the service can repost itself.
        val deleteIntent = Intent(
            context,
            StatusNotificationReviveReceiver::class.java,
        ).setAction(StatusNotificationReviveReceiver.ACTION_REVIVE)
        val deletePending = PendingIntent.getBroadcast(
            context,
            REQUEST_STATUS_DELETE,
            deleteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setContentTitle("Phylax")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_menu_home)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pending)
            .setDeleteIntent(deletePending)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** Post a notification for [alert]. Silently ignored if POST_NOTIFICATIONS is denied. */
    fun notify(alert: AlertFilter.Alert, tapAction: TapAction, snapshot: android.graphics.Bitmap? = null) {
        val channelId = when (alert.severity) {
            AlertFilter.Severity.ALERT -> CHANNEL_ALERTS
            AlertFilter.Severity.DETECTION -> CHANNEL_DETECTIONS
        }
        val title = buildTitle(alert)
        val subtitle = buildSubtitle(alert)
        // Collapsed row shows the first line only; the expanded view (BigText / BigPicture
        // summary) shows the full body.
        val collapsedLine = subtitle.substringBefore('\n')

        // For alerts we land on the review timeline; for detections the id is an event-id,
        // which only resolves under Frigate's Explore view, so pick the target scheme based
        // on severity instead of blindly using /review.
        val intent = when (tapAction) {
            TapAction.REVIEW -> {
                val targetUri = when (alert.severity) {
                    AlertFilter.Severity.ALERT -> "frigate://review/${alert.id}"
                    AlertFilter.Severity.DETECTION -> "frigate://event/${alert.id}"
                }
                Intent(Intent.ACTION_VIEW, Uri.parse(targetUri))
            }
            TapAction.HOME -> Intent(Intent.ACTION_VIEW, Uri.parse("frigate://home"))
        }.setPackage(context.packageName)

        val pending = PendingIntent.getActivity(
            context,
            alert.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(collapsedLine)
            .setSmallIcon(R.drawable.ic_menu_camera)
            .setPriority(
                if (alert.severity == AlertFilter.Severity.ALERT) NotificationCompat.PRIORITY_HIGH
                else NotificationCompat.PRIORITY_DEFAULT,
            )
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pending)

        if (snapshot != null) {
            builder.setLargeIcon(snapshot)
            val bigPicture = NotificationCompat.BigPictureStyle()
                .bigPicture(snapshot)
                .bigLargeIcon(null as android.graphics.Bitmap?)
                .setSummaryText(subtitle)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                bigPicture.showBigPictureWhenCollapsed(true)
            }
            builder.setStyle(bigPicture)
        } else if (subtitle.contains('\n')) {
            // No snapshot fetched — use BigTextStyle so the expanded view still shows
            // speed / attributes on the second line instead of truncating them away.
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(subtitle))
        }

        val notifyPermission = android.Manifest.permission.POST_NOTIFICATIONS
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, notifyPermission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context).notify(alert.id.hashCode(), builder.build())
        }
    }

    private fun buildTitle(alert: AlertFilter.Alert): String {
        val cameraText = prettifyCameraName(alert.camera)
        val subject = describeSubject(alert)
        // List every zone Frigate reports, not just the first. For an object that walked
        // through "Sidewalk" into "Driveway" we want both names in the title so the user
        // can see the path without opening the review.
        val zoneNames = alert.zones
            .map(::prettifyZoneName)
            .filter { it.isNotEmpty() }
        val location = if (zoneNames.isEmpty()) "" else " at ${zoneNames.joinToString(", ")}"
        return "$subject detected$location on $cameraText"
    }

    /**
     * Two-line subtitle: severity + time on top, optional speed + extra attributes below.
     * The second line is omitted entirely if neither applies so we don't spam blank rows
     * in the shade.
     */
    private fun buildSubtitle(alert: AlertFilter.Alert): String {
        val severityText = if (alert.severity == AlertFilter.Severity.ALERT) "Alert" else "Detection"
        val whenText = alert.startTimeSec?.let { formatClockTime(it) }
        val primary = if (whenText == null) severityText else "$severityText • $whenText"

        val extras = buildList {
            alert.estimatedSpeedKph?.let { add("~${it.toInt()} km/h") }
            val tags = alert.attributes
                .map(::prettifyAttribute)
                .filter { it.isNotEmpty() }
            if (tags.isNotEmpty()) add(tags.joinToString(" · "))
        }
        return if (extras.isEmpty()) primary else "$primary\n${extras.joinToString(" • ")}"
    }

    /** Best natural-language subject for the headline, prioritising richer signals over raw labels. */
    private fun describeSubject(alert: AlertFilter.Alert): String {
        alert.plate?.let { return "${formatObjects(alert.labels).takeIf { it != "Activity" } ?: "Car"} $it" }
        alert.subLabel?.let { return it.replaceFirstChar { c -> c.uppercase() } }
        val attr = alert.attributes.firstOrNull { it.isNotEmpty() }
        if (attr != null && alert.labels.isNotEmpty()) {
            return "${formatObjects(alert.labels)} with ${prettifyAttribute(attr)}"
        }
        return formatObjects(alert.labels)
    }

    private fun prettifyZoneName(raw: String): String =
        raw.replace('_', ' ').split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }

    private fun prettifyAttribute(raw: String): String = raw.replace('_', ' ')

    /** Format the alert's start time as a wall-clock string in the device locale. */
    private fun formatClockTime(epochSeconds: Double): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(epochSeconds.toLong() * 1000L))

    private fun formatObjects(labels: List<String>): String {
        val pretty = labels.map { it.replaceFirstChar { c -> c.uppercase() } }
        return when (pretty.size) {
            0 -> "Activity"
            1 -> pretty[0]
            2 -> "${pretty[0]} and ${pretty[1]}"
            else -> pretty.dropLast(1).joinToString(", ") + " and " + pretty.last()
        }
    }

    private fun prettifyCameraName(raw: String): String =
        raw.replace('_', ' ')
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }

    private fun ensureChannels() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                "Frigate alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "High-priority reviews flagged by Frigate as alerts"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 150, 250)
            },
        )
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DETECTIONS,
                "Frigate detections",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Tracked-object detections"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 120)
            },
        )
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STATUS,
                "Frigate listener",
                // LOW (not MIN): keeps the channel silent/no-badge but avoids the OS
                // collapsing it so aggressively that the user thinks the app is dead.
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Persistent background listener for Frigate alerts"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    enum class TapAction { REVIEW, HOME }

    companion object {
        private const val CHANNEL_ALERTS = "frigate_alerts"
        private const val CHANNEL_DETECTIONS = "frigate_detections"
        private const val CHANNEL_STATUS = "frigate_status"
        private const val REQUEST_STATUS = 1_000
        private const val REQUEST_STATUS_DELETE = 1_001
    }
}
