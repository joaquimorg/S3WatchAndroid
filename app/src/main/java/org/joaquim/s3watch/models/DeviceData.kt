package org.joaquim.s3watch.models

import org.json.JSONObject

data class DeviceData(
    val battery: Int,
    val charging: Boolean,
    val steps: Int
) {
    companion object {
        fun fromJson(jsonString: String): DeviceData {
            val jsonObject = JSONObject(jsonString)
            val battery = jsonObject.optInt("battery", 0) // Default to 0 if not found
            val charging = jsonObject.optBoolean("charging", false) // Default to false
            val steps = jsonObject.optInt("steps", 0) // Default to 0
            return DeviceData(battery, charging, steps)
        }
    }
}
