package com.asksakis.freegate.ui.settings

import android.os.Bundle
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.asksakis.freegate.R

/**
 * Power-user knobs only: voice-call audio routing and custom WebView User Agent.
 * Anything release/identity-related (version, "what's new", check for updates)
 * lives under Settings → About so the per-screen mental model stays clean.
 */
class AdvancedSettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.prefs_advanced, rootKey)

        val customUa = findPreference<EditTextPreference>("custom_user_agent")
        customUa?.summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()

        findPreference<SwitchPreferenceCompat>("use_custom_user_agent")
            ?.setOnPreferenceChangeListener { _, newValue ->
                val msg = if (newValue as Boolean) {
                    "Custom User Agent enabled. The page will reload with new settings."
                } else {
                    "Using default User Agent"
                }
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                true
            }

        customUa?.setOnPreferenceChangeListener { _, _ ->
            Toast.makeText(
                requireContext(),
                "User Agent updated. The page will reload with new settings.",
                Toast.LENGTH_SHORT,
            ).show()
            true
        }
    }
}
