package org.joaquim.s3watch // Or your actual package name

import android.app.Application
import android.util.Log
import org.joaquim.s3watch.bluetooth.BluetoothCentralManager
import org.joaquim.s3watch.services.BleForegroundService

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize BluetoothCentralManager here
        BluetoothCentralManager.INSTANCE.initialize(this)
        Log.i("MyApplication", "BluetoothCentralManager initialized.") // Optional: for confirmation
        // Ensure the BLE foreground service is running to keep connection alive
        BleForegroundService.start(this)
    }
}
