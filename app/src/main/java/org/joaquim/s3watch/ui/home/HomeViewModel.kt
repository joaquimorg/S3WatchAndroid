package org.joaquim.s3watch.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import org.joaquim.s3watch.bluetooth.BluetoothCentralManager

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    enum class ConnectionStatus {
        CONNECTED,
        DISCONNECTED,
        CONNECTING,
        ERROR
    }

    private val _text = MutableLiveData<String>()
    val text: LiveData<String> = _text

    private val _connectedDeviceName = MutableLiveData<String?>()
    val connectedDeviceName: LiveData<String?> = _connectedDeviceName

    private val _connectionState = MutableLiveData<ConnectionStatus>()
    val connectionState: LiveData<ConnectionStatus> = _connectionState

    private val bluetoothManager = BluetoothCentralManager.INSTANCE

    // Lambdas for observing, to allow removal in onCleared
    private val connectionStateObserver: (ConnectionStatus?) -> Unit = { status ->
        _connectionState.postValue(status ?: ConnectionStatus.DISCONNECTED)
        updateUiTextBasedOnState(status, bluetoothManager.connectedDeviceName.value)
    }
    private val deviceNameObserver: (String?) -> Unit = { name ->
        _connectedDeviceName.postValue(name)
        updateUiTextBasedOnState(bluetoothManager.connectionState.value, name)
    }
    private val errorMessageObserver: (String?) -> Unit = { errorMessage ->
        if (errorMessage != null && _connectionState.value == ConnectionStatus.ERROR) {
            _text.postValue("Error: $errorMessage")
        }
    }

    init {
        // Observe connection state from BluetoothCentralManager
        bluetoothManager.connectionState.observeForever(connectionStateObserver)

        // Observe connected device name from BluetoothCentralManager
        bluetoothManager.connectedDeviceName.observeForever(deviceNameObserver)

        // Observe error messages from BluetoothCentralManager
        bluetoothManager.lastErrorMessage.observeForever(errorMessageObserver)
        
        // Load initial state and attempt reconnect if disconnected
        loadInitialDeviceStateAndReconnectIfNeeded()
    }

    private fun loadInitialDeviceStateAndReconnectIfNeeded() {
        val currentStatus = bluetoothManager.connectionState.value ?: ConnectionStatus.DISCONNECTED
        val currentName = bluetoothManager.connectedDeviceName.value
        _connectionState.postValue(currentStatus)
        _connectedDeviceName.postValue(currentName)
        updateUiTextBasedOnState(currentStatus, currentName)

        if (currentStatus == ConnectionStatus.DISCONNECTED) {
            _text.postValue("No device connected. Trying to reconnect...")
            bluetoothManager.reconnect() // Attempt to reconnect to the last known device
        }
    }
    
    private fun updateUiTextBasedOnState(status: ConnectionStatus?, name: String?) {
        when (status) {
            ConnectionStatus.CONNECTED -> _text.postValue("Connected to: ${name ?: "Device"}")
            ConnectionStatus.CONNECTING -> _text.postValue("Connecting to: ${name ?: "Device"}...")
            ConnectionStatus.DISCONNECTED -> _text.postValue("No device connected.")
            ConnectionStatus.ERROR -> {
                if (bluetoothManager.lastErrorMessage.value == null) {
                     _text.postValue("Connection error with ${name ?: "device"}.")
                }
                // Error message is also handled by errorMessageObserver
            }
            null -> _text.postValue("Unknown connection state.")
        }
    }

    fun refreshConnectedDevice() {
        // This function might ask BluetoothCentralManager to re-evaluate/re-emit its state,
        // or simply reload from the manager's current LiveData as done in loadInitialDeviceState.
        val currentStatus = bluetoothManager.connectionState.value ?: ConnectionStatus.DISCONNECTED
        val currentName = bluetoothManager.connectedDeviceName.value
        _connectionState.postValue(currentStatus)
        _connectedDeviceName.postValue(currentName)
        updateUiTextBasedOnState(currentStatus, currentName)
        // Optionally, if BluetoothCentralManager has a specific refresh method:
        // bluetoothManager.refreshState() 
    }

    fun attemptDeviceReconnect() {
        _connectionState.postValue(ConnectionStatus.CONNECTING) // Immediate UI feedback
        _text.postValue("Attempting to reconnect...")
        bluetoothManager.reconnect()
        // The result of the reconnection will be observed through BluetoothCentralManager's LiveData
    }

    fun sendDateTimeToDevice() {
        if (bluetoothManager.connectionState.value == ConnectionStatus.CONNECTED) {
            _text.postValue("Sending Date/Time to ${connectedDeviceName.value ?: "device"}...")
            bluetoothManager.sendDateTime()
            // Feedback for the send operation (success/failure) could come from another LiveData
            // in BluetoothCentralManager, e.g., bluetoothManager.lastSendStatus.observeForever { ... }
            // For now, we assume if no immediate error, it was "sent".
            // A more robust implementation would update _text upon confirmation or failure from the manager.
        } else {
            _text.postValue("Device not connected. Cannot send Date/Time.")
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Remove observers to prevent memory leaks
        bluetoothManager.connectionState.removeObserver(connectionStateObserver)
        bluetoothManager.connectedDeviceName.removeObserver(deviceNameObserver)
        bluetoothManager.lastErrorMessage.removeObserver(errorMessageObserver)
    }
}
