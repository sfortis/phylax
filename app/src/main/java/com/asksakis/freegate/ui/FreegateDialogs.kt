package com.asksakis.freegate.ui

import android.content.Context
import com.asksakis.freegate.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Single entry point for in-app dialogs so every popup (stats, connection status,
 * confirmations, update prompts) shares the dark-grey surface theme without each
 * site having to remember the theme overlay id.
 */
object FreegateDialogs {
    fun builder(context: Context): MaterialAlertDialogBuilder =
        MaterialAlertDialogBuilder(context, R.style.ThemeOverlay_Freegate_Dialog)
}
