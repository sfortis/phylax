package com.asksakis.freegate

import android.app.Application
import com.asksakis.freegate.auth.ServerProfileStore
import com.asksakis.freegate.utils.PersistentLogcatWriter

/**
 * Application singleton. We deliberately do NOT enable DynamicColors — the app uses
 * its own fixed Material 3 purple/teal palette so the branding stays consistent
 * regardless of the device's wallpaper.
 */
class FrigateViewerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Promote pre-multi-server flat-state installs to the per-profile store. No-op
        // once at least one profile exists, so it's safe on every launch.
        ServerProfileStore.getInstance(this).ensureMigrated()
        // Keep a rotating day of logs on disk so "Share debug logs" in Settings has the
        // history that led up to a fault, not just what the buffer holds afterwards.
        // Android does not always let an app read its own logcat; the share button says so
        // when nothing was captured.
        PersistentLogcatWriter.start(this)
    }
}
