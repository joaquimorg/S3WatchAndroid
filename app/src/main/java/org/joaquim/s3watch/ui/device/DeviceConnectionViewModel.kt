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
import org.json.JSONObject
import java.time.LocalDateTime
import java.util.*

class DeviceConnectionViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private val TAG = DeviceConnectionViewModel::class.java.simpleName
        const val PREFS_NAME = "S3WatchPrefs"
        const val KEY_CONNECTED_DEVICE_ADDRESS = "connected_device_address"

        private const val NORDIC_UART_SERVICE_UUID_STRING = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
        private const val NORDIC_UART_RX_CHARACTERISTIC_UUID_STRING = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E" // App receives on this (ESP32 TX)
        private const val NORDIC_UART_TX_CHARACTERISTIC_UUID_STRING = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E" // App sends on this (ESP32 RX)

        val NORDIC_UART_SERVICE_UUID: UUID = UUID.fromString(NORDIC_UART_SERVICE_UUID_STRING)
        val NORDIC_UART_RX_CHARACTERISTIC_UUID: UUID = UUID.fromString(NORDIC_UART_RX_CHARACTERISTIC_UUID_STRING)
        val NORDIC_UART_TX_CHARACTERISTIC_UUID: UUID = UUID.fromString(NORDIC_UART_TX_CHARACTERISTIC_UUID_STRING)

        val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val SCAN_PERIOD: Long = 10000 // Stops scanning after 10 seconds.
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

    private var bluetoothGatt: BluetoothGatt? = null
    private var nusTxCharacteristic: BluetoothGattCharacteristic? = null
    private var nusRxCharacteristic: BluetoothGattCharacteristic? = null

    private val handler = Handler(Looper.getMainLooper())

    private val sharedPreferences = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)


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

        val filters: MutableList<ScanFilter> = ArrayList()
        val serviceUuid = ParcelUuid.fromString(NORDIC_UART_SERVICE_UUID_STRING)
        val nordicUartFilter = ScanFilter.Builder().setServiceUuid(serviceUuid).build()
        filters.add(nordicUartFilter)

        Log.d(TAG, "Starting BLE scan with Nordic UART filter...")
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
            val device = result.device
            val currentList = _discoveredDevices.value ?: mutableListOf()
            if (device != null && device.name != null && !currentList.any { it.address == device.address }) {
                currentList.add(device)
                _discoveredDevices.postValue(currentList)
                Log.i(TAG, "Device found: ${device.name} (${device.address})")
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
        stopScan() // Stop scanning before connecting
        _connectionStatus.postValue(getApplication<Application>().getString(R.string.status_connecting_to, device.name ?: device.address))
        Log.i(TAG, "Connecting to ${device.name ?: device.address}")
        try {
            bluetoothGatt = device.connectGatt(getApplication(), false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException during connectGatt: ${e.message}")
            _connectionStatus.postValue(getApplication<Application>().getString(R.string.status_ble_connect_permission_error))
        }
    }

    private val gattCallback: BluetoothGattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val deviceName = if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) gatt.device.name ?: gatt.device.address else gatt.device.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Connected to GATT server on $deviceName.")
                _connectionStatus.postValue(getApplication<Application>().getString(R.string.status_connected_to, deviceName))
                sharedPreferences.edit().putString(KEY_CONNECTED_DEVICE_ADDRESS, gatt.device.address).apply()
                Log.i(TAG, "Device address ${gatt.device.address} saved to SharedPreferences.")

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
                closeGatt()
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
                    // closeGatt() // Optional: disconnect if essential service is missing
                    return
                }

                nusTxCharacteristic = nusService.getCharacteristic(NORDIC_UART_TX_CHARACTERISTIC_UUID)
                nusRxCharacteristic = nusService.getCharacteristic(NORDIC_UART_RX_CHARACTERISTIC_UUID)

                if (nusTxCharacteristic == null) {
                    Log.w(TAG, "NUS TX characteristic not found on $deviceName.")
                    _connectionStatus.postValue(getApplication<Application>().getString(R.string.status_nus_tx_char_not_found, deviceName))
                } else {
                    Log.i(TAG, "NUS TX Characteristic found for $deviceName.")
                    // Removed: sendCurrentDateTime() // Attempt to send data
                }

                // Navigate regardless of TX found or send success, as per user's last request.
                // Or only navigate if TX is found: if (nusTxCharacteristic != null) _navigateToHome.postValue(Unit)
                // This navigation will now happen before sendCurrentDateTime is called from onDescriptorWrite.
                // This might be okay, or you might want to move navigation to after successful send in onCharacteristicWrite
                // or after successful notification setup in onDescriptorWrite if the Home screen relies on that.
                Log.d(TAG, "Navigating to home screen...")
                _navigateToHome.postValue(Unit)


                if (nusRxCharacteristic == null) {
                    Log.w(TAG, "NUS RX characteristic not found on $deviceName.")
                    _connectionStatus.postValue(getApplication<Application>().getString(R.string.status_nus_rx_char_not_found, deviceName))
                } else {
                    Log.i(TAG, "NUS RX Characteristic found for $deviceName. Attempting to enable notifications.")
                    enableNotifications(nusRxCharacteristic!!)
                }

            } else {
                Log.w(TAG, "onServicesDiscovered received error status: $status for $deviceName")
                _connectionStatus.postValue(getApplication<Application>().getString(R.string.status_service_discovery_failed_status, deviceName, status.toString()))
            }
        }

        @SuppressLint("MissingPermission")
        private fun enableNotifications(characteristic: BluetoothGattCharacteristic) {
            val gatt = bluetoothGatt ?: run {
                Log.e(TAG, "bluetoothGatt is null in enableNotifications, cannot proceed.")
                _connectionStatus.postValue(getApplication<Application>().getString(R.string.status_error_gatt_null)) // You'll need to add this string resource
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
                // Consider adding a specific status update if this scenario is critical
                return
            }

            val cccd = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
            if (cccd == null) {
                Log.w(TAG, "CLIENT_CHARACTERISTIC_CONFIG_UUID descriptor not found for RX characteristic ${characteristic.uuid} on $deviceNameToLog.")
                _connectionStatus.postValue(getApplication<Application>().getString(R.string.status_nus_rx_cccd_not_found, deviceNameToLog)) // Add this string
                return
            }

            Log.d(TAG, "Attempting to enable notifications for RX characteristic: ${characteristic.uuid} on $deviceNameToLog.")
            if (!gatt.setCharacteristicNotification(characteristic, true)) {
                Log.e(TAG, "setCharacteristicNotification failed for ${characteristic.uuid} on $deviceNameToLog.")
                _connectionStatus.postValue(getApplication<Application>().getString(R.string.status_nus_set_notify_failed, deviceNameToLog)) // Add this string
                return
            }

            var initiationSuccess = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val writeResult = gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                Log.d(TAG, "writeDescriptor (Tiramisu+) for CCCD on $deviceNameToLog result: $writeResult (0 is success)")
                if (writeResult == BluetoothStatusCodes.SUCCESS) {
                    initiationSuccess = true
                } else {
                    Log.e(TAG, "Failed to initiate write CCCD descriptor (Tiramisu+) for $deviceNameToLog: error code $writeResult")
                }
            } else {
                @Suppress("DEPRECATION")
                if (!cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    Log.e(TAG, "Failed to set value for CCCD descriptor (pre-Tiramisu) for $deviceNameToLog.")
                    // This failure will be caught by !initiationSuccess below if writeDescriptor also fails or isn't called.
                }
                if (gatt.writeDescriptor(cccd)) {
                    initiationSuccess = true
                    Log.d(TAG, "writeDescriptor (pre-Tiramisu) for CCCD on $deviceNameToLog initiated successfully.")
                } else {
                    Log.e(TAG, "Failed to initiate write CCCD descriptor (pre-Tiramisu) for $deviceNameToLog.")
                }
            }

            if (!initiationSuccess) {
                _connectionStatus.postValue(getApplication<Application>().getString(R.string.status_nus_rx_cccd_write_fail, deviceNameToLog)) // Add this string
                // Clean up: if CCCD write failed to initiate, disable local notification setting.
                gatt.setCharacteristicNotification(characteristic, false)
                Log.w(TAG, "Cleaned up by calling setCharacteristicNotification(false) for ${characteristic.uuid} on $deviceNameToLog due to CCCD write initiation failure.")
            }
            // Note: The actual success/failure of enabling notification is confirmed asynchronously in onDescriptorWrite callback.
        }


        @SuppressLint("MissingPermission")
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val deviceName = if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) gatt.device.name ?: gatt.device.address else gatt.device.address
            Log.d(TAG, "onCharacteristicWrite for ${characteristic.uuid} on $deviceName status: $status")
            if (characteristic.uuid == NORDIC_UART_TX_CHARACTERISTIC_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "JSON data sent successfully to $deviceName.")
                    // Potentially navigate to home screen here if you want to wait for successful send
                    // _navigateToHome.postValue(Unit)
                } else {
                    Log.e(TAG, "Failed to send JSON data to $deviceName, status: $status")
                    _connectionStatus.postValue(getApplication<Application>().getString(R.string.status_json_send_failed, deviceName, status.toString())) // Ensure this string takes 2 params
                }
            }
        }

        // Overload for Android 13+ (API 33+)
        @SuppressLint("MissingPermission")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val deviceName = if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) gatt.device.name ?: gatt.device.address else gatt.device.address
            val data = String(value, Charsets.UTF_8)
            Log.i(TAG, "onCharacteristicChanged (API 33+) from $deviceName ${characteristic.uuid}: $data")
            // Handle received data if necessary
        }

        // Deprecated version for older APIs
        @Suppress("DEPRECATION")
        @SuppressLint("MissingPermission")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val deviceName = if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) gatt.device.name ?: gatt.device.address else gatt.device.address
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val value = characteristic.value
                if (value != null) {
                    val data = String(value, Charsets.UTF_8)
                    Log.i(TAG, "onCharacteristicChanged (pre-API 33) from $deviceName ${characteristic.uuid}: $data")
                    // Handle received data if necessary
                } else {
                    Log.w(TAG, "onCharacteristicChanged (pre-API 33) from $deviceName ${characteristic.uuid} with null value")
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val deviceName = if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) gatt.device.name ?: gatt.device.address else gatt.device.address
            Log.d(TAG, "onDescriptorWrite for ${descriptor.uuid} (characteristic ${descriptor.characteristic.uuid}) on $deviceName status: $status")

            // Check if this is the descriptor for our NUS RX characteristic's CCCD
            if (descriptor.characteristic.uuid == NORDIC_UART_RX_CHARACTERISTIC_UUID && descriptor.uuid == CLIENT_CHARACTERISTIC_CONFIG_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "Successfully enabled notifications for RX characteristic on $deviceName.")
                    // Now that notifications are confirmed enabled, try sending data.
                    if (nusTxCharacteristic != null) {
                        Log.d(TAG, "Notifications enabled, attempting to send current date/time.")
                        sendCurrentDateTime()
                    } else {
                        Log.w(TAG, "Notifications enabled, but NUS TX characteristic is null. Cannot send data.")
                        _connectionStatus.postValue(getApplication<Application>().getString(R.string.status_nus_tx_char_not_found_on_send))
                    }
                } else {
                    Log.e(TAG, "Failed to enable notifications for RX characteristic on $deviceName, status: $status")
                    _connectionStatus.postValue(getApplication<Application>().getString(R.string.status_nus_rx_enable_failed_status, deviceName, status.toString())) // Add new string
                }
            } else {
                Log.d(TAG, "onDescriptorWrite for a different descriptor: ${descriptor.uuid} on char ${descriptor.characteristic.uuid}")
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
        // Format: \"%d-%d-%dT%d:%d:%d\", &year, &month, &day, &hour, &minute, &second
        // Using ISO_LOCAL_DATE_TIME format which is YYYY-MM-DDTHH:MM:SS.sss
        // For the requested format without milliseconds and ensuring zero padding:
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
            // For pre-Tiramisu, the writeType is set on the characteristic object itself.
            // For Tiramisu+, it's an argument to the writeCharacteristic method.
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE // Set for pre-Tiramisu case

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Using WRITE_TYPE_NO_RESPONSE as the third argument here
                val result = bluetoothGatt!!.writeCharacteristic(characteristic, payloadBytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                Log.d(TAG, "writeCharacteristic (Tiramisu+) for NUS TX (using NO_RESPONSE) result: $result (0 is SUCCESS)")
                if (result != BluetoothStatusCodes.SUCCESS) { // BluetoothStatusCodes.SUCCESS is 0
                    Log.e(TAG, "Failed to initiate write characteristic (Tiramisu+, NO_RESPONSE): error $result")
                    _connectionStatus.postValue(getApplication<Application>().getString(R.string.status_json_send_failed_code, result.toString()))
                } else {
                    // Success for NO_RESPONSE means the data was queued successfully.
                    // There won't be an onCharacteristicWrite callback for WRITE_TYPE_NO_RESPONSE.
                    Log.d(TAG, "Successfully initiated writeCharacteristic (Tiramisu+, NO_RESPONSE) for NUS TX.")
                    // If you expect a response or want to confirm delivery, you might need WRITE_TYPE_DEFAULT
                    // and ensure the peripheral acknowledges. But for now, let's see if NO_RESPONSE works.
                }
            } else {
                // Pre-Tiramisu path, characteristic.writeType set above is used.
                @Suppress("DEPRECATION")
                characteristic.value = payloadBytes
                @Suppress("DEPRECATION")
                val success = bluetoothGatt!!.writeCharacteristic(characteristic)
                Log.d(TAG, "writeCharacteristic (pre-Tiramisu, using NO_RESPONSE) for NUS TX initiated: $success")
                if (!success) {
                    Log.e(TAG, "Failed to initiate write characteristic (pre-Tiramisu, NO_RESPONSE)")
                    _connectionStatus.postValue(getApplication<Application>().getString(R.string.status_json_send_failed))
                } else {
                    // Similar to Tiramisu+, for NO_RESPONSE, success here means queued.
                    // No onCharacteristicWrite callback.
                    Log.d(TAG, "Successfully initiated writeCharacteristic (pre-Tiramisu, NO_RESPONSE) for NUS TX.")
                }
            }
        }
    }
    private fun hasBluetoothConnectPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            getApplication<Application>().applicationContext, // Corrigido
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }




    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        if (!hasBluetoothConnectPermission()) {
            _connectionStatus.postValue(application.getString(R.string.status_connect_permission_error))
            Log.w(TAG, "Bluetooth connect permission missing to connect.")
            return
        }

        stopScan() // Garante que o scan está parado

        _connectionStatus.postValue(
            application.getString(
                R.string.status_connecting_to,
                device.name ?: device.address
            )
        )
        Log.i(TAG, "Connecting to device: ${device.name} (${device.address})")

        // Fechar qualquer conexão GATT existente antes de criar uma nova
        bluetoothGatt?.close()
        bluetoothGatt = null

        // Conectar ao dispositivo
        // Usar TRANSPORT_LE para conexões BLE explicitamente
        // O handler pode ser útil para garantir que callbacks ocorram em um thread específico, mas null usa o padrão.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            bluetoothGatt =
                device.connectGatt(application, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            bluetoothGatt = device.connectGatt(application, false, gattCallback)
        }

        if (bluetoothGatt == null) {
            _connectionStatus.postValue(
                application.getString(
                    R.string.status_connection_failed_generic,
                    device.name ?: device.address
                )
            )
            Log.e(TAG, "connectGatt returned null for device: ${device.address}")
        }
    }
    @SuppressLint("MissingPermission")
    fun closeGatt() {
        if (bluetoothGatt != null) {
            if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                try {
                    bluetoothGatt?.disconnect()
                    bluetoothGatt?.close()
                    Log.i(TAG, "GATT client disconnected and closed.")
                } catch (e: SecurityException) {
                    Log.e(TAG, "SecurityException during closeGatt: ${e.message}")
                }
            } else {
                Log.w(TAG, "BLUETOOTH_CONNECT permission missing, cannot fully close GATT.")
            }
            bluetoothGatt = null
            nusTxCharacteristic = null
            nusRxCharacteristic = null
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ActivityCompat.checkSelfPermission(
            getApplication<Application>().applicationContext,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onCleared() {
        super.onCleared()
        closeGatt()
    }
}
