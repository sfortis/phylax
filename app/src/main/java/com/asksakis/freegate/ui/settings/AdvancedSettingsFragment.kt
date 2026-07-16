package com.asksakis.freegate.ui.settings

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import com.asksakis.freegate.R

/**
 * Power-user knobs only: voice-call audio routing. Anything release/identity-related
 * (version, "what's new", check for updates) lives under Settings -> About so the
 * per-screen mental model stays clean.
 */
class AdvancedSettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.prefs_advanced, rootKey)
    }
}
