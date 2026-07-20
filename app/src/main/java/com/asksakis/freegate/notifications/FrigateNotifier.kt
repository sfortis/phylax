package com.asksakis.freegate.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
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

        // Both alerts and detections now come from the reviews topic, so `alert.id`
        // is always a review id — route everyone to /review. (Pre-reviews-only
        // detections used `/event` because the id was an event id; that path is
        // dead now and would 404 in Frigate's UI.)
        val intent = when (tapAction) {
            TapAction.REVIEW -> Intent(Intent.ACTION_VIEW, Uri.parse("frigate://review/${alert.id}"))
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
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
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

    /**
     * Post a bare motion notification for [camera]. Motion frames carry no event/review
     * id, labels or zones, so this is intentionally minimal: a headline plus the time,
     * an optional snapshot, and a tap that opens the app home (there is no review to deep
     * link to). Throttling / per-camera opt-in are enforced upstream by the service.
     */
    fun notifyMotion(camera: String, timeSec: Long, snapshot: android.graphics.Bitmap? = null) {
        val title = "Motion detected on ${prettifyCameraName(camera)}"
        val body = "Motion • ${formatClockTime(timeSec.toDouble())}"

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("frigate://home"))
            .setPackage(context.packageName)
        val notifId = "motion:$camera".hashCode()
        val pending = PendingIntent.getActivity(
            context,
            notifId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_MOTION)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pending)

        if (snapshot != null) {
            builder.setLargeIcon(snapshot)
            val bigPicture = NotificationCompat.BigPictureStyle()
                .bigPicture(snapshot)
                .bigLargeIcon(null as android.graphics.Bitmap?)
                .setSummaryText(body)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                bigPicture.showBigPictureWhenCollapsed(true)
            }
            builder.setStyle(bigPicture)
        }

        val notifyPermission = android.Manifest.permission.POST_NOTIFICATIONS
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, notifyPermission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context).notify(notifId, builder.build())
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

        // No channel sound for alerts. Even with bypassDnd + USAGE_ALARM,
        // Samsung One UI silences notification-originated audio in vibrate
        // ringer mode (audio policy override below the SDK surface). [AlarmSoundPlayer]
        // owns the alert sound and routes it through STREAM_ALARM directly,
        // matching the pattern Pushover / ntfy / hospital alert SDKs use to
        // wake users reliably at night. Vibration still rides the channel.
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                "Frigate alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "High-priority reviews flagged by Frigate as alerts"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 150, 250)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(null, null)
                // Still keep bypass on: covers stock-Android devices where the
                // channel audio path isn't broken, and lets the heads-up appear
                // through DND even when sound playback is handled separately.
                setBypassDnd(true)
            },
        )
        // Detections also drop channel-level sound and route through
        // [DetectionSoundPlayer] instead so the in-app ringtone picker can
        // honour "Silent / custom tone" without channel-immutability fighting
        // back (Android freezes channel sound at first creation; an in-app
        // pref would otherwise be a no-op for the channel audio path).
        // Vibration remains on the channel so it still rides Android's DND
        // and notification-volume rules.
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DETECTIONS,
                "Frigate detections",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Tracked-object detections"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 120)
                setSound(null, null)
            },
        )
        // Old detection channel shipped with a channel-level bundled chime;
        // leaving it around in System Settings → Apps → Phylax → Notifications
        // would confuse users (two rows) and the leftover channel would still
        // ring on detections from any caller that hadn't been migrated. Drop
        // it now that DetectionSoundPlayer fully owns the audio path.
        runCatching { mgr.deleteNotificationChannel(LEGACY_CHANNEL_DETECTIONS) }
        // Same migration story for alerts: the original `frigate_alerts`
        // channel was created on first launch with a default notification
        // ringtone, and Android freezes channel sound at first creation. The
        // `setSound(null, null)` above is therefore a no-op for upgraders —
        // the systemui NotificationPlayer keeps ringing the old channel tone
        // independently of AlarmSoundPlayer, so picking "Silent" in-app still
        // produced sound until the channel itself was retired.
        runCatching { mgr.deleteNotificationChannel(LEGACY_CHANNEL_ALERTS) }
        // Dedicated channel for per-camera motion notifications, kept separate from
        // detections so the user can independently tune / silence the (potentially
        // noisier) motion class in system settings. DEFAULT importance; vibration on,
        // channel sound left null to match the other channels' audio-policy handling.
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MOTION,
                "Frigate motion",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Per-camera motion detected by Frigate (no object required)"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 120)
                setSound(null, null)
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
        // Channel sound/importance/bypassDnd are immutable after first creation; if
        // we change defaults later we'll need to bump the id (Android soft-restores
        // settings on delete-and-recreate of the same id for ~30 days).
        // Kept package-public so the Settings UI can deep-link straight to each
        // channel via Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS.
        // v2: bumped when the channel-level ringtone was dropped in favour of
        // [AlarmSoundPlayer] owning the audio path. The original
        // `frigate_alerts` channel was created with a default notification
        // sound on first launch and Android freezes channel audio at first
        // creation, so we need a fresh id to get `sound = null`. Without this
        // bump, picking "Silent" in-app still produced the channel ringtone
        // through the systemui NotificationPlayer. ensureChannels() deletes
        // the legacy id.
        const val CHANNEL_ALERTS = "frigate_alerts_v2"
        private const val LEGACY_CHANNEL_ALERTS = "frigate_alerts"
        // v2: same migration story for detections — bumped when the
        // channel-level chime was dropped in favour of [DetectionSoundPlayer].
        const val CHANNEL_DETECTIONS = "frigate_detections_v2"
        private const val LEGACY_CHANNEL_DETECTIONS = "frigate_detections"
        const val CHANNEL_MOTION = "frigate_motion"
        private const val CHANNEL_STATUS = "frigate_status"
        private const val REQUEST_STATUS = 1_000
        private const val REQUEST_STATUS_DELETE = 1_001
    }
}
