package org.joaquim.s3watch.bluetooth

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.*
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.joaquim.s3watch.ui.device.DeviceConnectionViewModel // For SharedPreferences constants
// Removed: import org.joaquim.s3watch.ui.home.HomeViewModel.ConnectionStatus 
import org.json.JSONObject
import kotlin.math.min
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*

// Singleton to manage Bluetooth connection centrally
@SuppressLint("MissingPermission") // Add this if you handle permissions prior to calling methods
object BluetoothCentralManager {

    private const val TAG = "BluetoothCentralManager"

    // Define ConnectionStatus enum here
    enum class ConnectionStatus {
        CONNECTED,
        DISCONNECTED,
        CONNECTING,
        ERROR
    }

    // Nordic UART Service (NUS) and characteristics UUIDs
    private val NUS_SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    private val NUS_RX_CHARACTERISTIC_UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e") // Receive from S3 (Notify)
    private val NUS_TX_CHARACTERISTIC_UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e") // Send to S3 (Write)
    private val CCC_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb") // Client Characteristic Configuration Descriptor
    private const val BLE_MTU = 128

    val INSTANCE: BluetoothCentralManager by lazy { this }

    private lateinit var applicationContext: Application
    private lateinit var sharedPreferences: SharedPreferences
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var nusTxCharacteristic: BluetoothGattCharacteristic? = null
    private var nusRxCharacteristic: BluetoothGattCharacteristic? = null
    private val rxBuffer = StringBuilder()

    // LiveData exposed to ViewModels
    private val _connectionState = MutableLiveData<ConnectionStatus>(ConnectionStatus.DISCONNECTED)
    val connectionState: LiveData<ConnectionStatus> = _connectionState

    private val _connectedDeviceName = MutableLiveData<String?>()
    val connectedDeviceName: LiveData<String?> = _connectedDeviceName

    private val _lastErrorMessage = MutableLiveData<String?>()
    val lastErrorMessage: LiveData<String?> = _lastErrorMessage

    private val _dataReceived = MutableLiveData<String>() // For data from RX characteristic
    val dataReceived: LiveData<String> = _dataReceived

    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    fun initialize(app: Application) {
        applicationContext = app
        sharedPreferences = app.getSharedPreferences(DeviceConnectionViewModel.PREFS_NAME, Context.MODE_PRIVATE)
        val bluetoothManager = app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
        bluetoothAdapter = bluetoothManager?.adapter

        loadPersistedDeviceState()
    }

