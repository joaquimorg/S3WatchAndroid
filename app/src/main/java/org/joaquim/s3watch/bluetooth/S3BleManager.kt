package org.joaquim.s3watch.bluetooth

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import no.nordicsemi.android.ble.BleManager
import java.util.UUID

/**
 * Simple BLE manager using Nordic's Android BLE library to handle the
 * Nordic UART Service (NUS) used by the watch.
 */
class S3BleManager(context: Context) : BleManager(context) {

    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null

    /** Callback invoked when data is received from the peripheral. */
    var onDataReceived: ((String) -> Unit)? = null

    override fun getGattCallback(): BleManagerGattCallback = object : BleManagerGattCallback() {
        override fun initialize() {
            setNotificationCallback(rxCharacteristic).with { _, data ->
                onDataReceived?.invoke(data.value?.toString(Charsets.UTF_8) ?: "")
            }
            enableNotifications(rxCharacteristic).enqueue()
        }

        override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
            val service = gatt.getService(NUS_SERVICE_UUID) ?: return false
            txCharacteristic = service.getCharacteristic(NUS_TX_CHARACTERISTIC_UUID)
            rxCharacteristic = service.getCharacteristic(NUS_RX_CHARACTERISTIC_UUID)
            return txCharacteristic != null && rxCharacteristic != null
        }

        override fun onServicesInvalidated() {
            txCharacteristic = null
            rxCharacteristic = null
        }
    }

    /** Send a UTF-8 string to the peripheral using the TX characteristic. */
    fun send(data: String) {
        writeCharacteristic(txCharacteristic, data.toByteArray(Charsets.UTF_8)).enqueue()
    }

    private companion object {
        val NUS_SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val NUS_RX_CHARACTERISTIC_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        val NUS_TX_CHARACTERISTIC_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    }
}

