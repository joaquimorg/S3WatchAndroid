package org.joaquim.s3watch.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.telephony.SmsMessage
import org.joaquim.s3watch.bluetooth.BluetoothCentralManager

/**
 * Receives incoming SMS messages and forwards them to the watch.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "android.provider.Telephony.SMS_RECEIVED") {
            val bundle: Bundle = intent.extras ?: return
            val pdus = bundle.get("pdus") as? Array<*> ?: return
            val format = bundle.getString("format")
            for (pdu in pdus) {
                val sms = SmsMessage.createFromPdu(pdu as ByteArray, format)
                val from = sms.displayOriginatingAddress
                val message = sms.messageBody
                BluetoothCentralManager.sendNotification("sms", "SMS from $from", message)
            }
        }
    }
}

