package org.joaquim.s3watch.ui.home

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import org.joaquim.s3watch.ui.device.DeviceConnectionViewModel // Para as constantes

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val _text = MutableLiveData<String>().apply {
        value = "This is home Fragment"
    }
    val text: LiveData<String> = _text

    private val _connectedDeviceAddress = MutableLiveData<String?>()
    val connectedDeviceAddress: LiveData<String?> = _connectedDeviceAddress

    private val sharedPreferences: SharedPreferences = 
        application.getSharedPreferences(DeviceConnectionViewModel.PREFS_NAME, Context.MODE_PRIVATE)

    init {
        loadConnectedDevice()
    }

    private fun loadConnectedDevice() {
        val address = sharedPreferences.getString(DeviceConnectionViewModel.KEY_CONNECTED_DEVICE_ADDRESS, null)
        _connectedDeviceAddress.postValue(address)
        if (address != null) {
            _text.postValue("Conectado a: $address") // Atualiza o texto principal também
        } else {
            _text.postValue("Nenhum dispositivo conectado")
        }
    }

    // Função para ser chamada se a conexão for perdida ou um novo dispositivo for conectado em outro lugar
    fun refreshConnectedDevice() {
        loadConnectedDevice()
    }
}
