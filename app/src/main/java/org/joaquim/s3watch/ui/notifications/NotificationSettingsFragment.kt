package org.joaquim.s3watch.ui.notifications

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.provider.Settings
import androidx.appcompat.widget.SearchView
import androidx.preference.CheckBoxPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import androidx.core.app.NotificationManagerCompat

/**
 * Fragment showing notification settings.
 * Allows the user to choose which applications will have their
 * notifications forwarded to the connected watch.
 */
class NotificationSettingsFragment : PreferenceFragmentCompat() {
    private data class AppItem(
        val pkg: String,
        val label: String,
        val icon: Drawable,
        val isSystem: Boolean,
    )
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var appsCategory: PreferenceCategory
    private lateinit var permissionCategory: PreferenceCategory
    private lateinit var notifAccessSwitch: SwitchPreferenceCompat
    private var allApps: List<AppItem> = emptyList()
    private var currentFilter: String = ""
    private var showSystemApps: Boolean = false

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        // Create an empty screen and add the multi-select list dynamically
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())

        prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val pm = requireContext().packageManager

        // Save search state and create UI
        currentFilter = prefs.getString(KEY_NOTIFICATION_APP_SEARCH, "")?.trim().orEmpty()
        showSystemApps = prefs.getBoolean(KEY_SHOW_SYSTEM_APPS, false)

        // Permissions category with notification access switch
        permissionCategory = PreferenceCategory(requireContext()).apply {
            title = getString(org.joaquim.s3watch.R.string.permissions_title)
        }
        preferenceScreen.addPreference(permissionCategory)

        notifAccessSwitch = SwitchPreferenceCompat(requireContext()).apply {
            key = KEY_NOTIFICATION_ACCESS
            title = getString(org.joaquim.s3watch.R.string.notification_access_title)
            summary = getString(org.joaquim.s3watch.R.string.notification_access_summary)
            isChecked = isNotificationAccessGranted()
            setOnPreferenceChangeListener { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                } catch (_: Exception) {
                }
                false
            }
        }
        permissionCategory.addPreference(notifAccessSwitch)

        // Inline search bar preference
        val searchPref = object : Preference(requireContext()) {
            override fun onBindViewHolder(holder: PreferenceViewHolder) {
                super.onBindViewHolder(holder)
                val sv = holder.findViewById(org.joaquim.s3watch.R.id.search_view) as? SearchView
                sv?.apply {
                    queryHint = context.getString(org.joaquim.s3watch.R.string.search_apps)
                    setIconifiedByDefault(false)
                    isIconified = false
                    setQuery(currentFilter, false)
                    clearFocus()
                    setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                        override fun onQueryTextSubmit(query: String?): Boolean {
                            val newFilter = query?.trim().orEmpty()
                            if (newFilter != currentFilter) {
                                currentFilter = newFilter
                                prefs.edit().putString(KEY_NOTIFICATION_APP_SEARCH, currentFilter).apply()
                                rebuildAppChecklist(currentFilter)
                            }
                            return true
                        }

                        override fun onQueryTextChange(newText: String?): Boolean {
                            val newFilter = newText?.trim().orEmpty()
                            if (newFilter != currentFilter) {
                                currentFilter = newFilter
                                prefs.edit().putString(KEY_NOTIFICATION_APP_SEARCH, currentFilter).apply()
                                rebuildAppChecklist(currentFilter)
                            }
                            return true
                        }
                    })
                }
            }
        }.apply {
            key = KEY_NOTIFICATION_APP_SEARCH
            layoutResource = org.joaquim.s3watch.R.layout.preference_inline_search
            isSelectable = false
        }
        // System apps toggle
        val systemAppsToggle = SwitchPreferenceCompat(requireContext()).apply {
            key = KEY_SHOW_SYSTEM_APPS
            title = getString(org.joaquim.s3watch.R.string.show_system_apps)
            //summary = getString(org.joaquim.s3watch.R.string.show_system_apps_summary)
            isChecked = showSystemApps
            setOnPreferenceChangeListener { _, newValue ->
                showSystemApps = (newValue as? Boolean) == true
                prefs.edit().putBoolean(KEY_SHOW_SYSTEM_APPS, showSystemApps).apply()
                rebuildAppChecklist(currentFilter)
                true
            }
        }

        preferenceScreen.addPreference(searchPref)

        preferenceScreen.addPreference(systemAppsToggle)

        appsCategory = PreferenceCategory(requireContext()).apply {
            title = getString(org.joaquim.s3watch.R.string.select_notification_apps)
        }
        preferenceScreen.addPreference(appsCategory)

        // Show loading indicator while resolving apps
        val loadingPref = Preference(requireContext()).apply {
            layoutResource = org.joaquim.s3watch.R.layout.preference_loading
            isSelectable = false
        }
        appsCategory.addPreference(loadingPref)

        // Load app list off the main thread
        Thread {
            val apps = resolveAppsList(pm)
            requireActivity().runOnUiThread {
                allApps = apps
                rebuildAppChecklist(currentFilter)
            }
        }.start()
    }

    companion object {
        const val KEY_NOTIFICATION_APPS = "notification_apps"
        const val KEY_NOTIFICATION_APP_SEARCH = "notification_app_search"
        const val KEY_SHOW_SYSTEM_APPS = "notification_show_system_apps"
        const val KEY_NOTIFICATION_ACCESS = "notification_access"
        private const val TAG = "NotificationSettings"
    }

    private fun resolveAppsList(pm: PackageManager): List<AppItem> {
        // Prefer querying launchable apps (ACTION_MAIN + CATEGORY_LAUNCHER)
        val launcherIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val launchables = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(
                launcherIntent,
                PackageManager.ResolveInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(launcherIntent, 0)
        }

        val combined = LinkedHashMap<String, AppItem>()

        launchables.forEach { ri ->
            val pkg = ri.activityInfo.packageName
            val appInfo = ri.activityInfo.applicationInfo
            val isSystem = isSystemApp(appInfo)
            val label = ri.loadLabel(pm)?.toString() ?: pkg
            val icon = try { pm.getApplicationIcon(pkg) } catch (_: Exception) { ri.loadIcon(pm) }
            combined[pkg] = AppItem(pkg, label, icon, isSystem)
        }

        // Also include installed packages (requires QUERY_ALL_PACKAGES which is declared)
        val installed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(0)
        }

        installed.forEach { pi ->
            val pkg = pi.packageName
            val appInfo = pi.applicationInfo ?: return@forEach
            if (!appInfo.enabled) return@forEach
            val label = appInfo.loadLabel(pm)?.toString() ?: pkg
            val icon = try { pm.getApplicationIcon(pkg) } catch (_: Exception) { appInfo.loadIcon(pm) }
            val isSystem = isSystemApp(appInfo)
            val existing = combined[pkg]
            if (existing == null || (existing.label.startsWith("com.") && label.isNotBlank())) {
                combined[pkg] = AppItem(pkg, label, icon, isSystem)
            }
        }

        val apps = combined.values
            .filter { it.label.isNotBlank() }
            .sortedBy { it.label.lowercase() }

        Log.d(TAG, "Resolved launchable activities: ${launchables.size}")
        Log.d(TAG, "Resolved app entries: ${apps.size}")
        return apps
    }

    private fun isSystemApp(appInfo: ApplicationInfo): Boolean {
        val flags = appInfo.flags
        return ((flags and ApplicationInfo.FLAG_SYSTEM) != 0) ||
                ((flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)
    }

    private fun rebuildAppChecklist(filter: String) {
        // Clear category and rebuild according to filter
        appsCategory.removeAll()

        val selected = prefs.getStringSet(KEY_NOTIFICATION_APPS, emptySet())?.toMutableSet() ?: mutableSetOf()

        val nameFiltered = if (filter.isBlank()) allApps else allApps.filter {
            it.label.contains(filter, ignoreCase = true) || it.pkg.contains(filter, ignoreCase = true)
        }

        val filtered = if (showSystemApps) nameFiltered else nameFiltered.filter { !it.isSystem }

        if (filtered.isEmpty()) {
            val info = Preference(requireContext()).apply {
                title = "No matching apps"
                summary = "Try a different search term."
                isEnabled = false
            }
            appsCategory.addPreference(info)
            return
        }

        filtered.forEach { item ->
            val pkg = item.pkg
            val label = item.label
            val icon = item.icon
            val checkbox = CheckBoxPreference(requireContext()).apply {
                key = "notification_app_$pkg"
                title = label
                summary = pkg
                this.icon = icon
                isChecked = selected.contains(pkg)
                onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                    val checked = newValue as Boolean
                    val current = prefs.getStringSet(KEY_NOTIFICATION_APPS, emptySet())?.toMutableSet() ?: mutableSetOf()
                    if (checked) current.add(pkg) else current.remove(pkg)
                    prefs.edit().putStringSet(KEY_NOTIFICATION_APPS, current).apply()
                    true
                }
            }
            appsCategory.addPreference(checkbox)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Ensure the last item isn't hidden behind the BottomNavigationView
        val rv = listView
        rv.clipToPadding = false
        view.post {
            val navView = requireActivity().findViewById<BottomNavigationView>(org.joaquim.s3watch.R.id.nav_view)
            val fallback = resources.getDimensionPixelSize(org.joaquim.s3watch.R.dimen.bottom_nav_fallback_height)
            val extra = resources.getDimensionPixelSize(org.joaquim.s3watch.R.dimen.preference_list_bottom_padding_extra)
            val bottomPad = (navView?.height ?: fallback) + extra
            rv.setPadding(rv.paddingLeft, rv.paddingTop, rv.paddingRight, bottomPad)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::notifAccessSwitch.isInitialized) {
            notifAccessSwitch.isChecked = isNotificationAccessGranted()
        }
    }

    private fun isNotificationAccessGranted(): Boolean {
        return NotificationManagerCompat.getEnabledListenerPackages(requireContext()).contains(requireContext().packageName)
    }
}
