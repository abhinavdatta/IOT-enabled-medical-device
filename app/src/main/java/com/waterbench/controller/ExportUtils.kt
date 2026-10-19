package com.waterbench.controller

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import com.google.gson.Gson
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.Locale

/**
 * Exports recorded telemetry, sessions, calibrations and alarms to CSV, JSON
 * or PDF. Files are written to the app's external files directory (or
 * Downloads fallback) and exposed via FileProvider so the user can open/share
 * them. No network is involved; nothing leaves the device unless the user
 * explicitly shares the produced file.
 */
object ExportUtils {

    private const val FILE_PROVIDER_AUTHORITY = "com.waterbench.controller.fileprovider"
    private val gson = Gson()

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val fileDateFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    /** All record categories the user can include in an export (PDF page A). */
    enum class Category(val label: String) {
        TELEMETRY("Telemetry"),
        SESSIONS("Sessions"),
        CALIBRATIONS("Calibration"),
        ALARMS("Alarms")
    }

    /** Export format. */
    enum class Format(val mime: String, val ext: String) {
        CSV("text/csv", "csv"),
        JSON("application/json", "json"),
        PDF("application/pdf", "pdf")
    }

    data class ExportRequest(
        val deviceId: String?, // null = all devices
        val from: Long = 0, // inclusive ms, 0 = no lower bound
        val to: Long = Long.MAX_VALUE, // inclusive ms
        val categories: Set<Category> = Category.values().toSet(),
        val format: Format = Format.CSV
    )

    /**
     * Runs the export synchronously on the caller's thread (call from a
     * coroutine / worker). Returns the exported File.
     */
    fun export(context: Context, req: ExportRequest): File {
        val dir = outputDir(context)
        val base = "waterbench_export_${fileDateFmt.format(Date())}"
        return when (req.format) {
            Format.CSV -> exportCsv(context, dir, base, req)
            Format.JSON -> exportJson(context, dir, base, req)
            Format.PDF -> exportPdf(context, dir, base, req)
        }
    }

