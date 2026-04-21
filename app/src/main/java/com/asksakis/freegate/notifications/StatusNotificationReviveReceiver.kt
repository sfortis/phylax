package com.asksakis.freegate.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Broadcast target for the status notification's `deleteIntent`.
 *
 * On Android 14+ foreground-service notifications are user-dismissable by OS design, even
 * with `setOngoing(true)`. When the user swipes ours away we re-call
 * [FrigateAlertService.updateForContext], which triggers a fresh `startForeground()` and
 * restores the persistent notification within ~1s. The service itself never stopped — only
 * its visible notification did.
 */
class StatusNotificationReviveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Log.d(TAG, "Status notification dismissed — reviving FGS")
        FrigateAlertService.updateForContext(context.applicationContext)
    }

    companion object {
        private const val TAG = "StatusNotifRevive"
        const val ACTION_REVIVE = "com.asksakis.freegate.action.REVIVE_STATUS_NOTIFICATION"
    }
}
