package com.asksakis.freegate.ui.settings

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import com.asksakis.freegate.R
import com.asksakis.freegate.widget.WidgetRefreshWorker

/**
 * Power-user knobs: voice-call audio routing, the Frigate tablet layout and how often the
 * home screen widgets refresh. Anything release/identity-related (version, "what's new",
 * check for updates) lives under Settings -> About so the per-screen mental model stays
 * clean.
 */
class AdvancedSettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.prefs_advanced, rootKey)
        findPreference<SeekBarPreference>(WidgetRefreshWorker.PREF_REFRESH_STEP)?.apply {
            summary = refreshSummary(value)
            setOnPreferenceChangeListener { _, newValue ->
                val step = newValue as Int
                summary = refreshSummary(step)
                // The preference is saved after this listener returns, so pass the value.
                WidgetRefreshWorker.updateSchedule(requireContext(), step = step)
                true
            }
        }
    }

    private fun refreshSummary(step: Int): String {
        val minutes = WidgetRefreshWorker.minutesForStep(step)
            ?: return getString(R.string.widget_refresh_alerts_only)
        val hours = minutes / MINUTES_PER_HOUR
        val rest = minutes % MINUTES_PER_HOUR
        return when {
            hours == 0 -> getString(R.string.widget_refresh_minutes, minutes)
            rest == 0 -> resources.getQuantityString(R.plurals.widget_refresh_hours, hours, hours)
            else -> getString(R.string.widget_refresh_hours_minutes, hours, rest)
        }
    }

    private companion object {
        const val MINUTES_PER_HOUR = 60
    }
}
