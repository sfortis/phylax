package com.asksakis.freegate.ui.settings

import android.app.AlertDialog
import android.os.Build
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.asksakis.freegate.R
import com.asksakis.freegate.utils.UpdateChecker
import kotlinx.coroutines.launch

/**
 * User agent, app version, update checking.
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

        findPreference<Preference>("app_version")?.summary = resolveAppVersion()

        // The in-app updater is stripped from the fdroid flavor (F-Droid updates are
        // delivered through its own repository), so the Preference is hidden too.
        val checkUpdates = findPreference<Preference>("check_updates")
        if (!com.asksakis.freegate.BuildConfig.ENABLE_UPDATE_CHECK) {
            checkUpdates?.isVisible = false
        } else {
            checkUpdates?.setOnPreferenceClickListener {
                checkForUpdatesManually()
                true
            }
        }
    }

    private fun resolveAppVersion(): String = try {
        val pm = requireContext().packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(
                requireContext().packageName,
                android.content.pm.PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(requireContext().packageName, 0)
        }
        info.versionName ?: "Unknown"
    } catch (e: Exception) {
        "Unknown"
    }

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
                updateInfo != null ->
                    updateChecker.showUpdateDialog(requireActivity() as AppCompatActivity, updateInfo)
                updateChecker.lastErrorMessage != null -> Toast.makeText(
                    context,
                    "Could not check for updates: ${updateChecker.lastErrorMessage}",
                    Toast.LENGTH_LONG,
                ).show()
                else -> Toast.makeText(context, "You're on the latest version", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
