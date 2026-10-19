package com.waterbench.controller

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import java.util.Locale

class CalibrateFragment : Fragment() {

    private lateinit var vm: MainViewModel
    private lateinit var tvCalPwm: TextView
    private lateinit var seekCalPwm: SeekBar
    private lateinit var btnCalStart: Button
    private lateinit var btnCalStop: Button
    private lateinit var etCalVolume: EditText
    private lateinit var btnCalSave: Button
    private var currentPwm = 112

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.screen_calibrate, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        vm = ViewModelProvider(requireActivity())[MainViewModel::class.java]

        tvCalPwm = view.findViewById(R.id.tvCalPwm)
        seekCalPwm = view.findViewById(R.id.seekCalPwm)
        btnCalStart = view.findViewById(R.id.btnCalStart)
        btnCalStop = view.findViewById(R.id.btnCalStop)
        etCalVolume = view.findViewById(R.id.etCalVolume)
        btnCalSave = view.findViewById(R.id.btnCalSave)

        seekCalPwm.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                currentPwm = value + 55
                tvCalPwm.text = "$currentPwm"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        btnCalStart.setOnClickListener {
            EventLog.log("CAL", "Calibration started · PWM $currentPwm")
            vm.sendCommand("CAL $currentPwm", "CAL $currentPwm requested")
        }
        btnCalStop.setOnClickListener {
            EventLog.log("CAL", "Calibration stopped by user")
            vm.sendCommand("CAL_STOP", "CAL_STOP requested")
        }
        btnCalSave.setOnClickListener {
            val text = etCalVolume.text?.toString()?.trim() ?: ""
            val ml = text.toDoubleOrNull()
            if (ml == null || ml <= 0.0) {
                etCalVolume.error = "Enter a valid mL amount"
                return@setOnClickListener
            }
            EventLog.log("CAL", String.format(Locale.US, "Calibration saved · %.1f mL @ PWM %d", ml, currentPwm))
            vm.sendCommand("CAL_RESULT $ml", "CAL_RESULT $ml requested")

            // Record calibration locally (dropsPerMl corrected by next /api/status poll).
            val deviceId = DeviceRegistry.selected?.id ?: "device-1"
            val status = vm.status.value
            val flow = if (status != null) status.flowMlMin else 0.0
            DataRecorder.recordCalibration(deviceId, currentPwm, ml, 0.0, flow)
        }
    }
}
