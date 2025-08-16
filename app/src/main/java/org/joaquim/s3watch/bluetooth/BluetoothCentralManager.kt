package org.joaquim.s3watch.bluetooth

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import no.nordicsemi.android.ble.observer.ConnectionObserver
import org.joaquim.s3watch.ui.device.DeviceConnectionViewModel
import org.joaquim.s3watch.ui.home.HomeViewModel.ConnectionStatus
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/**
 * Centralised BLE manager built on top of Nordic's Android BLE library.
 * It handles connection state and basic data transmission over the
 * Nordic UART Service.
 */
@SuppressLint("MissingPermission")
object BluetoothCentralManager : ConnectionObserver {

    private lateinit var applicationContext: Application
    private lateinit var sharedPreferences: SharedPreferences
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleManager: S3BleManager? = null

    private val _connectionState = MutableLiveData(ConnectionStatus.DISCONNECTED)
    val connectionState: LiveData<ConnectionStatus> = _connectionState

    private val _connectedDeviceName = MutableLiveData<String?>()
    val connectedDeviceName: LiveData<String?> = _connectedDeviceName

    private val _lastErrorMessage = MutableLiveData<String?>()
    val lastErrorMessage: LiveData<String?> = _lastErrorMessage

    private val _dataReceived = MutableLiveData<String>()
    val dataReceived: LiveData<String> = _dataReceived

    fun initialize(app: Application) {
        applicationContext = app
        sharedPreferences = app.getSharedPreferences(DeviceConnectionViewModel.PREFS_NAME, Context.MODE_PRIVATE)
        val manager = app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
        bluetoothAdapter = manager?.adapter
    }

    fun connect(deviceAddress: String, deviceNameHint: String? = null) {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            _lastErrorMessage.postValue("Bluetooth not available")
            _connectionState.postValue(ConnectionStatus.ERROR)
            return
        }
        val device = try {
            adapter.getRemoteDevice(deviceAddress)
        } catch (e: IllegalArgumentException) {
            _lastErrorMessage.postValue("Invalid Bluetooth address.")
            _connectionState.postValue(ConnectionStatus.ERROR)
            return
        }

        bleManager?.close()
        bleManager = S3BleManager(applicationContext).apply {
            onDataReceived = { _dataReceived.postValue(it) }
            setConnectionObserver(this@BluetoothCentralManager)
        }
        _connectedDeviceName.postValue(deviceNameHint ?: device.name ?: deviceAddress)
        _connectionState.postValue(ConnectionStatus.CONNECTING)
        bleManager?.connect(device)?.retry(3, 100)?.enqueue()
    }

    fun reconnect() {
        val address = sharedPreferences.getString(DeviceConnectionViewModel.KEY_CONNECTED_DEVICE_ADDRESS, null)
        val name = sharedPreferences.getString(DeviceConnectionViewModel.KEY_CONNECTED_DEVICE_NAME, null)
        if (address != null) {
            connect(address, name)
        } else {
            _lastErrorMessage.postValue("No saved device to reconnect.")
            _connectionState.postValue(ConnectionStatus.DISCONNECTED)
        }
    }

    fun disconnect() {
        bleManager?.disconnect()?.enqueue()
    }

    fun sendDateTime() {
        val dateTimeString = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        } else {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
        }
        val jsonData = JSONObject().apply { put("datetime", dateTimeString) }.toString()
        sendJson(jsonData)
    }

    fun sendJson(jsonData: String) {
        bleManager?.send(jsonData) ?: run {
            _lastErrorMessage.postValue("Not connected or BLE manager unavailable.")
        }
    }

    // ConnectionObserver callbacks
    override fun onDeviceConnecting(device: BluetoothDevice) {
        _connectionState.postValue(ConnectionStatus.CONNECTING)
    }

    override fun onDeviceConnected(device: BluetoothDevice) {
        _connectionState.postValue(ConnectionStatus.CONNECTING)
    }

    override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) {
        _lastErrorMessage.postValue("Failed to connect: $reason")
        _connectionState.postValue(ConnectionStatus.ERROR)
    }

    override fun onDeviceReady(device: BluetoothDevice) {
        _connectedDeviceName.postValue(device.name ?: device.address)
        _connectionState.postValue(ConnectionStatus.CONNECTED)
        sharedPreferences.edit()
            .putString(DeviceConnectionViewModel.KEY_CONNECTED_DEVICE_ADDRESS, device.address)
            .putString(DeviceConnectionViewModel.KEY_CONNECTED_DEVICE_NAME, device.name ?: device.address)
            .apply()
    }

    override fun onDeviceDisconnecting(device: BluetoothDevice) {
        _connectionState.postValue(ConnectionStatus.DISCONNECTED)
    }

    override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) {
        _connectionState.postValue(ConnectionStatus.DISCONNECTED)
        bleManager?.close()
        bleManager = null
    }
}

