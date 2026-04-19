package com.asksakis.freegate

import android.app.Application

/**
 * Application singleton. We deliberately do NOT enable DynamicColors — the app uses
 * its own fixed Material 3 purple/teal palette so the branding stays consistent
 * regardless of the device's wallpaper.
 */
class FrigateViewerApp : Application()
