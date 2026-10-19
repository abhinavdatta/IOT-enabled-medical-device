package com.waterbench.controller

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * History screen: live event stream with level filter chips,
 * an EXPORT LOGS button, and a refresh note.
 */
class HistoryFragment : Fragment() {

    private lateinit var vm: MainViewModel
    private lateinit var dotMonitor: View
    private lateinit var tvMonitorStatus: TextView
    private lateinit var tvLastPacket: TextView
    private lateinit var tvRefreshNote: TextView
    private lateinit var tvEventStream: TextView
    private lateinit var btnClearLog: Button
    private lateinit var btnExportLogs: Button
    private lateinit var levelFilterBar: LinearLayout
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** Currently selected log level filter (null = all). */
    private var levelFilter: String? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.screen_history, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        vm = ViewModelProvider(requireActivity())[MainViewModel::class.java]

        dotMonitor = view.findViewById(R.id.dotMonitor)
        tvMonitorStatus = view.findViewById(R.id.tvMonitorStatus)
        tvLastPacket = view.findViewById(R.id.tvLastPacket)
        tvRefreshNote = view.findViewById(R.id.tvRefreshNote)
        tvEventStream = view.findViewById(R.id.tvEventStream)
        btnClearLog = view.findViewById(R.id.btnClearLog)
        btnExportLogs = view.findViewById(R.id.btnExportLogs)
        levelFilterBar = view.findViewById(R.id.levelFilterBar)

        // Build level filter chips.
        val levels = listOf(
            getString(R.string.level_all),
            getString(R.string.level_info),
            getString(R.string.level_warn),
            getString(R.string.level_fault),
            getString(R.string.level_state),
            getString(R.string.level_cmd),
            getString(R.string.level_done),
            getString(R.string.level_cal)
        )
        for (label in levels) {
            val chip = TextView(requireContext())
            chip.text = label
            chip.textSize = 12f
            chip.setPadding(12, 8, 12, 8)
            chip.setBackgroundResource(R.drawable.chip_bg)
            chip.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            chip.setOnClickListener {
                levelFilter = if (label == getString(R.string.level_all)) null else label
                refreshChipStates()
                renderEventStream()
            }
            levelFilterBar.addView(chip)
        }
        refreshChipStates()

        btnClearLog.setOnClickListener {
            EventLog.clear()
            renderEventStream()
        }

        btnExportLogs.setOnClickListener {
            val all = EventLog.snapshot
            val filtered = if (levelFilter == null) all else all.filter { it.contains("  $levelFilter  ") }
            val text = if (filtered.isEmpty()) "No log events to export." else filtered.joinToString("\n")
            val intent = android.content.Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_SUBJECT, "Triveni event log")
                putExtra(android.content.Intent.EXTRA_TEXT, text)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.export_logs)))
        }

        // Connection + last packet
        viewLifecycleOwner.lifecycleScope.launch {
            vm.connected.collect { c ->
                dotMonitor.setBackgroundResource(if (c) R.color.mint else R.color.red)
                tvMonitorStatus.text = if (c) "Connected · Device 1" else "Disconnected"
                tvRefreshNote.visibility = if (c) View.VISIBLE else View.GONE
                tvRefreshNote.text = if (c) "Live feed · auto-refreshes every second" else "Reconnect to refresh"
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            vm.status.collect { s ->
                if (s != null) {
                    tvLastPacket.text = "Last packet · " + timeFmt.format(Date(vm.lastPacketAt.value))
                }
            }
        }
        // Event stream
        viewLifecycleOwner.lifecycleScope.launch {
            vm.status.collect {
                renderEventStream()
            }
        }
    }

    private fun refreshChipStates() {
        val allLabel = getString(R.string.level_all)
        for (i in 0 until levelFilterBar.childCount) {
            val chip = levelFilterBar.getChildAt(i) as TextView
            val label = chip.text.toString()
            val selected = (label == allLabel && levelFilter == null) ||
                (label != allLabel && levelFilter == label)
            chip.setBackgroundResource(if (selected) R.drawable.card_bg_selected else R.drawable.chip_bg)
            chip.setTextColor(if (selected) ContextCompat.getColor(requireContext(), R.color.mint)
                            else ContextCompat.getColor(requireContext(), R.color.text_secondary))
        }
    }

    private fun renderEventStream() {
        val all = EventLog.snapshot
        val filtered = if (levelFilter == null) all else all.filter { it.contains("  $levelFilter  ") }
        tvEventStream.text = if (filtered.isEmpty()) "No events yet." else filtered.joinToString("\n")
    }

    companion object {
        fun newInstance() = HistoryFragment()
    }
}
