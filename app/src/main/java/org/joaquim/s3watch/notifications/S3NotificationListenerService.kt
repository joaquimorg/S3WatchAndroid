package org.joaquim.s3watch.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.preference.PreferenceManager
import org.joaquim.s3watch.bluetooth.BluetoothCentralManager
import org.joaquim.s3watch.ui.notifications.NotificationSettingsFragment

/**
 * Listens for system notifications and forwards the ones coming from
 * user-selected applications to the connected watch.
 * Also forwards call and SMS notifications unconditionally.
 */
class S3NotificationListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val selected = prefs.getStringSet(NotificationSettingsFragment.KEY_NOTIFICATION_APPS, emptySet())
            ?: emptySet()

        val pkg = sbn.packageName

        val title = sbn.notification.extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text = sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        val category = sbn.notification.category

        val isCall = category == Notification.CATEGORY_CALL
        val isMessage = category == Notification.CATEGORY_MESSAGE

        if (selected.contains(pkg) || isCall || isMessage) {
            BluetoothCentralManager.sendNotification(pkg, title, text)
        }
    }
}

