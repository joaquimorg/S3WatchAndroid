package org.joaquim.s3watch.ui.device

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import org.joaquim.s3watch.utils.SingleLiveEvent

class DeviceConnectionViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "DeviceConnectionVM"
        private const val NORDIC_UART_SERVICE_UUID = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
        const val PREFS_NAME = "S3WatchPrefs"
        const val KEY_CONNECTED_DEVICE_ADDRESS = "connected_device_mac"
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (application.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }
    private val bluetoothLeScanner by lazy { bluetoothAdapter?.bluetoothLeScanner }

    private var bluetoothGatt: BluetoothGatt? = null

    private val _discoveredDevices = MutableLiveData<MutableList<BluetoothDevice>>(mutableListOf())
    val discoveredDevices: LiveData<List<BluetoothDevice>> get() = _discoveredDevices as LiveData<List<BluetoothDevice>>

    private val _isScanning = MutableLiveData(false)
    val isScanning: LiveData<Boolean> = _isScanning

    private val _connectionStatus = MutableLiveData<String?>()
    val connectionStatus: LiveData<String?> = _connectionStatus

    private val _navigateToHome = SingleLiveEvent<Unit>()
    val navigateToHome: LiveData<Unit> = _navigateToHome

    private val sharedPreferences: SharedPreferences = 
        application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        // Poderia carregar o endereço aqui se quiséssemos tentar uma reconexão automática, por exemplo.
        // val connectedDeviceAddress = sharedPreferences.getString(KEY_CONNECTED_DEVICE_ADDRESS, null)
        // Log.d(TAG, "Last connected device address: $connectedDeviceAddress")
    }

    @SuppressLint("MissingPermission") // Permissions are checked in the Fragment
    fun startScan() {
        if (_isScanning.value == true) {
            Log.d(TAG, "Scan already in progress.")
            return
        }

        if (bluetoothLeScanner == null) {
            Log.e(TAG, "Bluetooth LE Scanner not available.")
            _connectionStatus.postValue("Scanner BLE não disponível.")
            return
        }

        _discoveredDevices.value?.clear()
        _discoveredDevices.postValue(_discoveredDevices.value ?: mutableListOf())
        _isScanning.postValue(true)
        _connectionStatus.postValue("Buscando dispositivos...")

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        // Filtro (descomente e ajuste se necessário)
        // val filters: MutableList<ScanFilter> = ArrayList()
        // val serviceUuid = ParcelUuid.fromString(NORDIC_UART_SERVICE_UUID)
        // val nordicUartFilter = ScanFilter.Builder().setServiceUuid(serviceUuid).build()
        // filters.add(nordicUartFilter)

        Log.d(TAG, "Starting BLE scan...")
        try {
            bluetoothLeScanner?.startScan(null, scanSettings, leScanCallback) // Usando null para filtros por enquanto
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException during startScan: ${e.message}")
            _connectionStatus.postValue("Erro de permissão ao iniciar o scan.")
            _isScanning.postValue(false)
        }
    }

    @SuppressLint("MissingPermission") // Permissions are checked in the Fragment
    fun stopScan() {
        if (_isScanning.value == false) {
            Log.d(TAG, "Scan not in progress or already stopped.")
            return
        }
        try {
            bluetoothLeScanner?.stopScan(leScanCallback)
            Log.d(TAG, "Scan stopped.")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException during stopScan: ${e.message}")
            _connectionStatus.postValue("Erro de permissão ao parar o scan.")
        }
        _isScanning.postValue(false)
    }

    private val leScanCallback: ScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            val device = result.device
            Log.d(TAG, "onScanResult: Device Address: ${device?.address}, Name: ${device?.name}")

            val currentList = _discoveredDevices.value ?: mutableListOf()
            if (device != null && device.address != null && currentList.none { it.address == device.address }) {
                try {
                     Log.d(TAG, "Device added: ${device.name ?: "Unknown"} - ${device.address}")
                } catch (e: SecurityException) {
                    Log.e(TAG, "SecurityException for device.name: ${e.message}")
                }
                currentList.add(device)
                _discoveredDevices.postValue(currentList)
            }
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            super.onBatchScanResults(results)
            Log.d(TAG, "onBatchScanResults: ${results.size} devices found")
            results.forEach { result ->
                val device = result.device
                val currentList = _discoveredDevices.value ?: mutableListOf()
                 if (device != null && device.address != null && currentList.none { it.address == device.address }) {
                    try {
                        Log.d(TAG, "Device added (batch): ${device.name ?: "Unknown"} - ${device.address}")
                    } catch (e: SecurityException) {
                        Log.e(TAG, "SecurityException for device.name (batch): ${e.message}")
                    }
                    currentList.add(device)
                 }
                 _discoveredDevices.postValue(currentList)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e(TAG, "LE Scan Failed: $errorCode")
            _isScanning.postValue(false)
            _connectionStatus.postValue("Falha no Scan BLE: $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        if (bluetoothGatt != null) {
            Log.w(TAG, "GATT connection already exists or in progress. Disconnecting previous one.")
            // bluetoothGatt?.disconnect() // Consider a full disconnect/close sequence
            // bluetoothGatt?.close()
            // bluetoothGatt = null 
            // Você pode querer gerenciar isso de forma mais robusta, como permitir apenas uma conexão por vez.
        }
        _connectionStatus.postValue("Conectando a ${device.name ?: device.address}...")
        Log.d(TAG, "Attempting to connect to device: ${device.address}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            bluetoothGatt = device.connectGatt(getApplication(), false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            bluetoothGatt = device.connectGatt(getApplication(), false, gattCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val deviceAddress = gatt.device.address
            val deviceName = gatt.device.name ?: deviceAddress

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "Successfully connected to $deviceName ($deviceAddress)")
                    _connectionStatus.postValue("Conectado a $deviceName")
                    stopScan() // Parar o scan após conectar
                    bluetoothGatt = gatt // Store GATT instance
                    
                    // Salvar o endereço do dispositivo
                    with(sharedPreferences.edit()) {
                        putString(KEY_CONNECTED_DEVICE_ADDRESS, deviceAddress)
                        apply()
                    }
                    Log.i(TAG, "Device address $deviceAddress saved to SharedPreferences.")

                    // Descobrir serviços
                    gatt.discoverServices()
                    
                    // Navegar para Home
                    _navigateToHome.postValue(Unit)

                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i(TAG, "Successfully disconnected from $deviceName ($deviceAddress)")
                    _connectionStatus.postValue("Desconectado de $deviceName")
                    closeGatt()
                }
            } else {
                Log.w(TAG, "Error $status encountered for $deviceName ($deviceAddress)! Disconnecting.")
                _connectionStatus.postValue("Erro de conexão: $status com $deviceName")
                closeGatt()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Services discovered for ${gatt.device.name ?: gatt.device.address}")
                // TODO: Interagir com os serviços (ex: Nordic UART Service)
                // gatt.services.forEach { service -> Log.d(TAG, "Service UUID: ${service.uuid}") }
            } else {
                Log.w(TAG, "Service discovery failed with status $status for ${gatt.device.name ?: gatt.device.address}")
            }
        }
        // TODO: Implementar onCharacteristicRead, onCharacteristicWrite, onCharacteristicChanged
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        bluetoothGatt?.disconnect()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun closeGatt() {
        bluetoothGatt?.close()
        bluetoothGatt = null
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "ViewModel cleared. Disconnecting and closing GATT.")
        disconnect()
        closeGatt()
    }
}
