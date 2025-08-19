package org.joaquim.s3watch.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.joaquim.s3watch.services.BleForegroundService

/**
 * Starts the BLE foreground service after device boot so the app
 * can maintain the BLE connection and forward notifications.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i("BootCompletedReceiver", "Boot completed; starting BLE service")
            BleForegroundService.start(context)
        }
    }
}