    /** Shares the exported file with the user via an ACTION_SEND intent. */
    fun shareFile(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, file)
        return Intent(Intent.ACTION_SEND).apply {
            type = when (file.extension) {
                "csv" -> "text/csv"
                "json" -> "application/json"
                else -> "application/pdf"
            }
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_SUBJECT, "Triveni export ${file.nameWithoutExtension}")
        }
    }

    // ---------------- CSV ----------------

    private fun exportCsv(context: Context, dir: File, base: String, req: ExportRequest): File {
        val file = File(dir, "$base.csv")
        OutputStreamWriter(FileOutputStream(file), "UTF-8").use { out ->
            out.write("Triveni Export,${dateFmt.format(Date())}\n")
            out.write("Device filter,${csvCell(req.deviceId ?: "All devices")}\n")
            out.write("Date range,${dateFmt.format(Date(req.from))} to ${dateFmt.format(Date(req.to))}\n\n")

            if (Category.TELEMETRY in req.categories) {
                writeSection(out, "Telemetry")
                out.write("Timestamp,Device,State,Fault,Flow mL/min,Drops/s,Pressure kPa,Current mA,PWM\n")
                DataRecorder.allTelemetry()
                    .filter { matches(req, it) }
                    .forEach { t ->
                        out.write("${dateFmt.format(Date(t.ts))},${csvCell(t.deviceId)},${csvCell(t.state)},${csvCell(t.fault)},${t.flowMlMin},${t.dropsPerSecond},${t.pressureKpa},${t.currentMa},${t.pwm}\n")
                    }
                out.write("\n")
            }
            if (Category.SESSIONS in req.categories) {
                writeSection(out, "Sessions")
                out.write("Session Id,Device,Started,Ended,Target Drops,Drops Delivered,Target Flow mL/min,Avg Flow mL/min,Result\n")
                DataRecorder.allSessions()
                    .filter { matches(req, it) }
                    .forEach { s ->
                        out.write("${csvCell(s.id)},${csvCell(s.deviceId)},${dateFmt.format(Date(s.startedAt))},${s.endedAt?.let { dateFmt.format(Date(it)) } ?: ""},${s.targetDrops},${s.dropsDelivered},${s.targetFlowMlMin},${s.avgFlowMlMin},${csvCell(s.result)}\n")
                    }
                out.write("\n")
            }
            if (Category.CALIBRATIONS in req.categories) {
                writeSection(out, "Calibrations")
                out.write("Calibration Id,Device,Timestamp,PWM,Volume mL,Drops/mL,Flow mL/min\n")
                DataRecorder.allCalibrations()
                    .filter { matches(req, it) }
                    .forEach { c ->
                        out.write("${csvCell(c.id)},${csvCell(c.deviceId)},${dateFmt.format(Date(c.ts))},${c.pwm},${c.volumeMl},${c.dropsPerMl},${c.flowMlMin}\n")
                    }
                out.write("\n")
            }
            if (Category.ALARMS in req.categories) {
                writeSection(out, "Alarms")
                out.write("Alarm Id,Device,Timestamp,Fault,State,Acknowledged\n")
                DataRecorder.allAlarms()
                    .filter { matches(req, it) }
                    .forEach { a ->
                        out.write("${csvCell(a.id)},${csvCell(a.deviceId)},${dateFmt.format(Date(a.ts))},${csvCell(a.fault)},${csvCell(a.state)},${a.acknowledged}\n")
                    }
                out.write("\n")
            }
        }
        return file
    }

    // ---------------- JSON ----------------

    private fun exportJson(context: Context, dir: File, base: String, req: ExportRequest): File {
        val file = File(dir, "$base.json")
        val builder = StringBuilder()
        builder.append("{\n")
        builder.append("  \"exported_at\": \"${dateFmt.format(Date())}\",\n")
        builder.append("  \"device_filter\": ${jsonStr(req.deviceId)},\n")
        builder.append("  \"date_range_from\": ${req.from},\n")
        builder.append("  \"date_range_to\": ${req.to},\n")
        builder.append("  \"categories\": [${req.categories.joinToString { "\"${it.label}\"" }}],\n")

        if (Category.TELEMETRY in req.categories) {
            builder.append("  \"telemetry\": [\n")
            DataRecorder.allTelemetry().filter { matches(req, it) }.forEachIndexed { i, t ->
                builder.append("    {\"ts\": ${t.ts}, \"device_id\": ${jsonStr(t.deviceId)}, \"state\": ${jsonStr(t.state)}, \"fault\": ${jsonStr(t.fault)}, \"flow_ml_min\": ${t.flowMlMin}, \"drops_per_sec\": ${t.dropsPerSecond}, \"pressure_kpa\": ${t.pressureKpa}, \"current_ma\": ${t.currentMa}, \"pwm\": ${t.pwm}}")
                builder.append(if (i < telemetryCount(req) - 1) ",\n" else "\n")
            }
            builder.append("  ],\n")
        }
        if (Category.SESSIONS in req.categories) {
            builder.append("  \"sessions\": [\n")
            DataRecorder.allSessions().filter { matches(req, it) }.forEachIndexed { i, s ->
                builder.append("    {\"id\": ${jsonStr(s.id)}, \"device_id\": ${jsonStr(s.deviceId)}, \"started_at\": ${s.startedAt}, \"ended_at\": ${s.endedAt}, \"target_drops\": ${s.targetDrops}, \"drops_delivered\": ${s.dropsDelivered}, \"target_flow_ml_min\": ${s.targetFlowMlMin}, \"avg_flow_ml_min\": ${s.avgFlowMlMin}, \"result\": ${jsonStr(s.result)}}")
                builder.append(if (i < sessionsCount(req) - 1) ",\n" else "\n")
            }
            builder.append("  ],\n")
        }
        if (Category.CALIBRATIONS in req.categories) {
            builder.append("  \"calibrations\": [\n")
            DataRecorder.allCalibrations().filter { matches(req, it) }.forEachIndexed { i, c ->
                builder.append("    {\"id\": ${jsonStr(c.id)}, \"device_id\": ${jsonStr(c.deviceId)}, \"ts\": ${c.ts}, \"pwm\": ${c.pwm}, \"volume_ml\": ${c.volumeMl}, \"drops_per_ml\": ${c.dropsPerMl}, \"flow_ml_min\": ${c.flowMlMin}}")
                builder.append(if (i < calibrationsCount(req) - 1) ",\n" else "\n")
            }
            builder.append("  ],\n")
        }
        if (Category.ALARMS in req.categories) {
            builder.append("  \"alarms\": [\n")
            DataRecorder.allAlarms().filter { matches(req, it) }.forEachIndexed { i, a ->
                builder.append("    {\"id\": ${jsonStr(a.id)}, \"device_id\": ${jsonStr(a.deviceId)}, \"ts\": ${a.ts}, \"fault\": ${jsonStr(a.fault)}, \"state\": ${jsonStr(a.state)}, \"acknowledged\": ${a.acknowledged}}")
                builder.append(if (i < alarmsCount(req) - 1) ",\n" else "\n")
            }
            builder.append("  ]\n")
        }
        builder.append("}\n")

        FileOutputStream(file).use { out ->
            out.write(builder.toString().toByteArray(Charsets.UTF_8))
        }
        return file
    }

    // ---------------- PDF ----------------

    @SuppressLint("WrongThread")
    private fun exportPdf(context: Context, dir: File, base: String, req: ExportRequest): File {
        val file = File(dir, "$base.pdf")
        val document = PdfDocument()

        try {
            val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4-ish points
            var page = document.startPage(pageInfo)
            var canvas = page.canvas
            val paint = android.graphics.Paint()
            paint.color = android.graphics.Color.BLACK
            paint.textSize = 12f
            paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)

            var y = 40f
            canvas.drawText("Triveni — Data Export", 40f, y, paint); y += 20
            paint.typeface = android.graphics.Typeface.DEFAULT
            paint.textSize = 10f
            canvas.drawText("Exported ${dateFmt.format(Date())}", 40f, y, paint); y += 14
            canvas.drawText("Device: ${req.deviceId ?: "All devices"}", 40f, y, paint); y += 14
            canvas.drawText("Range: ${dateFmt.format(Date(req.from))} to ${dateFmt.format(Date(req.to))}", 40f, y, paint); y += 24

            fun section(title: String) {
                paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                paint.textSize = 11f
                canvas.drawText(title, 40f, y, paint); y += 14
                paint.typeface = android.graphics.Typeface.DEFAULT
                paint.textSize = 9f
            }

            if (Category.TELEMETRY in req.categories) {
                section("Telemetry")
                DataRecorder.allTelemetry().filter { matches(req, it) }.take(60).forEach { t ->
                    canvas.drawText("${dateFmt.format(Date(t.ts))} ${t.deviceId} ${t.state} ${t.fault} flow=${t.flowMlMin} drops/s=${t.dropsPerSecond} kPa=${t.pressureKpa} mA=${t.currentMa} pwm=${t.pwm}", 40f, y, paint)
                    y += 11
                    if (y > 800) { document.finishPage(page); page = document.startPage(pageInfo); canvas = page.canvas; y = 40f }
                }
                y += 10
            }
            if (Category.SESSIONS in req.categories) {
                section("Sessions")
                DataRecorder.allSessions().filter { matches(req, it) }.take(40).forEach { s ->
                    canvas.drawText("${s.id} ${s.deviceId} started=${dateFmt.format(Date(s.startedAt))} target=${s.targetDrops} delivered=${s.dropsDelivered} result=${s.result}", 40f, y, paint)
                    y += 11
                    if (y > 800) { document.finishPage(page); page = document.startPage(pageInfo); canvas = page.canvas; y = 40f }
                }
                y += 10
            }
            if (Category.CALIBRATIONS in req.categories) {
                section("Calibrations")
                DataRecorder.allCalibrations().filter { matches(req, it) }.take(40).forEach { c ->
                    canvas.drawText("${c.deviceId} ts=${dateFmt.format(Date(c.ts))} pwm=${c.pwm} vol=${c.volumeMl}ml drops/ml=${c.dropsPerMl} flow=${c.flowMlMin}", 40f, y, paint)
                    y += 11
                    if (y > 800) { document.finishPage(page); page = document.startPage(pageInfo); canvas = page.canvas; y = 40f }
                }
                y += 10
            }
            if (Category.ALARMS in req.categories) {
                section("Alarms")
                DataRecorder.allAlarms().filter { matches(req, it) }.take(40).forEach { a ->
                    canvas.drawText("${dateFmt.format(Date(a.ts))} ${a.deviceId} ${a.fault} ${a.state} ack=${a.acknowledged}", 40f, y, paint)
                    y += 11
                    if (y > 800) { document.finishPage(page); page = document.startPage(pageInfo); canvas = page.canvas; y = 40f }
                }
            }

            document.finishPage(page)
            FileOutputStream(file).use { document.writeTo(it) }
        } finally {
            document.close()
        }
        return file
    }

    // ---------------- helpers ----------------

    private fun writeSection(out: OutputStreamWriter, title: String) {
        out.write("=== $title ===\n")
    }

    /** Quote external text as CSV and neutralize spreadsheet formula cells. */
    private fun csvCell(value: String): String {
        val firstMeaningful = value.trimStart(' ', '\t', '\r', '\n').firstOrNull()
        val safe = if (firstMeaningful == '=' || firstMeaningful == '+' ||
            firstMeaningful == '-' || firstMeaningful == '@') "'$value" else value
        return "\"${safe.replace("\"", "\"\"")}\""
    }

    private fun matches(req: ExportRequest, deviceId: String, ts: Long): Boolean {
        if (req.deviceId != null && deviceId != req.deviceId) return false
        return ts in req.from..req.to
    }

    private fun matches(req: ExportRequest, t: DataRecorder.TelemetrySample): Boolean =
        matches(req, t.deviceId, t.ts)

    private fun matches(req: ExportRequest, s: DataRecorder.Session): Boolean =
        matches(req, s.deviceId, s.startedAt)

    private fun matches(req: ExportRequest, c: DataRecorder.Calibration): Boolean =
        matches(req, c.deviceId, c.ts)

    private fun matches(req: ExportRequest, a: DataRecorder.AlarmEvent): Boolean =
        matches(req, a.deviceId, a.ts)

    private fun telemetryCount(req: ExportRequest) = DataRecorder.allTelemetry().filter { matches(req, it.deviceId, it.ts) }.size
    private fun sessionsCount(req: ExportRequest) = DataRecorder.allSessions().filter { matches(req, it.deviceId, it.startedAt) }.size
    private fun calibrationsCount(req: ExportRequest) = DataRecorder.allCalibrations().filter { matches(req, it.deviceId, it.ts) }.size
    private fun alarmsCount(req: ExportRequest) = DataRecorder.allAlarms().filter { matches(req, it.deviceId, it.ts) }.size

    private fun jsonStr(s: String?): String = if (s == null) "null" else gson.toJson(s)

    private fun outputDir(context: Context): File {
        val external = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        return if (external != null) external else context.getExternalFilesDir(null) ?: context.cacheDir
    }

    private fun <T> Long?.orNull(): Long? = this
}
