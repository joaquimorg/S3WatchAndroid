package org.joaquim.s3watch.ui.notifications

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class NotificationSettingsViewModel : ViewModel() {

    private val _text = MutableLiveData<String>().apply {
        value = "This is a placeholder for the Notification Settings Fragment"
    }
    val text: LiveData<String> = _text
}