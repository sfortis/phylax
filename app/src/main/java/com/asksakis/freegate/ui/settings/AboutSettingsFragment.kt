package com.asksakis.freegate.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.asksakis.freegate.BuildConfig
import com.asksakis.freegate.R
import com.asksakis.freegate.utils.UpdateChecker
import kotlinx.coroutines.launch

/**
 * Settings → About. Combines release/identity info (version + "what's new" +
 * check-for-updates) with the external link rows (source, issues, third-party,
 * sponsor). External links open with a chooser intent so the user picks their
 * browser per tap — no silent default-handler grant.
 */
class AboutSettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.prefs_about, rootKey)

        findPreference<AboutHeroPreference>("about_hero")?.setVersionLabel(
            "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        )

        // The in-app updater is compiled out of the fdroid flavor (F-Droid
        // updates flow through its own repo), so hide the row entirely instead
        // of showing a tap-target that doesn't do anything.
        val checkUpdates = findPreference<Preference>("check_updates")
        if (!BuildConfig.ENABLE_UPDATE_CHECK) {
            checkUpdates?.isVisible = false
        } else {
            checkUpdates?.setOnPreferenceClickListener {
                checkForUpdatesManually()
                true
            }
        }

        bindLink("about_source", URL_SOURCE)
        bindLink("about_issues", URL_ISSUES)
        bindLink("about_changelog", URL_CHANGELOG)
        bindLink("about_third_party", URL_THIRD_PARTY)
        bindLink("about_sponsor", URL_SPONSOR)
    }

    /**
     * Wire a single preference key to "open this URL externally" via a chooser
     * — `ACTION_VIEW` + `Intent.createChooser` so the user picks their browser
     * once per tap instead of silently inheriting the system default handler.
     */
    private fun bindLink(prefKey: String, url: String) {
        findPreference<Preference>(prefKey)?.setOnPreferenceClickListener {
            runCatching {
                val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                startActivity(Intent.createChooser(viewIntent, "Open with"))
            }
            true
        }
    }

    /**
     * Trigger the in-app update checker manually. Mirrors what
     * AdvancedSettingsFragment used to do before this row moved here.
     */
    private fun checkForUpdatesManually() {
        val updateChecker = UpdateChecker(requireContext())

        val progressBar = ProgressBar(context).apply {
            isIndeterminate = true
            setPadding(0, 16, 0, 0)
        }
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 40)
            addView(progressBar)
        }
        val progressDialog = com.asksakis.freegate.ui.FreegateDialogs.builder(requireContext())
            .setTitle("Checking for updates...")
            .setView(layout)
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch {
            val updateInfo = updateChecker.checkForUpdates(force = true)
            progressDialog.dismiss()

            when {
                updateInfo != null -> updateChecker.showUpdateDialog(
                    requireActivity() as AppCompatActivity,
                    updateInfo,
                )
                updateChecker.lastErrorMessage != null -> Toast.makeText(
                    context,
                    "Could not check for updates: ${updateChecker.lastErrorMessage}",
                    Toast.LENGTH_LONG,
                ).show()
                else -> Toast.makeText(
                    context,
                    "You're on the latest version",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private companion object {
        const val URL_SOURCE = "https://github.com/sfortis/phylax"
        const val URL_ISSUES = "https://github.com/sfortis/phylax/issues"
        const val URL_CHANGELOG = "https://github.com/sfortis/phylax/releases"
        const val URL_THIRD_PARTY = "https://github.com/sfortis/phylax/blob/main/THIRD_PARTY_NOTICES.md"
        const val URL_SPONSOR = "https://www.buymeacoffee.com/sfortis"
    }
}
