package com.waterbench.controller

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.*

/**
 * Registry of known ESP32 devices. Supports multiple paired devices so the user
 * can switch between rigs (e.g. "Device 1 · WaterBench-ESP32", "Device 2").
 *
 * Each device keeps a display name, its HTTP base URL, its last-reported Wi-Fi
 * name, and whether it is currently connected. Selection is persisted in
 * SharedPreferences so the user's chosen device survives process death.
 *
 * Not a demo: every field is loaded/saved to disk; nothing is lost on restart.
 */
object DeviceRegistry {

    private const val PREF_NAME = "waterbench_devices"
    private const val KEY_DEVICES = "devices_json"
    private const val KEY_SELECTED = "selected_device_id"

    private val gson = Gson()

    /** All known devices. Never null; defaults to a single "Device 1". */
    val devices: List<DeviceEntry>
        get() = _devices

    private var _devices: List<DeviceEntry> = emptyList()

    /** Currently selected device id, or null if none. */
    var selectedId: String? = null
        private set

    /** The currently selected device entry, or null. */
    val selected: DeviceEntry? get() = _devices.find { it.id == selectedId }

    data class DeviceEntry(
        val id: String,
        val displayName: String,
        var baseUrl: String,
        var lastWifiName: String? = null,
        var connected: Boolean = false
    )

    /**
     * Loads the registry from disk. Call once in Application.onCreate or the
     * first time the Settings hub is opened.
     */
    fun load(context: Context) {
        val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_DEVICES, null)
        val sel = prefs.getString(KEY_SELECTED, null)

        val list = if (json.isNullOrBlank()) defaultDevices() else {
            val type = object : TypeToken<List<DeviceEntry>>() {}.type
            gson.fromJson(json, type) ?: defaultDevices()
        }
        _devices = list
        selectedId = sel?.takeIf { s -> list.any { it.id == s } }
        if (selectedId == null && list.isNotEmpty()) selectedId = list.first().id
        savePrefs(context)
    }

    /** Persists the current device list and selection to disk. */
    fun save(context: Context) {
        savePrefs(context)
    }

    private fun savePrefs(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_DEVICES, gson.toJson(_devices))
            .putString(KEY_SELECTED, selectedId)
            .apply()
    }

    /** Selects a device by id and persists the choice. */
    fun select(context: Context, deviceId: String) {
        require(_devices.any { it.id == deviceId }) { "Unknown device id: $deviceId" }
        selectedId = deviceId
        savePrefs(context)
    }

    /** Adds a new device and selects it. */
    fun addDevice(context: Context, displayName: String, baseUrl: String): DeviceEntry {
        val entry = DeviceEntry(
            id = java.util.UUID.randomUUID().toString(),
            displayName = displayName,
            baseUrl = baseUrl.trimEnd('/'),
            lastWifiName = null,
            connected = false
        )
        _devices = _devices + entry
        select(context, entry.id)
        return entry
    }

    /** Removes a device by id. Re-selects the first remaining device if needed. */
    fun removeDevice(context: Context, deviceId: String) {
        _devices = _devices.filter { it.id != deviceId }
        if (selectedId == deviceId) {
            selectedId = if (_devices.isNotEmpty()) _devices.first().id else null
        }
        savePrefs(context)
    }

    /** Updates the last-reported Wi-Fi name and connection flag for a device. */
    fun updateStatus(context: Context, deviceId: String, wifiName: String?, connected: Boolean) {
        val entry = _devices.find { it.id == deviceId } ?: return
        entry.lastWifiName = wifiName
        entry.connected = connected
        savePrefs(context)
    }

    /** Returns the effective base URL for the selected device, or the default. */
    fun selectedBaseUrl(): String = selected?.baseUrl ?: Esp32Client.DEFAULT_BASE

    private fun defaultDevices(): List<DeviceEntry> {
        return listOf(
            DeviceEntry(
                id = "device-1",
                displayName = "Device 1",
                baseUrl = Esp32Client.DEFAULT_BASE,
                lastWifiName = "WaterBench-ESP32",
                connected = false
            )
        )
    }
}
