package com.waterbench.controller

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.launch

class DeviceSettingsFragment : Fragment() {

    private lateinit var vm: MainViewModel
    private lateinit var tvDeviceWifiName: TextView
    private lateinit var etWifiName: EditText
    private lateinit var btnApplyRename: Button
    private lateinit var cardRenameStatus: LinearLayout
    private lateinit var tvRenameStatus: TextView
    private var pendingRename: String? = null
    private var renameAppliedAt = 0L

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.screen_device_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        vm = ViewModelProvider(requireActivity())[MainViewModel::class.java]

        tvDeviceWifiName = view.findViewById(R.id.tvDeviceWifiName)
        etWifiName = view.findViewById(R.id.etWifiName)
        btnApplyRename = view.findViewById(R.id.btnApplyRename)
        cardRenameStatus = view.findViewById(R.id.cardRenameStatus)
        tvRenameStatus = view.findViewById(R.id.tvRenameStatus)

        btnApplyRename.setOnClickListener { proposeRename() }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.status.collect { s ->
                if (s != null) {
                    tvDeviceWifiName.text = s.wifiName
                    // Rename is only "successful" once a later valid status reports the new wifiName.
                    val want = pendingRename
                    if (want != null && s.wifiName == want) {
                        EventLog.log("INFO", "Wi-Fi rename confirmed · ${s.wifiName}")
                        pendingRename = null
                        etWifiName.setText("")
                        cardRenameStatus.visibility = View.VISIBLE
                        tvRenameStatus.text =
                            "Network renamed to $want. Join the new network in your phone's Wi-Fi settings."
                    }
                }
            }
        }
    }

    private fun proposeRename() {
        val name = etWifiName.text?.toString()?.trim() ?: ""
        if (!validateWifiName(name)) {
            etWifiName.error =
                "Use 1–31 chars: letters, numbers, hyphens, underscores. No spaces or other punctuation."
            return
        }
        val cur = vm.status.value
        if (cur == null) {
            etWifiName.error = "Connect to the ESP32 before renaming."
            return
        }
        if (cur.state != "IDLE" && !(cur.state == "PAUSED" && cur.fault == "NONE")) {
            etWifiName.error = "Rename is only accepted while IDLE (or safely PAUSED with no alarm)."
            return
        }

        // Confirmation dialog before sending.
        AlertDialog.Builder(requireContext())
            .setTitle("Apply and restart Wi-Fi?")
            .setMessage("The ESP32 will restart its Wi-Fi network as \"$name\". " +
                "The direct link will drop. Join \"$name\" in your phone's Wi-Fi settings, then return and check the connection.")
            .setPositiveButton("Apply") { _, _ ->
                EventLog.log("CMD", "Wi-Fi rename requested · $name")
                vm.sendCommand("SET_WIFI_NAME $name", "SET_WIFI_NAME $name requested")
                pendingRename = name
                renameAppliedAt = System.currentTimeMillis()
                cardRenameStatus.visibility = View.VISIBLE
                tvRenameStatus.text =
                    "The ESP32 is restarting its Wi-Fi. Join the new network name in your phone's Wi-Fi settings, then return here and check the connection."
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun validateWifiName(name: String): Boolean {
        if (name.isEmpty() || name.length > 31) return false
        // Allowed: letters, numbers, hyphens, underscores.
        name.forEach { c ->
            val ok = c.isLetterOrDigit() || c == '-' || c == '_'
            if (!ok) return false
        }
        return true
    }
}
