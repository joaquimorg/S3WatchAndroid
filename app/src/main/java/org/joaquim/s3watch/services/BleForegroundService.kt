package org.joaquim.s3watch.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.joaquim.s3watch.R
import org.joaquim.s3watch.models.DeviceData
import org.joaquim.s3watch.bluetooth.BluetoothCentralManager

/**
 * Foreground service that keeps the BLE link alive in background and
 * attempts reconnection for up to 5 minutes when it's lost.
 */
class BleForegroundService : Service() {

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "s3watch_ble"
        private const val NOTIFICATION_CHANNEL_NAME = "S3Watch BLE"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, BleForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, BleForegroundService::class.java)
            context.stopService(intent)
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private var reconnectJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastBattery: Int? = null
    private var currentStatusText: String = "Maintaining BLE connection"

    private val connectionObserver = Observer<BluetoothCentralManager.ConnectionStatus> { state ->
        when (state) {
            BluetoothCentralManager.ConnectionStatus.CONNECTED -> cancelReconnectJob()
            BluetoothCentralManager.ConnectionStatus.DISCONNECTED,
            BluetoothCentralManager.ConnectionStatus.ERROR -> {
                if (BluetoothCentralManager.shouldAutoReconnect() || BluetoothCentralManager.hasPendingToSend()) {
                    scheduleReconnectWindow()
                } else {
                    // No reconnect if remote indicated intentional disconnect
                    cancelReconnectJob()
                    updateStatusNotification("Idle (remote off)")
                }
            }
            BluetoothCentralManager.ConnectionStatus.CONNECTING -> { /* no-op */ }
        }
    }

    private val dataObserver = Observer<String> { json ->
        try {
            val data = DeviceData.fromJson(json)
            lastBattery = data.battery
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification(currentStatusText))
        } catch (_: Exception) { }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(currentStatusText))

        // Observe BLE connection changes and react
        BluetoothCentralManager.connectionState.observeForever(connectionObserver)
        BluetoothCentralManager.dataReceived.observeForever(dataObserver)

        // Kick a reconnect attempt on service start
        scheduleReconnectWindow()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Nothing else to do; sticky so it restarts if killed
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        BluetoothCentralManager.connectionState.removeObserver(connectionObserver)
        BluetoothCentralManager.dataReceived.removeObserver(dataObserver)
        cancelReconnectJob()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun scheduleReconnectWindow() {
        if (reconnectJob?.isActive == true) return
        // Hold a partial wakelock during the reconnect window so attempts continue when the phone is locked
        if (wakeLock?.isHeld != true) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "S3Watch:Reconnect").apply {
                setReferenceCounted(false)
                try { acquire(5 * 60 * 1000L + 10 * 1000L) } catch (_: Throwable) {}
            }
        }
        reconnectJob = scope.launch {
            val start = System.currentTimeMillis()
            val timeoutMs = 5 * 60 * 1000L // 5 minutes
            val intervalMs = 15 * 1000L // try every 15 seconds

            while (isActive && System.currentTimeMillis() - start < timeoutMs) {
                if (!BluetoothCentralManager.shouldAutoReconnect() && !BluetoothCentralManager.hasPendingToSend()) {
                    // Stop trying if remote requested no auto-reconnect and nothing to send
                    updateStatusNotification("Idle (remote off)")
                    break
                }
                BluetoothCentralManager.reconnect()
                // Update ongoing notification text so users know what's happening
                val elapsed = (System.currentTimeMillis() - start) / 1000
                updateStatusNotification("Reconnecting… ${elapsed}s")
                delay(intervalMs)
            }
        }
    }

    private fun cancelReconnectJob() {
        reconnectJob?.cancel()
        reconnectJob = null
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Throwable) {}
        // Update notification to connected state
        updateStatusNotification("Connected")
    }

    private fun updateStatusNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        currentStatusText = text
        nm.notify(NOTIFICATION_ID, buildNotification(currentStatusText))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Keeps the watch connection alive"
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val withBattery = lastBattery?.let { "$text • 🔋 ${it}%" } ?: text
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_s3watch)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(withBattery)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
