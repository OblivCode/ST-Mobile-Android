package app.stmobile.logging

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import app.stmobile.AppPaths
import app.stmobile.BuildConfig
import app.stmobile.R
import app.stmobile.Utils
import app.stmobile.models.NodeStatus
import app.stmobile.node.NodeController
import app.stmobile.payload.PayloadManager
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileReader
import java.time.Instant
import java.util.ArrayDeque
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Diagnostics snapshot generator, clipboard exporter, and unified log bundle builder.
 */
object DiagnosticsSnapshot {
    const val MAX_CLIPBOARD_CHARS = 64 * 1024 // 64 KB Binder safety cap to avoid TransactionTooLargeException

    /**
     * Reads up to [maxLines] tail lines from [file] using a bounded circular buffer,
     * avoiding loading multi-megabyte log files into JVM heap memory.
     */
    fun readTail(file: File?, maxLines: Int = 50): List<String> {
        if (file == null || !file.exists() || !file.isFile) return emptyList()
        val queue = ArrayDeque<String>(maxLines)
        try {
            BufferedReader(FileReader(file)).use { reader ->
                var line: String? = reader.readLine()
                while (line != null) {
                    if (queue.size == maxLines) queue.pollFirst()
                    queue.addLast(line.take(500)) // Cap line length
                    line = reader.readLine()
                }
            }
        } catch (_: Exception) {}
        return queue.toList()
    }

    /**
     * Reads recent lines from `app.log`. If `app.log` recently rotated and contains
     * fewer than [maxLines] lines, prepends lines from `app.log.1` so diagnostic context is preserved.
     */
    fun readAppLogTailWithFallback(logsDir: File, maxLines: Int = 50): List<String> {
        val appLog = File(logsDir, "app.log")
        val recent = readTail(appLog, maxLines)
        if (recent.size >= maxLines) return recent
        val appLog1 = File(logsDir, "app.log.1")
        if (!appLog1.exists()) return recent
        val needed = maxLines - recent.size
        val older = readTail(appLog1, needed)
        return older + recent
    }

    /**
     * Formats a complete, human-readable diagnostics report.
     * Decoupled pure function for millisecond JVM testability.
     */
    fun format(
        appVersion: String,
        flavor: String,
        installedInfo: PayloadManager.InstalledInfo?,
        deviceInfo: Map<String, String>,
        isBatteryExempt: Boolean,
        status: NodeStatus,
        appLogTail: List<String>,
        nodeStderrTail: List<String>,
        crashLogTail: List<String>? = null,
    ): String {
        return buildString {
            append("================================================================================\n")
            append("ST-MOBILE DIAGNOSTICS SNAPSHOT\n")
            append("================================================================================\n")
            append("Timestamp: ${Instant.now()}\n")
            append("App Version: $appVersion (Flavor: $flavor)\n")
            installedInfo?.let {
                append("SillyTavern: ${it.stVersion ?: "Unknown"} (Commit: ${it.stCommit ?: "None"}, Payload: ${it.payloadVersion ?: "Unknown"})\n")
            }
            append("\n[Device & System]\n")
            deviceInfo.forEach { (k, v) -> append("$k: $v\n") }
            append("Battery Optimization Exempt: $isBatteryExempt\n")
            append("\n[Server State]\n")
            append("State: ${status.state} (Port: ${status.port}, PID: ${status.pid ?: "N/A"})\n")
            append("Generating: ${status.isGenerating}\n")
            append("\n[Log Tail: app.log (last ${appLogTail.size} lines)]\n")
            if (appLogTail.isEmpty()) {
                append("(No app.log entries recorded yet)\n")
            } else {
                appLogTail.forEach { append("$it\n") }
            }
            append("\n[Log Tail: node_stderr.log (last ${nodeStderrTail.size} lines)]\n")
            if (nodeStderrTail.isEmpty()) {
                append("(No stderr entries recorded)\n")
            } else {
                nodeStderrTail.forEach { append("$it\n") }
            }
            if (!crashLogTail.isNullOrEmpty()) {
                append("\n[Crash Report (last ${crashLogTail.size} lines)]\n")
                crashLogTail.forEach { append("$it\n") }
            }
            append("================================================================================\n")
        }.take(MAX_CLIPBOARD_CHARS)
    }

