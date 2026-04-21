package com.asksakis.freegate.ui.settings

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import com.asksakis.freegate.R

class DownloadsSettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.prefs_downloads, rootKey)
    }
}
