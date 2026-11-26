package com.asksakis.freegate.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat

/**
 * Handles the action buttons carried by an alert, detection or motion notification.
 *
 * The one action so far silences the camera the notification came from, which until now cost
 * the user four taps inside the app: open it, open the mute sheet, find the camera, pick a
 * duration. From the shade it is a single tap, and the phone does not even have to be
 * unlocked.
 *
 * The work itself is one call into [CameraMuteStore]. This receiver and the listener service
 * share a process, so the store's cache is updated in place and the next frame is dropped by
 * the mute checks the service already runs before it notifies. Nothing in the delivery path
 * needs to know that the mute arrived from a notification rather than from the sheet.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_MUTE_CAMERA) return
        val camera = intent.getStringExtra(EXTRA_CAMERA)?.takeIf { it.isNotBlank() } ?: return
        val durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 0L)
        if (durationMs <= 0L) return

        CameraMuteStore.getInstance(context)
            .mute(CameraMuteStore.Kind.CAMERA, camera, durationMs)
        Log.d(TAG, "Muted $camera for ${durationMs / 60_000} min from a notification action")

        // The user has dealt with this event, so take the notification away rather than
        // leaving it in the shade beside a camera that is now silent.
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        if (notificationId != 0) {
            NotificationManagerCompat.from(context).cancel(notificationId)
        }
    }

    companion object {
        const val ACTION_MUTE_CAMERA = "com.asksakis.freegate.action.MUTE_CAMERA"
        const val EXTRA_CAMERA = "camera"
        const val EXTRA_DURATION_MS = "duration_ms"
        const val EXTRA_NOTIFICATION_ID = "notification_id"

        private const val TAG = "NotifAction"
    }
}
