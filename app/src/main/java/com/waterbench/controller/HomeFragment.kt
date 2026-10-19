package com.waterbench.controller

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle.State.STARTED
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private lateinit var vm: MainViewModel
    private lateinit var dotConn: View
    private lateinit var tvConnStatus: TextView
    private lateinit var spnDevice: android.widget.Spinner
    private lateinit var btnAddDevice: Button
    private lateinit var chipState: TextView
    private lateinit var tvDropsDelivered: TextView
    private lateinit var tvDropsLeft: TextView
    private lateinit var progressDrops: ProgressBar
    private lateinit var tvElapsed: TextView
    private lateinit var tvMotorPct: TextView
    private lateinit var tvMotorVoltEst: TextView
    private lateinit var tvBusV: TextView
    private lateinit var tvCurrentMa: TextView
    private lateinit var tvPressure: TextView
    private lateinit var tvFlow: TextView
    private lateinit var tvDropsPerSec: TextView
    private lateinit var tvWifiName: TextView
    private lateinit var bannerAlarm: TextView
    private lateinit var btnStart: Button
    private lateinit var btnPause: Button
    private lateinit var btnStop: Button
    private lateinit var btnResume: Button
    private lateinit var btnAck: Button
    private lateinit var etFlow: EditText
    private lateinit var btnSetFlow: Button

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.screen_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        vm = ViewModelProvider(requireActivity())[MainViewModel::class.java]

        dotConn = view.findViewById(R.id.dotConn)
        tvConnStatus = view.findViewById(R.id.tvConnStatus)
        spnDevice = view.findViewById(R.id.spnDevice)
        btnAddDevice = view.findViewById(R.id.btnAddDevice)

        // Populate device selector dropdown from DeviceRegistry.
        val deviceNames = mutableListOf<String>()
        DeviceRegistry.devices.forEach { deviceNames.add("${it.displayName} · ${it.lastWifiName ?: it.baseUrl}") }
        if (deviceNames.isEmpty()) deviceNames.add("No devices")
        val deviceAdapter = android.widget.ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, deviceNames)
        deviceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spnDevice.adapter = deviceAdapter
        DeviceRegistry.devices.indexOfFirst { it.id == DeviceRegistry.selectedId }
            .takeIf { it >= 0 }?.let { spnDevice.setSelection(it) }
        chipState = view.findViewById(R.id.chipState)
        tvDropsDelivered = view.findViewById(R.id.tvDropsDelivered)
        tvDropsLeft = view.findViewById(R.id.tvDropsLeft)
        progressDrops = view.findViewById(R.id.progressDrops)
        tvElapsed = view.findViewById(R.id.tvElapsed)
        tvMotorPct = view.findViewById(R.id.tvMotorPct)
        tvMotorVoltEst = view.findViewById(R.id.tvMotorVoltEst)
        tvBusV = view.findViewById(R.id.tvBusV)
        tvCurrentMa = view.findViewById(R.id.tvCurrentMa)
        tvPressure = view.findViewById(R.id.tvPressure)
        tvFlow = view.findViewById(R.id.tvFlow)
        tvDropsPerSec = view.findViewById(R.id.tvDropsPerSec)
        tvWifiName = view.findViewById(R.id.tvWifiName2)
        bannerAlarm = view.findViewById(R.id.bannerAlarm)
        btnStart = view.findViewById(R.id.btnStart)
        btnPause = view.findViewById(R.id.btnPause)
        btnStop = view.findViewById(R.id.btnStop)
        btnResume = view.findViewById(R.id.btnResume)
        btnAck = view.findViewById(R.id.btnAck)
        etFlow = view.findViewById(R.id.etFlow)
        btnSetFlow = view.findViewById(R.id.btnSetFlow)

        btnStart.setOnClickListener { vm.sendCommand("START", "START requested") }
        btnPause.setOnClickListener { vm.sendCommand("PAUSE", "PAUSE requested") }
        btnStop.setOnClickListener { vm.sendCommand("STOP", "STOP requested") }
        btnResume.setOnClickListener { vm.sendCommand("RESUME", "RESUME requested") }
        btnAck.setOnClickListener { vm.sendCommand("ACK_ALARM", "ACK_ALARM requested") }

        btnAddDevice.setOnClickListener {
            val dlg = android.app.AlertDialog.Builder(requireContext())
            dlg.setTitle("Add new device")
            dlg.setMessage("Enter a name for the new WaterBench device.")
            val input = android.widget.EditText(requireContext())
            input.hint = "Device name"
            dlg.setView(input)
            dlg.setPositiveButton("Add") { _, _ ->
                val name = input.text?.toString()?.trim()
                if (name.isNullOrBlank()) {
                    input.error = "Enter a device name"
                } else {
                    DeviceRegistry.addDevice(requireContext(), name, Esp32Client.DEFAULT_BASE)
                    // Refresh spinner
                    val names = mutableListOf<String>()
                    DeviceRegistry.devices.forEach { names.add("${it.displayName} · ${it.lastWifiName ?: it.baseUrl}") }
                    @Suppress("UNCHECKED_CAST")
                    val adapter = spnDevice.adapter as android.widget.ArrayAdapter<String>
                    adapter.clear()
                    adapter.addAll(names)
                    adapter.notifyDataSetChanged()
                    EventLog.log("INFO", "Added device · $name")
                }
            }
            dlg.setNegativeButton("Cancel", null)
            dlg.show()
        }

        btnSetFlow.setOnClickListener {
            val v = etFlow.text?.toString()?.trim()?.toDoubleOrNull()
            if (v == null || v <= 0.0 || v > 5.0) {
                etFlow.error = "Enter a flow between 0.1 and 5.0 mL/min"
                return@setOnClickListener
            }
            vm.sendCommand("SET_FLOW $v", String.format(java.util.Locale.US, "SET_FLOW %.3f requested", v))
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.status.collect { s -> if (s != null) render(s) }
                }
                launch {
                    vm.connected.collect { c -> renderConn(c) }
                }
                launch {
                    vm.elapsedSeconds.collect { sec ->
                        val h = sec / 3600
                        val m = (sec % 3600) / 60
                        val s = sec % 60
                        tvElapsed.text = String.format("%02d:%02d:%02d", h, m, s)
                    }
                }
            }
        }
    }

    private fun renderConn(c: Boolean) {
        if (c) {
            dotConn.setBackgroundResource(R.color.mint)
            tvConnStatus.text = "ESP32 CONNECTED"
            tvConnStatus.setTextColor(requireContext().getColor(R.color.mint))
        } else {
            dotConn.setBackgroundResource(R.color.red)
            tvConnStatus.text = "ESP32 DISCONNECTED"
            tvConnStatus.setTextColor(requireContext().getColor(R.color.red))
        }
    }

    private fun render(s: DeviceStatus) {
        chipState.text = when (s.state) {
            "IDLE" -> "IDLE"
            "CALIBRATING" -> "CALIBRATING"
            "RUNNING" -> "RUNNING"
            "PAUSED" -> "PAUSED"
            "COMPLETE" -> "COMPLETE"
            "OCCLUSION_ALARM" -> "OCCLUSION ALARM"
            "FAULT" -> "FAULT"
            else -> s.state
        }
        tvDropsDelivered.text = "${s.dropsDelivered}"
        tvDropsLeft.text = "${s.dropsRemaining} left"
        progressDrops.max = s.targetDrops * 100
        progressDrops.progress =
            if (s.targetDrops > 0) (s.dropsDelivered * 100 / s.targetDrops) else 0

        tvMotorPct.text = "${s.motorPercent}%"
        tvMotorVoltEst.text = String.format("%.2f V est.", s.motorVoltageEstimateV)
        tvBusV.text = String.format("%.2f V", s.busVoltageV)
        tvCurrentMa.text = "${s.currentMa} mA"
        tvPressure.text = String.format("%.2f kPa", s.pressureKpa)
        tvFlow.text = String.format("%.3f mL/min", s.flowMlMin)
        tvDropsPerSec.text = String.format("%.3f drops/s", s.dropsPerSecond)
        tvWifiName.text = s.wifiName

        // Alarm banner: only the ESP32 status clears it (fault NONE or no fault).
        if (s.fault != "NONE" || s.state == "OCCLUSION_ALARM") {
            bannerAlarm.text = faultMessage(s.fault, s.state)
            bannerAlarm.visibility = View.VISIBLE
        } else bannerAlarm.visibility = View.GONE

        // Sensor-missing notices take priority for banner text if sensors are absent.
        if (!s.pressureSensorPresent) {
            bannerAlarm.text = "Pressure sensor missing — pump start is locked for safety."
            bannerAlarm.visibility = View.VISIBLE
        }
        if (!s.currentMonitorPresent) {
            bannerAlarm.text = "Current monitor missing."
            bannerAlarm.visibility = View.VISIBLE
        }
        if (s.physicalStopPressed) {
            bannerAlarm.text = "PHYSICAL STOP button pressed — pump is off."
            bannerAlarm.visibility = View.VISIBLE
        }

        btnStart.isEnabled = (s.state == "IDLE" || s.state == "PAUSED") && s.fault == "NONE"
        btnPause.isEnabled = (s.state == "RUNNING" || s.state == "CALIBRATING")
        btnStop.isEnabled = true
        btnResume.isEnabled = s.state == "PAUSED" && s.fault == "NONE"
        btnAck.isEnabled = (s.state == "COMPLETE" || s.state == "FAULT" || s.state == "OCCLUSION_ALARM")
    }

    private fun faultMessage(fault: String, state: String): String = when {
        state == "OCCLUSION_ALARM" || fault == "OCCLUSION" ->
            "Occlusion alarm — pump is off. Check the tube before acknowledging."
        fault == "PHYSICAL_STOP" ->
            "Physical STOP button pressed — pump is off."
        fault == "NO_FLOW_OR_AIR" ->
            "No drops detected — check the water supply, tube, pump, and drop-sensor alignment."
        fault == "PRESSURE_SENSOR" ->
            "Pressure sensor missing or not responding — pump start is locked for safety."
        fault == "IR_SENSOR" ->
            "Drop sensor problem — inspect sensor alignment and wiring."
        else -> ""
    }
}
