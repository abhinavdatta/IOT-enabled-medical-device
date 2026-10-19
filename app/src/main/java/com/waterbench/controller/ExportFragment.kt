package com.waterbench.controller

import android.app.DatePickerDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import java.util.Locale

/**
 * Export data page (PDF page A).
 * Lets the user pick a device, date range, record categories and format,
 * then exports to CSV / JSON / PDF and offers to share the file.
 */
class ExportFragment : Fragment() {

    private lateinit var vm: MainViewModel

    private lateinit var spnDevice: Spinner
    private lateinit var btnDateFrom: Button
    private lateinit var btnDateTo: Button
    private lateinit var chkTelemetry: CheckBox
    private lateinit var chkSessions: CheckBox
    private lateinit var chkCalibrations: CheckBox
    private lateinit var chkAlarms: CheckBox
    private lateinit var rbCsv: RadioButton
    private lateinit var rbJson: RadioButton
    private lateinit var rbPdf: RadioButton
    private lateinit var btnExport: Button

    private var dateFrom: Long? = null
    private var dateTo: Long? = null
    private val dateFmt = SimpleDateFormat("MMM dd, yyyy", Locale.US)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.screen_export, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        vm = ViewModelProvider(requireActivity())[MainViewModel::class.java]

        spnDevice = view.findViewById(R.id.spnDevice)
        btnDateFrom = view.findViewById(R.id.btnDateFrom)
        btnDateTo = view.findViewById(R.id.btnDateTo)
        chkTelemetry = view.findViewById(R.id.chkTelemetry)
        chkSessions = view.findViewById(R.id.chkSessions)
        chkCalibrations = view.findViewById(R.id.chkCalibrations)
        chkAlarms = view.findViewById(R.id.chkAlarms)
        rbCsv = view.findViewById(R.id.rbCsv)
        rbJson = view.findViewById(R.id.rbJson)
        rbPdf = view.findViewById(R.id.rbPdf)
        btnExport = view.findViewById(R.id.btnExport)

        // Populate device spinner from DeviceRegistry.
        val deviceNames = mutableListOf<String>("All devices")
        DeviceRegistry.devices.forEach { deviceNames.add("${it.displayName} · ${it.lastWifiName ?: it.baseUrl}") }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, deviceNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spnDevice.adapter = adapter

        // Date pickers.
        val cal = Calendar.getInstance()
        btnDateFrom.setOnClickListener {
            DatePickerDialog(requireContext(), { _, year, month, day ->
                cal.set(year, month, day)
                dateFrom = cal.timeInMillis
                btnDateFrom.text = dateFmt.format(Date(cal.timeInMillis))
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }
        btnDateTo.setOnClickListener {
            DatePickerDialog(requireContext(), { _, year, month, day ->
                cal.set(year, month, day)
                dateTo = cal.timeInMillis + 86399000 // end of day
                btnDateTo.text = dateFmt.format(Date(cal.timeInMillis + 86399000))
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }

        btnExport.setOnClickListener {
            val selectedPos = spnDevice.selectedItemPosition
            val deviceId = if (selectedPos == 0) null else DeviceRegistry.devices[selectedPos - 1].id

            val categories = mutableSetOf<ExportUtils.Category>()
            if (chkTelemetry.isChecked) categories += ExportUtils.Category.TELEMETRY
            if (chkSessions.isChecked) categories += ExportUtils.Category.SESSIONS
            if (chkCalibrations.isChecked) categories += ExportUtils.Category.CALIBRATIONS
            if (chkAlarms.isChecked) categories += ExportUtils.Category.ALARMS

            val format = if (rbCsv.isChecked) ExportUtils.Format.CSV
            else if (rbJson.isChecked) ExportUtils.Format.JSON
            else ExportUtils.Format.PDF

            val req = ExportUtils.ExportRequest(
                deviceId = deviceId,
                from = dateFrom ?: 0,
                to = dateTo ?: Long.MAX_VALUE,
                categories = categories,
                format = format
            )

            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val file = withContext(Dispatchers.IO) {
                        ExportUtils.export(requireContext(), req)
                    }
                    requireActivity().runOnUiThread {
                        Toast.makeText(requireContext(),
                            getString(R.string.export_done, file.absolutePath), Toast.LENGTH_LONG).show()
                        val share = ExportUtils.shareFile(requireContext(), file)
                        startActivity(Intent.createChooser(share, getString(R.string.export_shared)))
                    }
                } catch (e: Exception) {
                    requireActivity().runOnUiThread {
                        Toast.makeText(requireContext(), "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    companion object {
        fun newInstance() = ExportFragment()
    }
}
