package com.waterbench.controller

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Local app-only event log. Nothing here is sent anywhere — it lives only
 * inside this app and is capped to keep memory usage tiny on low-end phones.
 */
object EventLog {
    private const val MAX_EVENTS = 200
    private val events = ArrayDeque<String>(MAX_EVENTS)
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    val snapshot: List<String> get() = synchronized(events) { events.toList() }

    fun log(kind: String, msg: String) = synchronized(events) {
        if (events.size >= MAX_EVENTS) events.removeFirst()
        events.addLast("${fmt.format(Date())}  $kind  $msg")
    }

    fun clear() = synchronized(events) { events.clear() }
}
