package com.waterbench.controller

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.ViewModelProvider

/**
 * Hosts the four main fragments (Home / Calibration / History / Settings)
 * plus the Device-Settings screen reachable from the Settings hub.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var vm: MainViewModel
    private lateinit var fragmentContainer: FragmentContainerView
    private lateinit var navButtons: List<View>

    private var current: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply the persisted style before inflating any views. Applying it from
        // the theme fragment's context only themes that context, not this Activity.
        ThemeManager.applySaved(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        vm = ViewModelProvider(this)[MainViewModel::class.java]
        DataRecorder.load(this)
        DeviceRegistry.load(this)
        fragmentContainer = findViewById(R.id.fragmentContainer)

        navButtons = listOf(
            findViewById(R.id.navHome), findViewById(R.id.navCalibrate),
            findViewById(R.id.navHistory), findViewById(R.id.navSettings)
        )
        navButtons.forEachIndexed { i, b -> b.setOnClickListener { switchScreen(i) } }

        if (savedInstanceState == null) switchScreen(0, force = true)
    }

    override fun onResume() {
        super.onResume()
        vm.startPolling()
    }

    override fun onPause() {
        super.onPause()
        vm.stopPolling()
    }

    fun switchScreen(index: Int, force: Boolean = false) {
        if (index == current && !force) return
        current = index
        val f: Fragment = when (index) {
            1 -> CalibrateFragment()
            2 -> HistoryFragment()
            3 -> SettingsFragment()
            4 -> DeviceSettingsFragment()
            5 -> ThemeFragment()
            6 -> ExportFragment()
            else -> HomeFragment()
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, f)
            .commitAllowingStateLoss()

        if (index != 4 && index != 5 && index != 6) {
            navButtons.forEachIndexed { i, b ->
                b.setBackgroundResource(if (i == index) R.drawable.nav_item_selected
                                       else android.R.color.transparent)
            }
        }
    }

    fun openDeviceSettings() {
        navButtons.forEach { it.setBackgroundResource(android.R.color.transparent) }
        switchScreen(4)
    }

    fun openTheme() {
        navButtons.forEach { it.setBackgroundResource(android.R.color.transparent) }
        switchScreen(5)
    }

    fun openExport() {
        navButtons.forEach { it.setBackgroundResource(android.R.color.transparent) }
        switchScreen(6)
    }

    // Handle back press: if we're in Device Settings, Theme, or Export, go back to Settings hub.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (current == 4 || current == 5 || current == 6) {
            switchScreen(3)
        } else {
            super.onBackPressed()
        }
    }
}
