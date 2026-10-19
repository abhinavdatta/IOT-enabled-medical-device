package com.waterbench.controller

import org.json.JSONObject

/**
 * Single source of truth for device state.
 * Data comes only from a successful /api/status response.
 */
data class DeviceStatus(
    val state: String,
    val fault: String,
    val wifiName: String,
    val wifiClients: Int,
    val targetDrops: Int,
    val dropsDelivered: Int,
    val dropsRemaining: Int,
    val targetFlowMlMin: Double,
    val flowMlMin: Double,
    val dropsPerSecond: Double,
    val pwm: Int,
    val motorPercent: Int,
    val motorVoltageEstimateV: Double,
    val busVoltageV: Double,
    val currentMa: Int,
    val pressureKpa: Double,
    val pressureSensorPresent: Boolean,
    val currentMonitorPresent: Boolean,
    val physicalStopPressed: Boolean
) {
    companion object {
        fun fromJson(json: String): DeviceStatus? = try {
            val o = JSONObject(json)
            DeviceStatus(
                state = o.optString("state", "IDLE"),
                fault = o.optString("fault", "NONE"),
                wifiName = o.optString("wifiName", ""),
                wifiClients = o.optInt("wifiClients", 0),
                targetDrops = o.optInt("targetDrops", 3),
                dropsDelivered = o.optInt("dropsDelivered", 0),
                dropsRemaining = o.optInt("dropsRemaining", 0),
                targetFlowMlMin = o.optDouble("targetFlowMlMin", 0.0),
                flowMlMin = o.optDouble("flowMlMin", 0.0),
                dropsPerSecond = o.optDouble("dropsPerSecond", 0.0),
                pwm = o.optInt("pwm", 0),
                motorPercent = o.optInt("motorPercent", 0),
                motorVoltageEstimateV = o.optDouble("motorVoltageEstimateV", 0.0),
                busVoltageV = o.optDouble("busVoltageV", 0.0),
                currentMa = o.optInt("currentMa", 0),
                pressureKpa = o.optDouble("pressureKpa", 0.0),
                pressureSensorPresent = o.optBoolean("pressureSensorPresent", false),
                currentMonitorPresent = o.optBoolean("currentMonitorPresent", false),
                physicalStopPressed = o.optBoolean("physicalStopPressed", false)
            )
        } catch (e: Exception) {
            null
        }
    }
}
