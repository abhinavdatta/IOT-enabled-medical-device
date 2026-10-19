package com.waterbench.controller

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.*

/**
 * Persists local telemetry samples, dose sessions, calibration results and alarm
 * events so they can be reviewed on History and exported via ExportUtils.
 *
 * Everything lives in SharedPreferences (no external DB, no network). Caps are
 * enforced so memory stays tiny on low-end phones.
 */
object DataRecorder {

    private const val PREF_NAME = "waterbench_data"
    private const val KEY_TELEMETRY = "telemetry_json"
    private const val KEY_SESSIONS = "sessions_json"
    private const val KEY_CALIBRATIONS = "calibrations_json"
    private const val KEY_ALARMS = "alarms_json"

    private const val MAX_TELEMETRY = 500
    private const val MAX_SESSIONS = 200
    private const val MAX_CALIBRATIONS = 100
    private const val MAX_ALARMS = 200

    private val gson = Gson()

    data class TelemetrySample(
        val ts: Long,
        val deviceId: String,
        val state: String,
        val fault: String,
        val flowMlMin: Double,
        val dropsPerSecond: Double,
        val pressureKpa: Double,
        val currentMa: Int,
        val pwm: Int
    )

    data class Session(
        val id: String,
        val deviceId: String,
        val startedAt: Long,
        val endedAt: Long? = null,
        val targetDrops: Int = 0,
        val dropsDelivered: Int = 0,
        val targetFlowMlMin: Double = 0.0,
        val avgFlowMlMin: Double = 0.0,
        val result: String = "" // "COMPLETE", "STOPPED", "FAULT", etc.
    )

    data class Calibration(
        val id: String,
        val deviceId: String,
        val ts: Long,
        val pwm: Int,
        val volumeMl: Double,
        val dropsPerMl: Double,
        val flowMlMin: Double
    )

    data class AlarmEvent(
        val id: String,
        val deviceId: String,
        val ts: Long,
        val fault: String,
        val state: String,
        val acknowledged: Boolean = false
    )

    private var telemetry: MutableList<TelemetrySample> = mutableListOf()
    private var sessions: MutableList<Session> = mutableListOf()
    private var calibrations: MutableList<Calibration> = mutableListOf()
    private var alarms: MutableList<AlarmEvent> = mutableListOf()

    fun load(context: Context) {
        val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        telemetry = listOf(prefs.getString(KEY_TELEMETRY, null)).filterNotNull()
            .flatMap { gson.fromJson(it, object : TypeToken<List<TelemetrySample>>() {}.type) as? List<*> ?: emptyList() }
            .filterIsInstance<TelemetrySample>().toMutableList()
        sessions = loadList(prefs, KEY_SESSIONS, Session::class.java)
        calibrations = loadList(prefs, KEY_CALIBRATIONS, Calibration::class.java)
        alarms = loadList(prefs, KEY_ALARMS, AlarmEvent::class.java)
    }

    private fun <T> loadList(prefs: SharedPreferences, key: String, clazz: Class<T>): MutableList<T> {
        val json = prefs.getString(key, null) ?: return mutableListOf()
        val type = object : TypeToken<List<T>>() {}.type
        return (gson.fromJson(json, type) as? List<T> ?: emptyList()).toMutableList()
    }

    fun save(context: Context) {
        val editor: SharedPreferences.Editor = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
        editor.putString(KEY_TELEMETRY, gson.toJson(telemetry))
        editor.putString(KEY_SESSIONS, gson.toJson(sessions))
        editor.putString(KEY_CALIBRATIONS, gson.toJson(calibrations))
        editor.putString(KEY_ALARMS, gson.toJson(alarms))
        editor.apply()
    }

    /** Records a live telemetry sample from /api/status. */
    fun recordTelemetry(deviceId: String, s: DeviceStatus) {
        telemetry.add(TelemetrySample(
            ts = System.currentTimeMillis(),
            deviceId = deviceId,
            state = s.state,
            fault = s.fault,
            flowMlMin = s.flowMlMin,
            dropsPerSecond = s.dropsPerSecond,
            pressureKpa = s.pressureKpa,
            currentMa = s.currentMa,
            pwm = s.pwm
        ))
        if (telemetry.size > MAX_TELEMETRY) telemetry = telemetry.subList(telemetry.size - MAX_TELEMETRY, telemetry.size)
    }

    /** Starts a new dose session. */
    fun startSession(deviceId: String, targetDrops: Int, targetFlowMlMin: Double): String {
        val id = UUID.randomUUID().toString()
        sessions.add(Session(
            id = id,
            deviceId = deviceId,
            startedAt = System.currentTimeMillis(),
            targetDrops = targetDrops,
            targetFlowMlMin = targetFlowMlMin,
            result = "RUNNING"
        ))
        cap(sessions, MAX_SESSIONS)
        return id
    }

    /** Ends the most recent RUNNING session for a device. */
    fun endSession(deviceId: String, dropsDelivered: Int, avgFlowMlMin: Double, result: String) {
        val idx = sessions.indices.reversed().firstOrNull { sessions[it].deviceId == deviceId && sessions[it].result == "RUNNING" }
            ?: return
        sessions[idx] = sessions[idx].copy(
            endedAt = System.currentTimeMillis(),
            dropsDelivered = dropsDelivered,
            avgFlowMlMin = avgFlowMlMin,
            result = result
        )
    }

    fun recordCalibration(deviceId: String, pwm: Int, volumeMl: Double, dropsPerMl: Double, flowMlMin: Double) {
        calibrations.add(Calibration(
            id = UUID.randomUUID().toString(),
            deviceId = deviceId,
            ts = System.currentTimeMillis(),
            pwm = pwm,
            volumeMl = volumeMl,
            dropsPerMl = dropsPerMl,
            flowMlMin = flowMlMin
        ))
        cap(calibrations, MAX_CALIBRATIONS)
    }

    fun recordAlarm(deviceId: String, fault: String, state: String): String {
        val id = UUID.randomUUID().toString()
        alarms.add(AlarmEvent(
            id = id,
            deviceId = deviceId,
            ts = System.currentTimeMillis(),
            fault = fault,
            state = state,
            acknowledged = false
        ))
        cap(alarms, MAX_ALARMS)
        return id
    }

    fun acknowledgeAlarm(alarmId: String) {
        val idx = alarms.indexOfFirst { it.id == alarmId }
        if (idx >= 0) alarms[idx] = alarms[idx].copy(acknowledged = true)
    }

    fun allTelemetry(): List<TelemetrySample> = telemetry.toList()
    fun allSessions(): List<Session> = sessions.toList()
    fun allCalibrations(): List<Calibration> = calibrations.toList()
    fun allAlarms(): List<AlarmEvent> = alarms.toList()

    fun telemetryForDevice(deviceId: String): List<TelemetrySample> =
        telemetry.filter { it.deviceId == deviceId }

    fun sessionsForDevice(deviceId: String): List<Session> =
        sessions.filter { it.deviceId == deviceId }

    fun alarmsForDevice(deviceId: String): List<AlarmEvent> =
        alarms.filter { it.deviceId == deviceId }

    private fun <T> cap(list: MutableList<T>, max: Int) {
        while (list.size > max) {
            list.removeAt(0)
        }
    }

    fun clearAll(context: Context) {
        telemetry.clear(); sessions.clear(); calibrations.clear(); alarms.clear()
        save(context)
    }
}
