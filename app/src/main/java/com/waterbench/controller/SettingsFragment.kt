package com.waterbench.controller

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Settings hub: DEVICES (list + add new device), APPEARANCE (theme),
 * DATA & RECORDS (export), HARDWARE SAFETY notice.
 */
class SettingsFragment : Fragment() {

    private lateinit var vm: MainViewModel

    private lateinit var dotSettings: View
    private lateinit var tvSettingsDevice: TextView
    private lateinit var tvSettingsConn: TextView
    private lateinit var tvSettingsWifiName: TextView
    private lateinit var tvCurrentTheme: TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.screen_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        vm = ViewModelProvider(requireActivity())[MainViewModel::class.java]

        dotSettings = view.findViewById(R.id.dotSettings)
        tvSettingsDevice = view.findViewById(R.id.tvSettingsDevice)
        tvSettingsConn = view.findViewById(R.id.tvSettingsConn)
        tvSettingsWifiName = view.findViewById(R.id.tvSettingsWifiName)
        tvCurrentTheme = view.findViewById(R.id.tvCurrentTheme)

        // DEVICES card → Device Settings (rename Wi-Fi, etc.)
        val btnDeviceSettings = view.findViewById<Button>(R.id.btnDeviceSettings)
        btnDeviceSettings.setOnClickListener {
            (activity as? MainActivity)?.openDeviceSettings()
        }

        // Registered devices list with EDIT entries (PDF page 6).
        val container = view.findViewById<LinearLayout>(R.id.deviceListContainer)
        val tvRegistered = view.findViewById<TextView>(R.id.tvRegisteredCount)
        fun renderDeviceList() {
            container.removeAllViews()
            DeviceRegistry.devices.forEach { d ->
                val row = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setBackgroundResource(R.drawable.card_bg)
                    setPadding(28, 24, 28, 24)
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = 16 }
                    layoutParams = params
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                val col = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val name = TextView(requireContext()).apply {
                    text = d.displayName
                    textSize = 14f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(requireContext().getColor(R.color.text_primary))
                }
                val status = TextView(requireContext()).apply {
                    text = "${d.lastWifiName ?: d.baseUrl} · " +
                        if (d.id == DeviceRegistry.selectedId && vm.connected.value) "Connected" else "Not connected"
                    textSize = 12f
                    setTextColor(requireContext().getColor(R.color.text_secondary))
                }
                col.addView(name); col.addView(status)
                row.addView(col)
                val edit = Button(requireContext()).apply {
                    text = "EDIT"
                    setBackgroundResource(R.drawable.btn_secondary)
                    setTextColor(requireContext().getColor(R.color.text_primary))
                    textSize = 12f
                    setOnClickListener {
                        DeviceRegistry.select(requireContext(), d.id)
                        (activity as? MainActivity)?.openDeviceSettings()
                    }
                }
                row.addView(edit)
                container.addView(row)
            }
            tvRegistered.text = "Registered devices: ${DeviceRegistry.devices.size}"
        }
        renderDeviceList()

        // ADD NEW DEVICE → name dialog, registers in DeviceRegistry.
        val btnAddDevice = view.findViewById<Button>(R.id.btnAddDevice)
        btnAddDevice.setOnClickListener {
            val input = android.widget.EditText(requireContext()).apply { hint = "Device name" }
            android.app.AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.add_device))
                .setMessage("Enter a name for the new Triveni device.")
                .setView(input)
                .setPositiveButton("Add") { _, _ ->
                    val name = input.text?.toString()?.trim()
                    if (!name.isNullOrBlank()) {
                        DeviceRegistry.addDevice(requireContext(), name, Esp32Client.DEFAULT_BASE)
                        EventLog.log("INFO", "Added device · $name")
                        renderDeviceList()
                        Toast.makeText(requireContext(), getString(R.string.device_added), Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // APPEARANCE → Theme page
        val btnOpenTheme = view.findViewById<Button>(R.id.btnOpenTheme)
        btnOpenTheme.setOnClickListener {
            tvCurrentTheme.text = ThemeManager.current(requireContext())
            (activity as? MainActivity)?.openTheme()
        }

        // DATA & RECORDS → Export page
        val btnOpenExport = view.findViewById<Button>(R.id.btnOpenExport)
        btnOpenExport.setOnClickListener {
            (activity as? MainActivity)?.openExport()
        }

        // Connection dot + status
        viewLifecycleOwner.lifecycleScope.launch {
            vm.connected.collect { c ->
                dotSettings.setBackgroundResource(if (c) R.color.mint else R.color.red)
                tvSettingsConn.text = if (c) "Connected" else "Disconnected"
                tvSettingsConn.setTextColor(
                    requireContext().getColor(if (c) R.color.mint else R.color.red)
                )
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            vm.status.collect { s ->
                if (s != null) {
                    tvSettingsDevice.text = "Device 1 · ${s.wifiName}"
                    tvSettingsWifiName.text = s.wifiName
                }
            }
        }
    }
}
