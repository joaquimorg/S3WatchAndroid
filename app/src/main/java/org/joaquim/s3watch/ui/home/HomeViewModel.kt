package org.joaquim.s3watch.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.map
import org.joaquim.s3watch.R
import org.joaquim.s3watch.bluetooth.BluetoothCentralManager

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val _text = MutableLiveData<String>()
    val text: LiveData<String> = _text

    private val _connectedDeviceName = MutableLiveData<String?>()
    val connectedDeviceName: LiveData<String?> = _connectedDeviceName

    // Internal and Exposed LiveData using BluetoothCentralManager.ConnectionStatus
    private val _connectionState = MutableLiveData<BluetoothCentralManager.ConnectionStatus?>()
    val connectionState: LiveData<BluetoothCentralManager.ConnectionStatus?> = _connectionState

    val reconnectButtonText: LiveData<Int> = connectionState.map {
        when (it) {
            BluetoothCentralManager.ConnectionStatus.CONNECTED -> R.string.action_disconnect
            BluetoothCentralManager.ConnectionStatus.CONNECTING -> R.string.action_connecting // Or a more specific "Cancel Connecting"
            else -> R.string.action_try_to_reconnect
        }
    }

    private val bluetoothManager = BluetoothCentralManager.INSTANCE

    private val connectionStateObserver: (BluetoothCentralManager.ConnectionStatus?) -> Unit = { status ->
        _connectionState.postValue(status ?: BluetoothCentralManager.ConnectionStatus.DISCONNECTED)
        updateUiTextBasedOnState(status, bluetoothManager.connectedDeviceName.value)
    }
    private val deviceNameObserver: (String?) -> Unit = { name ->
        _connectedDeviceName.postValue(name)
        updateUiTextBasedOnState(bluetoothManager.connectionState.value, name)
    }
    private val errorMessageObserver: (String?) -> Unit = { errorMessage ->
        if (errorMessage != null && _connectionState.value == BluetoothCentralManager.ConnectionStatus.ERROR) {
            _text.postValue("Error: $errorMessage")
        }
    }

    init {
        bluetoothManager.connectionState.observeForever(connectionStateObserver)
        bluetoothManager.connectedDeviceName.observeForever(deviceNameObserver)
        bluetoothManager.lastErrorMessage.observeForever(errorMessageObserver)
        loadInitialDeviceStateAndReconnectIfNeeded()
    }

    private fun loadInitialDeviceStateAndReconnectIfNeeded() {
        val currentStatus = bluetoothManager.connectionState.value ?: BluetoothCentralManager.ConnectionStatus.DISCONNECTED
        val currentName = bluetoothManager.connectedDeviceName.value
        _connectionState.postValue(currentStatus)
        _connectedDeviceName.postValue(currentName)
        updateUiTextBasedOnState(currentStatus, currentName)

        if (currentStatus == BluetoothCentralManager.ConnectionStatus.DISCONNECTED && currentName != null) {
             // Only try to reconnect if there was a previously connected device
            _text.postValue(getApplication<Application>().getString(R.string.status_connecting_to, currentName))
            bluetoothManager.reconnect()
        } else if (currentStatus == BluetoothCentralManager.ConnectionStatus.DISCONNECTED) {
            _text.postValue(getApplication<Application>().getString(R.string.home_no_device_connected))
        }
    }
    
    private fun updateUiTextBasedOnState(status: BluetoothCentralManager.ConnectionStatus?, name: String?) {
        val app = getApplication<Application>()
        when (status) {
            BluetoothCentralManager.ConnectionStatus.CONNECTED -> _text.postValue(app.getString(R.string.home_connected_to, name ?: "Device"))
            BluetoothCentralManager.ConnectionStatus.CONNECTING -> _text.postValue(app.getString(R.string.status_connecting_to, name ?: "Device"))
            BluetoothCentralManager.ConnectionStatus.DISCONNECTED -> _text.postValue(app.getString(R.string.home_no_device_connected))
            BluetoothCentralManager.ConnectionStatus.ERROR -> {
                if (bluetoothManager.lastErrorMessage.value == null) {
                     _text.postValue(app.getString(R.string.status_connection_error, "Unknown error", name ?: "device"))
                } // Error message is also handled by errorMessageObserver, which might be more specific
            }
            null -> _text.postValue(app.getString(R.string.status_unknown)) // Should ideally not be null after init
        }
    }

    fun handleReconnectButtonClick() {
        when (connectionState.value) {
            BluetoothCentralManager.ConnectionStatus.CONNECTED -> bluetoothManager.disconnect()
            BluetoothCentralManager.ConnectionStatus.CONNECTING -> { /* TODO: Implement cancel connection logic if desired */ }
            BluetoothCentralManager.ConnectionStatus.DISCONNECTED, BluetoothCentralManager.ConnectionStatus.ERROR, null -> {
                _connectionState.postValue(BluetoothCentralManager.ConnectionStatus.CONNECTING) // Immediate UI feedback
                // Try to reconnect to the last device, or prompt user if none is known
                val lastName = bluetoothManager.connectedDeviceName.value ?: "previous device"
                 _text.postValue(getApplication<Application>().getString(R.string.status_connecting_to, lastName))
                bluetoothManager.reconnect()
            }
        }
    }

    fun sendDateTimeToDevice() {
        if (bluetoothManager.connectionState.value == BluetoothCentralManager.ConnectionStatus.CONNECTED) {
            _text.postValue("Sending Date/Time to ${connectedDeviceName.value ?: "device"}...")
            bluetoothManager.sendDateTime()
        } else {
            _text.postValue("Device not connected. Cannot send Date/Time.")
        }
    }

    override fun onCleared() {
        super.onCleared()
        bluetoothManager.connectionState.removeObserver(connectionStateObserver)
        bluetoothManager.connectedDeviceName.removeObserver(deviceNameObserver)
        bluetoothManager.lastErrorMessage.removeObserver(errorMessageObserver)
    }
}