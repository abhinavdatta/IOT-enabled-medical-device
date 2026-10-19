package com.waterbench.controller

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    // ---- Connection / status state ------------------------------------------
    private val _status = MutableStateFlow<DeviceStatus?>(null)
    val status: StateFlow<DeviceStatus?> = _status

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val _lastPacketAt = MutableStateFlow(0L)
    val lastPacketAt: StateFlow<Long> = _lastPacketAt

    private var pollJob: Job? = null
    private var wasConnected = false

    // ---- Session timer ------------------------------------------------------
    private val _elapsedSeconds = MutableStateFlow(0L)
    val elapsedSeconds: StateFlow<Long> = _elapsedSeconds
    private var timerJob: Job? = null

    // ---- Recording ------------------------------------------------------------
    private var lastRecordedStatus: DeviceStatus? = null
    private var currentSessionId: String? = null
    private var sessionDropsAtStart = 0
    private var sessionFlowSum = 0.0
    private var sessionFlowCount = 0

    private val _currentDeviceId = MutableStateFlow(DeviceRegistry.selected?.id ?: "device-1")
    val currentDeviceId: StateFlow<String> = _currentDeviceId

    /** Telemetry + sessions + alarms: recorded live from each /api/status packet. */
    private fun recordFromStatus(s: DeviceStatus) {
        val deviceId = _currentDeviceId.value
        DataRecorder.recordTelemetry(deviceId, s)

        // Start a session on first RUNNING packet.
        if (s.state == "RUNNING" && lastRecordedStatus?.state != "RUNNING") {
            currentSessionId = DataRecorder.startSession(deviceId, s.targetDrops, s.targetFlowMlMin)
            sessionDropsAtStart = s.dropsDelivered
            sessionFlowSum = 0.0
            sessionFlowCount = 0
        }

        // End the session when leaving RUNNING.
        if (lastRecordedStatus?.state == "RUNNING" && s.state != "RUNNING") {
            val drops = s.dropsDelivered - sessionDropsAtStart
            val avgFlow = if (sessionFlowCount > 0) sessionFlowSum / sessionFlowCount else 0.0
            DataRecorder.endSession(deviceId, drops, avgFlow, if (s.state == "COMPLETE") "COMPLETE" else "STOPPED")
            currentSessionId = null
        }

        // Accumulate flow for the running session average.
        if (s.state == "RUNNING") {
            sessionFlowSum += s.flowMlMin
            sessionFlowCount++
        }

        // Record faults as alarms.
        if (s.fault != "NONE" && (lastRecordedStatus?.fault != s.fault || lastRecordedStatus?.state != s.state)) {
            DataRecorder.recordAlarm(deviceId, s.fault, s.state)
        }

        lastRecordedStatus = s
    }

    init {
        startPolling()
    }

    /** Poll GET /api/status once per second while the app is in the foreground. */
    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            var failures = 0
            while (isActive) {
                val raw = Esp32Client.getStatus()
                if (raw != null) {
                    val parsed = DeviceStatus.fromJson(raw)
                    if (parsed != null) {
                        onValidStatus(parsed)
                        failures = 0
                    } else {
                        // Malformed JSON — do NOT treat as a valid packet.
                        failures++
                    }
                } else {
                    failures++
                }
                if (failures >= 3 && _connected.value) {
                    markDisconnected()
                }
                delay(1000L)
            }
        }
    }

    /** Stop polling while app is in the background or closed. */
    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
        timerJob?.cancel()
        if (_connected.value) markDisconnected()
    }

    private fun onValidStatus(s: DeviceStatus) {
        val previously = _status.value
        val justConnected = !_connected.value
        _status.value = s
        _lastPacketAt.value = System.currentTimeMillis()
        if (justConnected) {
            _connected.value = true
            EventLog.log("INFO", "App connected to ESP32 at ${Esp32Client.baseUrl.value}")
        }
        if (previously?.state != s.state) {
            EventLog.log("STATE", "${previously?.state ?: "—"} → ${s.state}")
        }
        if (previously?.fault != s.fault && s.fault != "NONE") {
            EventLog.log("FAULT", "Fault received: ${s.fault}")
        }
        // Reset visible session timer at a newly started session.
        if (s.state == "RUNNING" && previously?.state != "RUNNING") {
            restartTimer()
        }
        if (s.state == "COMPLETE" && previously?.state != "COMPLETE") {
            EventLog.log("DONE", "Completed dose — ${s.targetDrops} drops detected.")
        }
        recordFromStatus(s)
    }

    private fun markDisconnected() {
        _connected.value = false
        EventLog.log("INFO", "App disconnected. ESP32 continues its local safety logic.")
    }

    private fun restartTimer() {
        timerJob?.cancel()
        _elapsedSeconds.value = 0L
        timerJob = viewModelScope.launch {
            while (isActive && _status.value?.state == "RUNNING") {
                delay(1000L)
                _elapsedSeconds.value += 1
            }
        }
    }

    // ---- Commands ------------------------------------------------------------

    /** Sends a command. Never assumes success — the next /api/status is the truth. */
    fun sendCommand(command: String, logLabel: String? = null) {
        viewModelScope.launch {
            val ok = Esp32Client.sendCommand(command) != null
            EventLog.log("CMD", "${logLabel ?: command} requested${if (ok) "" else " (no response)"}")
        }
    }
}