    /**
     * Gathers current system diagnostics asynchronously and writes the text snapshot to the Android clipboard.
     */
    fun copyToClipboard(context: Context) {
        Thread {
            try {
                val paths = AppPaths(context)
                val installed = PayloadManager(context).getInstalledInfo()
                val pm = context.getSystemService(PowerManager::class.java)
                val isExempt = pm?.isIgnoringBatteryOptimizations(context.packageName) == true

                val devInfo = mapOf(
                    "Model" to "${Build.MANUFACTURER} ${Build.MODEL}",
                    "Device" to (Build.DEVICE ?: "Unknown"),
                    "Android" to "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
                    "ABIs" to (Build.SUPPORTED_ABIS?.joinToString() ?: "Unknown"),
                )

                val text = format(
                    appVersion = CrashHandler.resolveAppVersion(context),
                    flavor = BuildConfig.FLAVOR,
                    installedInfo = installed,
                    deviceInfo = devInfo,
                    isBatteryExempt = isExempt,
                    status = NodeController.status.value,
                    appLogTail = readAppLogTailWithFallback(paths.logsDir, 50),
                    nodeStderrTail = readTail(File(paths.logsDir, "node_stderr.log"), 50),
                    crashLogTail = readTail(File(paths.logsDir, "crash.log"), 50).takeIf { it.isNotEmpty() },
                )

                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("ST-Mobile Diagnostics", text)
                clipboard.setPrimaryClip(clip)
                Utils.toast(context, context.getString(R.string.toast_diagnostics_copied))
            } catch (e: Exception) {
                Utils.toast(context, "Failed to copy diagnostics: ${e.message}")
            }
        }.start()
    }

    /**
     * Bundles all active log files, recent crash reports, and a fresh `diagnostics.txt` summary
     * into `stmobile_logs_bundle.zip` inside [logsDir].
     */
    fun createLogBundle(
        logsDir: File,
        appVersion: String,
        flavor: String,
        installedInfo: PayloadManager.InstalledInfo? = null,
        deviceInfo: Map<String, String> = emptyMap(),
        isBatteryExempt: Boolean = false,
        nodeStatus: NodeStatus = NodeController.status.value,
    ): File {
        logsDir.mkdirs()

        val tmpZip = File(logsDir, "stmobile_logs_bundle.tmp")
        val finalZip = File(logsDir, "stmobile_logs_bundle.zip")
        if (tmpZip.exists()) tmpZip.delete()

        val candidateFiles = listOf(
            File(logsDir, "app.log"),
            File(logsDir, "app.log.1"),
            File(logsDir, "node_stdout.log"),
            File(logsDir, "node_stdout.log.1"),
            File(logsDir, "node_stderr.log"),
            File(logsDir, "node_stderr.log.1"),
            File(logsDir, "crash.log"),
        ).filter { it.exists() && it.isFile && it.length() > 0 }

        ZipOutputStream(FileOutputStream(tmpZip)).use { zos ->
            // 1. Add diagnostics summary text
            val diagSummary = format(
                appVersion = appVersion,
                flavor = flavor,
                installedInfo = installedInfo,
                deviceInfo = deviceInfo,
                isBatteryExempt = isBatteryExempt,
                status = nodeStatus,
                appLogTail = readAppLogTailWithFallback(logsDir, 100),
                nodeStderrTail = readTail(File(logsDir, "node_stderr.log"), 100),
                crashLogTail = readTail(File(logsDir, "crash.log"), 100).takeIf { it.isNotEmpty() },
            )
            zos.putNextEntry(ZipEntry("diagnostics.txt"))
            zos.write(diagSummary.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // 2. Add log files
            val buffer = ByteArray(16 * 1024)
            for (file in candidateFiles) {
                zos.putNextEntry(ZipEntry(file.name))
                FileInputStream(file).use { fis ->
                    var read = fis.read(buffer)
                    while (read != -1) {
                        zos.write(buffer, 0, read)
                        read = fis.read(buffer)
                    }
                }
                zos.closeEntry()
            }
        }

        if (finalZip.exists()) finalZip.delete()
        tmpZip.renameTo(finalZip)
        return finalZip
    }

    fun createLogBundle(context: Context): File {
        val paths = AppPaths(context)
        val pm = context.getSystemService(PowerManager::class.java)
        val isExempt = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        val devInfo = mapOf(
            "Model" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "Device" to (Build.DEVICE ?: "Unknown"),
            "Android" to "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            "ABIs" to (Build.SUPPORTED_ABIS?.joinToString() ?: "Unknown"),
        )
        return createLogBundle(
            logsDir = paths.logsDir,
            appVersion = CrashHandler.resolveAppVersion(context),
            flavor = BuildConfig.FLAVOR,
            installedInfo = PayloadManager(context).getInstalledInfo(),
            deviceInfo = devInfo,
            isBatteryExempt = isExempt,
            nodeStatus = NodeController.status.value,
        )
    }
}