    private fun loadPersistedDeviceState() {
        val address = sharedPreferences.getString(DeviceConnectionViewModel.KEY_CONNECTED_DEVICE_ADDRESS, null)
        val name = sharedPreferences.getString(DeviceConnectionViewModel.KEY_CONNECTED_DEVICE_NAME, null)
        if (address != null) {
            _connectedDeviceName.postValue(name ?: address)
            // Initial state is disconnected; HomeViewModel or other components can trigger a reconnect.
            _connectionState.postValue(ConnectionStatus.DISCONNECTED)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            val deviceAddress = gatt?.device?.address
            val deviceName = gatt?.device?.name ?: sharedPreferences.getString(DeviceConnectionViewModel.KEY_CONNECTED_DEVICE_NAME, deviceAddress)

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "Successfully connected to $deviceName ($deviceAddress). Discovering services...")
                    _connectionState.postValue(ConnectionStatus.CONNECTING)
                    _connectedDeviceName.postValue(deviceName) // Update name if it was fetched from device
                    sharedPreferences.edit()
                        .putString(DeviceConnectionViewModel.KEY_CONNECTED_DEVICE_ADDRESS, deviceAddress)
                        .putString(DeviceConnectionViewModel.KEY_CONNECTED_DEVICE_NAME, deviceName)
                        .apply()
                    bluetoothGatt = gatt
                    coroutineScope.launch {
                        delay(600) // Recommended delay before service discovery
                        bluetoothGatt?.discoverServices()
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i(TAG, "Disconnected from $deviceName ($deviceAddress).")
                    _connectionState.postValue(ConnectionStatus.DISCONNECTED)
                    closeGattInternal()
                }
            } else {
                Log.e(TAG, "GATT Connection Error with $deviceName ($deviceAddress): $status")
                _lastErrorMessage.postValue("GATT Error: $status")
                _connectionState.postValue(ConnectionStatus.ERROR)
                closeGattInternal()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Services discovered for ${gatt?.device?.name}.")
                val service = gatt?.getService(NUS_SERVICE_UUID)
                if (service == null) {
                    Log.e(TAG, "NUS service not found.")
                    _lastErrorMessage.postValue("NUS service not found.")
                    _connectionState.postValue(ConnectionStatus.ERROR)
                    closeGattInternal()
                    return
                }
                nusTxCharacteristic = service.getCharacteristic(NUS_TX_CHARACTERISTIC_UUID)
                nusRxCharacteristic = service.getCharacteristic(NUS_RX_CHARACTERISTIC_UUID)

                if (nusRxCharacteristic == null || nusTxCharacteristic == null) {
                    Log.e(TAG, "NUS RX or TX characteristic not found.")
                    _lastErrorMessage.postValue("NUS RX/TX characteristic not found.")
                    _connectionState.postValue(ConnectionStatus.ERROR)
                    closeGattInternal()
                    return
                }
                enableNotifications(nusRxCharacteristic!!)
            } else {
                Log.w(TAG, "Service discovery failed: $status")
                _lastErrorMessage.postValue("Service discovery failed: $status")
                _connectionState.postValue(ConnectionStatus.ERROR)
                closeGattInternal()
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            if (characteristic?.uuid == NUS_TX_CHARACTERISTIC_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val data = characteristic.value?.toString(Charsets.UTF_8) ?: ""
                    Log.i(TAG, "Data sent successfully via TX : $data")
                    // You could add a LiveData for send status if needed
                } else {
                    Log.e(TAG, "Failed to write TX characteristic: $status")
                    _lastErrorMessage.postValue("Failed to send data: $status")
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            // This is the new way to handle onCharacteristicChanged with Android 13+
            // For older versions, the deprecated onCharacteristicChanged(gatt, characteristic) is used.
            if (characteristic.uuid == NUS_RX_CHARACTERISTIC_UUID) {
                val data = value.toString(Charsets.UTF_8)
                Log.i(TAG, "Data received on RX: $data")
                handleIncomingData(data)
            }
        }
        
        // This is the deprecated version but needed for compatibility with older Android versions.
        @Deprecated("Used for Android 12 and below")
        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                if (characteristic?.uuid == NUS_RX_CHARACTERISTIC_UUID) {
                    val data = characteristic.value?.toString(Charsets.UTF_8) ?: ""
                    Log.i(TAG, "Data received on RX (legacy): $data")
                    handleIncomingData(data)
                }
            }
        }


        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            if (descriptor?.uuid == CCC_DESCRIPTOR_UUID && descriptor.characteristic.uuid == NUS_RX_CHARACTERISTIC_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "RX notifications enabled successfully.")
                    _connectionState.postValue(ConnectionStatus.CONNECTED) // Now fully connected
                    _lastErrorMessage.postValue(null) // Clear any previous error
                    // Optionally send initial data like date/time
                    bluetoothGatt?.requestMtu(128)
                    sendDateTime()
                    //sendStatus()
                } else {
                    Log.e(TAG, "Failed to enable RX notifications: $status.")
                    _lastErrorMessage.postValue("Failed to enable notifications: $status")
                    _connectionState.postValue(ConnectionStatus.ERROR)
                    closeGattInternal()
                }
            }
        }
    }

    private fun handleIncomingData(chunk: String) {
        rxBuffer.append(chunk)
        var newlineIndex = rxBuffer.indexOf("\n")
        while (newlineIndex != -1) {
            val line = rxBuffer.substring(0, newlineIndex).replace("\r", "")
            _dataReceived.postValue(line)
            rxBuffer.delete(0, newlineIndex + 1)
            newlineIndex = rxBuffer.indexOf("\n")
        }
    }

    private fun enableNotifications(characteristic: BluetoothGattCharacteristic) {
        // Check if permissions are granted before calling bluetoothGatt methods
        if (applicationContext.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
             Log.e(TAG, "BLUETOOTH_CONNECT permission not granted.")
            _lastErrorMessage.postValue("BLUETOOTH_CONNECT permission needed.")
            _connectionState.postValue(ConnectionStatus.ERROR)
            return
        }

        val cccDescriptor = characteristic.getDescriptor(CCC_DESCRIPTOR_UUID)
        if (cccDescriptor == null) {
            Log.e(TAG, "CCC Descriptor not found for ${characteristic.uuid}")
            _lastErrorMessage.postValue("CCC Descriptor not found.")
            _connectionState.postValue(ConnectionStatus.ERROR)
            closeGattInternal()
            return
        }

        bluetoothGatt?.let { gatt ->
            if (gatt.setCharacteristicNotification(characteristic, true)) {
                // For Android 13+, POST_NOTIFICATIONS might be needed if your app targets API 33+
                // and the notification is user-visible (not the case for BLE characteristic indications usually)

                val value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE // Still use this for older versions
                }
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val result = gatt.writeDescriptor(cccDescriptor, value)
                    Log.d(TAG, "Writing ENABLE_NOTIFICATION_VALUE to ${characteristic.uuid} (Android 13+): Code $result")
                     if (result != BluetoothStatusCodes.SUCCESS) {
                        Log.e(TAG, "Failed to initiate CCC descriptor write (Android 13+). Code: $result")
                        _lastErrorMessage.postValue("Failed to write CCC descriptor (A13+). Code: $result")
                        // _connectionState.postValue(ConnectionStatus.ERROR) // onDescriptorWrite will handle error
                    }
                } else {
                    cccDescriptor.value = value
                    @Suppress("DEPRECATION")
                    val success = gatt.writeDescriptor(cccDescriptor)
                    Log.d(TAG, "Writing ENABLE_NOTIFICATION_VALUE to ${characteristic.uuid}: $success")
                    if (!success) {
                        Log.e(TAG, "Failed to initiate CCC descriptor write.")
                        _lastErrorMessage.postValue("Failed to write CCC descriptor.")
                         // _connectionState.postValue(ConnectionStatus.ERROR) // onDescriptorWrite will handle error
                    }
                }
            } else {
                Log.e(TAG, "Failed to set notification for ${characteristic.uuid}")
                _lastErrorMessage.postValue("Failed to set notification.")
                _connectionState.postValue(ConnectionStatus.ERROR)
            }
        }
    }

    fun connect(deviceAddress: String, deviceNameHint: String? = null) {
         if (applicationContext.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
             Log.e(TAG, "BLUETOOTH_CONNECT permission not granted for connect.")
            _lastErrorMessage.postValue("BLUETOOTH_CONNECT permission needed.")
            return
        }

        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            _lastErrorMessage.postValue("Bluetooth is not enabled.")
            _connectionState.postValue(ConnectionStatus.ERROR)
            return
        }
        if (_connectionState.value == ConnectionStatus.CONNECTING || (_connectionState.value == ConnectionStatus.CONNECTED && bluetoothGatt?.device?.address == deviceAddress)) {
            Log.w(TAG, "Already connected or connecting to this device.")
            return
        }

        closeGattInternal() // Close any previous connection

        try {
            val device = bluetoothAdapter!!.getRemoteDevice(deviceAddress)
            _connectedDeviceName.postValue(deviceNameHint ?: device.name ?: deviceAddress)
            _connectionState.postValue(ConnectionStatus.CONNECTING)
            Log.i(TAG, "Attempting to connect to ${deviceNameHint ?: device.name ?: deviceAddress} ($deviceAddress)...")
            bluetoothGatt = device.connectGatt(applicationContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)

        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Invalid Bluetooth address: $deviceAddress", e)
            _lastErrorMessage.postValue("Invalid Bluetooth address.")
            _connectionState.postValue(ConnectionStatus.ERROR)
        }
    }

    fun reconnect() {
        val address = sharedPreferences.getString(DeviceConnectionViewModel.KEY_CONNECTED_DEVICE_ADDRESS, null)
        val name = sharedPreferences.getString(DeviceConnectionViewModel.KEY_CONNECTED_DEVICE_NAME, null)
        if (address != null) {
            Log.i(TAG, "Reconnecting to $name ($address)")
            connect(address, name)
        } else {
            _lastErrorMessage.postValue("No saved device to reconnect.")
            _connectionState.postValue(ConnectionStatus.DISCONNECTED)
        }
    }

    fun sendDateTime() {
        if (_connectionState.value != ConnectionStatus.CONNECTED || nusTxCharacteristic == null || bluetoothGatt == null) {
            _lastErrorMessage.postValue("Not connected or TX characteristic unavailable.")
            Log.w(TAG, "Cannot send date/time: Not connected or TX unavailable.")
            return
        }

        val dateTimeString = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        } else {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
        }
        val jsonData = JSONObject().apply { put("datetime", dateTimeString) }.toString()

        sendJson(jsonData)
    }

    fun sendStatus() {
        val jsonData = JSONObject().apply { put("status", "read") }.toString()
        sendJson(jsonData)
    }

    fun sendJson(jsonData: String) {
        if (applicationContext.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
             Log.e(TAG, "BLUETOOTH_CONNECT permission not granted for sendJson.")
            _lastErrorMessage.postValue("BLUETOOTH_CONNECT permission needed.")
            return
        }

        if (_connectionState.value != ConnectionStatus.CONNECTED || nusTxCharacteristic == null || bluetoothGatt == null) {
            _lastErrorMessage.postValue("Not connected or TX characteristic unavailable.")
            Log.w(TAG, "Cannot send JSON: Not connected or TX unavailable.")
            return
        }

        val payload = (jsonData + "\r\n").toByteArray(Charsets.UTF_8)
        var offset = 0
        while (offset < payload.size) {
            val end = min(offset + BLE_MTU, payload.size)
            val chunk = payload.copyOfRange(offset, end)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

                val writeStatus = bluetoothGatt!!.writeCharacteristic(
                    nusTxCharacteristic!!,
                    chunk,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                )
                if (writeStatus != BluetoothStatusCodes.SUCCESS) {
                    Log.e(TAG, "Failed to write chunk: $writeStatus")
                    _lastErrorMessage.postValue("Failed to send data: $writeStatus")
                    return
                }
            } else {
                // nusTxCharacteristic is checked for nullity at the start of the function
                nusTxCharacteristic!!.value = chunk
                nusTxCharacteristic!!.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                val success = bluetoothGatt!!.writeCharacteristic(nusTxCharacteristic!!) // Use !! for safety, though checked
                if (!success) {
                    Log.e(TAG, "Failed to write chunk (legacy)")
                    _lastErrorMessage.postValue("Failed to send data.")
                    return
                }
            }
            offset = end
        }
        Log.i(TAG, "Sent JSON via TX: $jsonData")
    }

    fun disconnect() {
        Log.i(TAG, "Disconnect requested.")
        if (applicationContext.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Log.e(TAG, "BLUETOOTH_CONNECT permission not granted for disconnect.")
            _lastErrorMessage.postValue("BLUETOOTH_CONNECT permission needed to disconnect.")
            // Even if permission is denied, we should update our internal state and clean up resources.
            // The actual BLE disconnect might not happen, but our app state should reflect 'disconnected'.
        }
        
        if (bluetoothGatt != null) {
            bluetoothGatt?.disconnect() // This will trigger onConnectionStateChange with STATE_DISCONNECTED
                                     // which then calls closeGattInternal().
        } else {
            // If gatt is already null, it means we are not connected or already cleaned up.
            // Ensure state reflects this.
            closeGattInternal() // Clean up local resources if any were missed.
            _connectionState.postValue(ConnectionStatus.DISCONNECTED)
            Log.i(TAG, "No active GATT connection to disconnect, ensuring state is DISCONNECTED.")
        }
        // NOTE: Persisted device info (name and address in SharedPreferences) is NOT cleared here.
        // _connectedDeviceName LiveData also retains its last value.
        // This allows the UI to still show the last connected device for easy reconnection.
    }

    private fun closeGattInternal() {
        Log.d(TAG, "Closing GATT internal resources.")
        if (applicationContext.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Log.w(TAG, "BLUETOOTH_CONNECT permission not granted, but closing GATT locally.")
            // We can still try to close, but it might not fully work without permission.
            // The important part is cleaning up our reference.
        }
        try {
            bluetoothGatt?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing BluetoothGatt: ${e.message}")
        }
        bluetoothGatt = null
        nusTxCharacteristic = null
        nusRxCharacteristic = null
        // _connectionState is typically updated by onConnectionStateChange.
        // If closeGattInternal is called outside that flow (e.g., forced cleanup),
        // ensure the state is consistent.
        if (_connectionState.value != ConnectionStatus.DISCONNECTED && _connectionState.value != ConnectionStatus.ERROR) {
             // If we are closing internally and not already in a disconnected or error state,
             // then something is forcing a closure, so update state.
            // However, this might conflict if onConnectionStateChange is about to be called.
            // For now, let onConnectionStateChange be the primary driver for state.
            // Log.d(TAG, "closeGattInternal: State was ${_connectionState.value}, forcing to DISCONNECTED if not ERROR")
            // if (_connectionState.value != ConnectionStatus.ERROR) _connectionState.postValue(ConnectionStatus.DISCONNECTED)
        }
    }
}
