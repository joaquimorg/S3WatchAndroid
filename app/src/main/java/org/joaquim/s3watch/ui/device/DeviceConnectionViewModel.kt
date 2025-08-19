package org.joaquim.s3watch.ui.device

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.application
import org.joaquim.s3watch.R
import org.joaquim.s3watch.utils.SingleLiveEvent
import org.joaquim.s3watch.bluetooth.BluetoothCentralManager
import org.json.JSONObject
import java.time.LocalDateTime
import java.util.*

class DeviceConnectionViewModel(application: Application) : AndroidViewModel(application) {

    companion object {        private val TAG = DeviceConnectionViewModel::class.java.simpleName
        const val PREFS_NAME = "S3WatchPrefs"
        const val KEY_CONNECTED_DEVICE_ADDRESS = "connected_device_address"

        const val KEY_CONNECTED_DEVICE_NAME = "connected_device_name"

        private const val NORDIC_UART_SERVICE_UUID_STRING = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
        // NUS characteristic where the app **receives** data from the ESP32 (ESP32 TX)
        private const val NORDIC_UART_RX_CHARACTERISTIC_UUID_STRING = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"
        // NUS characteristic where the app **sends** data to the ESP32 (ESP32 RX)
        private const val NORDIC_UART_TX_CHARACTERISTIC_UUID_STRING = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"

        val NORDIC_UART_SERVICE_UUID: UUID = UUID.fromString(NORDIC_UART_SERVICE_UUID_STRING)
        val NORDIC_UART_RX_CHARACTERISTIC_UUID: UUID = UUID.fromString(NORDIC_UART_RX_CHARACTERISTIC_UUID_STRING)
        val NORDIC_UART_TX_CHARACTERISTIC_UUID: UUID = UUID.fromString(NORDIC_UART_TX_CHARACTERISTIC_UUID_STRING)

        val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val SCAN_PERIOD: Long = 5000 // Stops scanning after 5 seconds.

    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy(LazyThreadSafetyMode.NONE) {
        val bluetoothManager = application.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    private val _discoveredDevices = MutableLiveData<MutableList<BluetoothDevice>>(mutableListOf())
    val discoveredDevices: LiveData<MutableList<BluetoothDevice>> = _discoveredDevices

    private val _isScanning = MutableLiveData(false)
    val isScanning: LiveData<Boolean> = _isScanning

    private val _connectionStatus = MutableLiveData<String>()
    val connectionStatus: LiveData<String> = _connectionStatus

    private val _navigateToHome = SingleLiveEvent<Unit>()
    val navigateToHome: LiveData<Unit> = _navigateToHome

    // GATT state now lives in BluetoothCentralManager; keep only scan state here

    private val handler = Handler(Looper.getMainLooper())

    private val sharedPreferences = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        // Bridge central manager state into this VM for UI consumption
        BluetoothCentralManager.connectionState.observeForever { state ->
            when (state) {
                BluetoothCentralManager.ConnectionStatus.CONNECTED -> {
                    _connectionStatus.postValue(getApplication<Application>().getString(R.string.status_connected))
                    _navigateToHome.postValue(Unit)
                }
                BluetoothCentralManager.ConnectionStatus.CONNECTING -> {
                    _connectionStatus.postValue(getApplication<Application>().getString(R.string.status_connecting))
                }
                BluetoothCentralManager.ConnectionStatus.DISCONNECTED -> {
                    _connectionStatus.postValue(getApplication<Application>().getString(R.string.status_disconnected))
                }
                BluetoothCentralManager.ConnectionStatus.ERROR -> {
                    _connectionStatus.postValue(getApplication<Application>().getString(R.string.status_connection_error, "", ""))
                }
            }
        }
    }


    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
            _connectionStatus.postValue(getApplication<Application>().getString(R.string.status_scan_permission_error))
            Log.w(TAG, "BLUETOOTH_SCAN permission not granted for startScan")
            _isScanning.postValue(false)
            return
        }

        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            _connectionStatus.postValue(getApplication<Application>().getString(R.string.status_ble_scanner_not_available))
            Log.w(TAG, "BluetoothAdapter not available or not enabled.")
            _isScanning.postValue(false)
            return
        }

        val bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
        if (bluetoothLeScanner == null) {
            _connectionStatus.postValue(getApplication<Application>().getString(R.string.status_ble_scanner_not_available))
            Log.w(TAG, "BluetoothLeScanner not available.")
            _isScanning.postValue(false)
            return
        }

        _discoveredDevices.value?.clear()
        _discoveredDevices.postValue(_discoveredDevices.value ?: mutableListOf())
        _isScanning.postValue(true)
        _connectionStatus.postValue(getApplication<Application>().getString(R.string.status_scanning_devices))

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        // Show all nearby BLE devices (no service filter); some peripherals don't advertise NUS in scan data
        val filters: List<ScanFilter>? = null
        Log.d(TAG, "Starting BLE scan (no filter) ...")
        handler.postDelayed({
            if (_isScanning.value == true) {
                stopScan()
            }
        }, SCAN_PERIOD)

        try {
            bluetoothLeScanner.startScan(filters, scanSettings, leScanCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException during startScan: ${e.message}")
            _connectionStatus.postValue(getApplication<Application>().getString(R.string.status_scan_permission_error))
            _isScanning.postValue(false)
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
            _connectionStatus.postValue(getApplication<Application>().getString(R.string.status_stop_scan_permission_error))
            Log.w(TAG, "BLUETOOTH_SCAN permission not granted for stopScan")
            return
        }
        val bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
        if (bluetoothLeScanner == null || _isScanning.value == false) {
            Log.w(TAG, "stopScan called but scanner not available or not scanning.")
            _isScanning.postValue(false) // Ensure UI reflects not scanning
            return
        }
        Log.d(TAG, "Stopping BLE scan.")
        try {
            bluetoothLeScanner.stopScan(leScanCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException during stopScan: ${e.message}")
            _connectionStatus.postValue(getApplication<Application>().getString(R.string.status_stop_scan_permission_error))
        }
        _isScanning.postValue(false)
    }

    private val leScanCallback: ScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                Log.w(TAG, "BLUETOOTH_CONNECT permission missing in onScanResult, cannot get device name.")
            }
            val device = result.device ?: return
            val currentList = _discoveredDevices.value ?: mutableListOf()
            // Add device even if name is null; show address or Unknown in UI
            if (!currentList.any { it.address == device.address }) {
                currentList.add(device)
                _discoveredDevices.postValue(currentList)
                Log.i(TAG, "Device found: ${device.name ?: "(no name)"} (${device.address})")
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            super.onBatchScanResults(results)
            val currentList = _discoveredDevices.value ?: mutableListOf()
            var changed = false
            results.forEach { res ->
                val dev = res.device ?: return@forEach
                if (!currentList.any { it.address == dev.address }) {
                    currentList.add(dev)
                    changed = true
                }
            }
            if (changed) {
                _discoveredDevices.postValue(currentList)
                Log.d(TAG, "Batch results merged. Total: ${currentList.size}")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e(TAG, "BLE Scan Failed: $errorCode")
            _connectionStatus.postValue(getApplication<Application>().getString(R.string.status_scan_failed, errorCode.toString()))
            _isScanning.postValue(false)
        }
    }

    @SuppressLint("MissingPermission")
    fun connectGatt(device: BluetoothDevice) {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            _connectionStatus.postValue(getApplication<Application>().getString(R.string.status_ble_connect_permission_error))
            Log.w(TAG, "BLUETOOTH_CONNECT permission not granted for connectGatt")
            return
        }
        stopScan()
        val nameOrAddress = device.name ?: device.address
        _connectionStatus.postValue(getApplication<Application>().getString(R.string.status_connecting_to, nameOrAddress))
        Log.i(TAG, "Delegating connection to central manager: $nameOrAddress")
        // Delegate connection to the central manager (single source of truth)
        BluetoothCentralManager.connect(device.address, device.name)
    }

    /* Legacy per-ViewModel GATT stack kept for reference; replaced by BluetoothCentralManager
    private val gattCallback: BluetoothGattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val deviceName = if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) gatt.device.name ?: gatt.device.address else gatt.device.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Connected to GATT server on $deviceName.")
                _connectionStatus.postValue(getApplication<Application>().getString(R.string.status_connected_to, deviceName))

                sharedPreferences.edit()
                    .putString(KEY_CONNECTED_DEVICE_ADDRESS, gatt.device.address)
                    .putString(KEY_CONNECTED_DEVICE_NAME, deviceName) // Save name as well
                    .apply()

                Log.i(TAG, "Device address ${gatt.device.address} and name $deviceName saved to SharedPreferences.")

                Log.d(TAG, "Attempting to discover services...")
                if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                    Log.w(TAG, "BLUETOOTH_CONNECT permission missing for discoverServices.")
                    _connectionStatus.postValue(getApplication<Application>().getString(R.string.status_ble_connect_permission_error))
                    closeGatt()
                    return
                }
                val discoverServicesSuccess = gatt.discoverServices()
                if (!discoverServicesSuccess) {
                    Log.e(TAG, "discoverServices() failed to start.")
                    _connectionStatus.postValue(getApplication<Application>().getString(R.string.status_service_discovery_failed, deviceName))
                    closeGatt()
                } else {
                    Log.d(TAG, "Service discovery initiated for $deviceName.")
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "Disconnected from GATT server on $deviceName.")
                _connectionStatus.postValue(getApplication<Application>().getString(R.string.status_disconnected_from, deviceName))
                closeGatt() // Ensure GATT is fully cleaned up on disconnection
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val deviceName = if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) gatt.device.name ?: gatt.device.address else gatt.device.address
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Services discovered for $deviceName. Looking for NUS.")
                val nusService = gatt.getService(NORDIC_UART_SERVICE_UUID)
                if (nusService == null) {
                    Log.w(TAG, "Nordic UART Service not found on $deviceName.")
                    _connectionStatus.postValue(getApplication<Application>().getString(R.string.status_nus_service_not_found, deviceName))
                    closeGatt() 
                    return
                }

                nusTxCharacteristic = nusService.getCharacteristic(NORDIC_UART_TX_CHARACTERISTIC_UUID)
                nusRxCharacteristic = nusService.getCharacteristic(NORDIC_UART_RX_CHARACTERISTIC_UUID)

                if (nusTxCharacteristic == null) {
                    Log.w(TAG, "NUS TX characteristic not found on $deviceName.")
                    // Do not post status here, let onDescriptorWrite handle missing TX if RX succeeds
                } else {
                    Log.i(TAG, "NUS TX Characteristic found for $deviceName.")
                }

                if (nusRxCharacteristic == null) {
                    Log.w(TAG, "NUS RX characteristic not found on $deviceName.")
                    _connectionStatus.postValue(getApplication<Application>().getString(R.string.status_nus_rx_char_not_found, deviceName))
                    // If RX is essential and not found, but TX was, decide if to closeGatt or not.
                    // For now, if RX is missing, we can't proceed with enabling notifications, which is critical for the flow.
                    closeGatt()
                } else {
                    Log.i(TAG, "NUS RX Characteristic found for $deviceName. Attempting to enable notifications.")
                    enableNotifications(nusRxCharacteristic!!)
                }
                // DO NOT NAVIGATE HERE. Navigation will occur in onDescriptorWrite after successful notification setup and data send.
            } else {
                Log.w(TAG, "onServicesDiscovered received error status: $status for $deviceName")
                _connectionStatus.postValue(getApplication<Application>().getString(R.string.status_service_discovery_failed_status, deviceName, status.toString()))
                closeGatt()
            }
        }
        
        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val deviceName = if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) gatt.device.name ?: gatt.device.address else gatt.device.address
            Log.d(TAG, "onDescriptorWrite for ${descriptor.uuid} (characteristic ${descriptor.characteristic.uuid}) on $deviceName status: $status")

            if (descriptor.characteristic.uuid == NORDIC_UART_RX_CHARACTERISTIC_UUID && descriptor.uuid == CLIENT_CHARACTERISTIC_CONFIG_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "Successfully enabled notifications for RX characteristic on $deviceName.")
                    if (nusTxCharacteristic != null) {
                        Log.d(TAG, "Notifications enabled, attempting to send current date/time.")
                        sendCurrentDateTime() // This uses WRITE_TYPE_NO_RESPONSE
                        // Since sendCurrentDateTime is fire-and-forget, navigate immediately after queuing the send.

                        Log.d(TAG, "Navigating to home screen after attempting to send date/time.")
                        _navigateToHome.postValue(Unit)
                    } else {
                        Log.w(TAG, "Notifications enabled, but NUS TX characteristic is null. Cannot send initial data. Navigating anyway.")
                         _connectionStatus.postValue(getApplication<Application>().getString(R.string.status_nus_tx_char_not_found_on_send))
                        // Decide if navigation is appropriate if TX is missing for the initial send.
                        // For now, let's navigate as per original implicit behavior.
                        // If TX is critical for home screen, then do not navigate and closeGatt().
                         _navigateToHome.postValue(Unit) // Or closeGatt() if TX is mandatory for home screen.
                    }
                } else {
                    Log.e(TAG, "Failed to enable notifications for RX characteristic on $deviceName, status: $status")
                    _connectionStatus.postValue(getApplication<Application>().getString(R.string.status_nus_rx_enable_failed_status, deviceName, status.toString()))
                    closeGatt() // Critical failure, close connection and don't navigate.
                }
            } else {
                Log.d(TAG, "onDescriptorWrite for a different descriptor: ${descriptor.uuid} on char ${descriptor.characteristic.uuid}")
            }
        }


        @SuppressLint("MissingPermission")
        private fun enableNotifications(characteristic: BluetoothGattCharacteristic) {
            val gatt = bluetoothGatt ?: run {
                Log.e(TAG, "bluetoothGatt is null in enableNotifications, cannot proceed.")
                _connectionStatus.postValue(getApplication<Application>().getString(R.string.status_error_gatt_null)) 
                return
            }

            val deviceNameToLog = if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                gatt.device.name ?: gatt.device.address
            } else {
                gatt.device.address
            }

            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                Log.w(TAG, "BLUETOOTH_CONNECT permission missing for enableNotifications on $deviceNameToLog.")
                _connectionStatus.postValue(getApplication<Application>().getString(R.string.status_ble_connect_permission_error))
                return
            }

            if (characteristic.uuid != NORDIC_UART_RX_CHARACTERISTIC_UUID) {
                Log.w(TAG, "enableNotifications called for an unexpected characteristic: ${characteristic.uuid} on $deviceNameToLog. Expected ${NORDIC_UART_RX_CHARACTERISTIC_UUID}.")
                return
            }

            val cccd = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
            if (cccd == null) {
                Log.w(TAG, "CLIENT_CHARACTERISTIC_CONFIG_UUID descriptor not found for RX characteristic ${characteristic.uuid} on $deviceNameToLog.")
                _connectionStatus.postValue(getApplication<Application>().getString(R.string.status_nus_rx_cccd_not_found, deviceNameToLog)) 
                closeGatt() // If CCCD is missing, we can't enable notifications.
                return
            }

            Log.d(TAG, "Attempting to enable notifications for RX characteristic: ${characteristic.uuid} on $deviceNameToLog.")
            if (!gatt.setCharacteristicNotification(characteristic, true)) {
                Log.e(TAG, "setCharacteristicNotification failed for ${characteristic.uuid} on $deviceNameToLog.")
                _connectionStatus.postValue(getApplication<Application>().getString(R.string.status_nus_set_notify_failed, deviceNameToLog)) 
                closeGatt()
                return
            }

            var initiationSuccess = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val writeResult = gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                Log.d(TAG, "writeDescriptor (Tiramisu+) for CCCD on $deviceNameToLog result: $writeResult (0 is success)")
                if (writeResult == BluetoothStatusCodes.SUCCESS) { // BluetoothStatusCodes.SUCCESS is 0
                    initiationSuccess = true
                } else {
                    Log.e(TAG, "Failed to initiate write CCCD descriptor (Tiramisu+) for $deviceNameToLog: error code $writeResult")
                }
            } else {
                @Suppress("DEPRECATION")
                if (!cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    Log.e(TAG, "Failed to set value for CCCD descriptor (pre-Tiramisu) for $deviceNameToLog.")
                }
                @Suppress("DEPRECATION")
                if (gatt.writeDescriptor(cccd)) {
                    initiationSuccess = true
                    Log.d(TAG, "writeDescriptor (pre-Tiramisu) for CCCD on $deviceNameToLog initiated successfully.")
                } else {
                    Log.e(TAG, "Failed to initiate write CCCD descriptor (pre-Tiramisu) for $deviceNameToLog.")
                }
            }

            if (!initiationSuccess) {
                _connectionStatus.postValue(getApplication<Application>().getString(R.string.status_nus_rx_cccd_write_fail, deviceNameToLog)) 
                gatt.setCharacteristicNotification(characteristic, false) // Clean up
                Log.w(TAG, "Cleaned up by calling setCharacteristicNotification(false) for ${characteristic.uuid} on $deviceNameToLog due to CCCD write initiation failure.")
                closeGatt()
            }
        }


        @SuppressLint("MissingPermission")
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val deviceName = if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) gatt.device.name ?: gatt.device.address else gatt.device.address
            Log.d(TAG, "onCharacteristicWrite for ${characteristic.uuid} on $deviceName status: $status")
            // This callback is not reliably triggered for WRITE_TYPE_NO_RESPONSE.
            // If it were WRITE_TYPE_DEFAULT, navigation logic for successful send would be here.
            if (characteristic.uuid == NORDIC_UART_TX_CHARACTERISTIC_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "JSON data acknowledged by peripheral (if using WRITE_TYPE_DEFAULT) to $deviceName.")
                } else {
                    Log.e(TAG, "Failed to send JSON data to $deviceName, status: $status (if using WRITE_TYPE_DEFAULT)")
                    _connectionStatus.postValue(getApplication<Application>().getString(R.string.status_json_send_failed, deviceName, status.toString()))
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
             val deviceName = if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) gatt.device.name ?: gatt.device.address else gatt.device.address
            val data = String(value, Charsets.UTF_8)
            Log.i(TAG, "onCharacteristicChanged (API 33+) from $deviceName ${characteristic.uuid}: $data")
            // TODO: Handle received data from RX characteristic
        }

        @Suppress("DEPRECATION")
        @SuppressLint("MissingPermission")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val deviceName = if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) gatt.device.name ?: gatt.device.address else gatt.device.address
                val value = characteristic.value
                if (value != null) {
                    val data = String(value, Charsets.UTF_8)
                    Log.i(TAG, "onCharacteristicChanged (pre-API 33) from $deviceName ${characteristic.uuid}: $data")
                    // TODO: Handle received data from RX characteristic
                } else {
                    Log.w(TAG, "onCharacteristicChanged (pre-API 33) from $deviceName ${characteristic.uuid} with null value")
                }
            }
        }
    }


    @SuppressLint("MissingPermission")
    private fun sendCurrentDateTime() {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            Log.w(TAG, "BLUETOOTH_CONNECT permission missing for sendCurrentDateTime.")
            _connectionStatus.postValue(getApplication<Application>().getString(R.string.status_ble_connect_permission_error))
            return
        }
        if (bluetoothGatt == null) {
            Log.e(TAG, "bluetoothGatt is null in sendCurrentDateTime")
            _connectionStatus.postValue(getApplication<Application>().getString(R.string.status_gatt_null_on_send))
            return
        }
        if (nusTxCharacteristic == null) {
            Log.e(TAG, "nusTxCharacteristic is null in sendCurrentDateTime")
            _connectionStatus.postValue(getApplication<Application>().getString(R.string.status_nus_tx_char_not_found_on_send))
            return
        }

        val now = LocalDateTime.now()
        val year = now.year
        val month = now.monthValue
        val day = now.dayOfMonth
        val hour = now.hour
        val minute = now.minute
        val second = now.second
        val formattedDateTime = String.format(Locale.ROOT, "%04d-%02d-%02dT%02d:%02d:%02d", year, month, day, hour, minute, second)

        val jsonPayload = JSONObject()
        jsonPayload.put("datetime", formattedDateTime)
        val jsonString = jsonPayload.toString()
        val payloadBytes = jsonString.toByteArray(Charsets.UTF_8)

        Log.i(TAG, "Sending JSON to NUS TX: $jsonString")

        nusTxCharacteristic?.let { characteristic ->
            var writeInitiated = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val result = bluetoothGatt!!.writeCharacteristic(characteristic, payloadBytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                Log.d(TAG, "writeCharacteristic (Tiramisu+) for NUS TX (NO_RESPONSE) result: $result (0 is SUCCESS)")
                if (result == BluetoothStatusCodes.SUCCESS) {
                    writeInitiated = true
                } else {
                    Log.e(TAG, "Failed to initiate write characteristic (Tiramisu+, NO_RESPONSE): error $result")
                    _connectionStatus.postValue(getApplication<Application>().getString(R.string.status_json_send_failed_code, result.toString()))
                }
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = payloadBytes
                @Suppress("DEPRECATION")
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                if (bluetoothGatt!!.writeCharacteristic(characteristic)) {
                    writeInitiated = true
                } else {
                    Log.e(TAG, "Failed to initiate write characteristic (pre-Tiramisu, NO_RESPONSE)")
                    _connectionStatus.postValue(getApplication<Application>().getString(R.string.status_json_send_failed))
                }
            }
            if (writeInitiated) {
                 Log.d(TAG, "Successfully initiated writeCharacteristic (NO_RESPONSE) for NUS TX.")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun closeGatt() {
        // Delegate disconnection to the central manager
        BluetoothCentralManager.disconnect()
    }
    */

    private fun hasPermission(permission: String): Boolean {
        return ActivityCompat.checkSelfPermission(
            getApplication<Application>().applicationContext,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onCleared() {
        super.onCleared()
        //Log.d(TAG, "onCleared called, closing GATT.")
        //closeGatt()
    }
}
