package com.waterbench.controller

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * HTTP client for the direct local ESP32 link. HTTP only, no cloud.
 * Default base URL: http://192.168.4.1
 */
object Esp32Client {

    const val DEFAULT_BASE = "http://192.168.4.1"
    const val DEFAULT_WIFI_NAME = "WaterBench-ESP32"
    const val DEFAULT_WIFI_PASSWORD_HINT = "WaterBench2026"

    private val _baseUrl = MutableStateFlow(DEFAULT_BASE)
    val baseUrl: StateFlow<String> = _baseUrl

    @Volatile var connectTimeoutMs: Int = 2500
    @Volatile var readTimeoutMs: Int = 2500

    fun setBaseUrl(url: String) { _baseUrl.value = url.trimEnd('/') }

    /** GET /api/status — returns raw JSON string or null on failure. */
    suspend fun getStatus(): String? = request("GET", "/api/status", null)

    /** POST /api/command — body is either form-encoded or JSON. */
    suspend fun sendCommand(command: String): String? {
        val body = "command=" + java.net.URLEncoder.encode(command, "UTF-8")
        return request("POST", "/api/command", body)
    }

    private suspend fun request(method: String, path: String, body: String?): String? =
        withContext(Dispatchers.IO) {
            try {
                val url = URL(_baseUrl.value + path)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = method
                conn.connectTimeout = connectTimeoutMs
                conn.readTimeout = readTimeoutMs
                conn.setRequestProperty("Accept", "application/json")
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = conn.responseCode
                if (code in 200..299) {
                    conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                } else null
            } catch (e: Exception) {
                null
            }
        }
}
