package com.asksakis.freegate.ui.settings

import android.os.Bundle
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.asksakis.freegate.R

/**
 * Top-level Settings screen. Each category preference navigates to its own child
 * fragment via the nav graph — back/up behave naturally, slide animation, ActionBar
 * title flips automatically (destination label).
 */
class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.prefs_root, rootKey)
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        val actionId = when (preference.key) {
            "cat_connection" -> R.id.action_settings_to_connection
            "cat_notifications" -> R.id.action_settings_to_notifications
            "cat_downloads" -> R.id.action_settings_to_downloads
            "cat_advanced" -> R.id.action_settings_to_advanced
            else -> return super.onPreferenceTreeClick(preference)
        }
        findNavController().navigate(actionId)
        return true
    }
}
