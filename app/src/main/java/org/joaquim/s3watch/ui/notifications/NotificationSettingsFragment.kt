package org.joaquim.s3watch.ui.notifications

import android.os.Bundle
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceFragmentCompat

/**
 * Fragment showing notification settings.
 * Allows the user to choose which applications will have their
 * notifications forwarded to the connected watch.
 */
class NotificationSettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        // Create an empty screen and add the multi-select list dynamically
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())

        val pm = requireContext().packageManager
        val apps = pm.getInstalledApplications(0).sortedBy { it.loadLabel(pm).toString() }

        val entries = apps.map { it.loadLabel(pm).toString() }.toTypedArray()
        val entryValues = apps.map { it.packageName }.toTypedArray()

        val appPref = MultiSelectListPreference(requireContext()).apply {
            key = KEY_NOTIFICATION_APPS
            title = getString(org.joaquim.s3watch.R.string.select_notification_apps)
            this.entries = entries
            this.entryValues = entryValues
        }

        preferenceScreen.addPreference(appPref)
    }

    companion object {
        const val KEY_NOTIFICATION_APPS = "notification_apps"
    }
}

