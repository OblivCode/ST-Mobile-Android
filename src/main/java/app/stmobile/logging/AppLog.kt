package app.stmobile.logging

import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Thread-safe, non-blocking persistent file logger for ST-Mobile.
 * Writes formatted log lines to `/files/logs/app.log` with atomic 5 MB rotation to `app.log.1`.
 *
 * Designed with an injectable [logcatSink] to allow fast, decoupled pure-JVM unit testing
 * without triggering unmocked `android.util.Log` exceptions.
 */
object AppLog {
    const val DEFAULT_MAX_LOG_BYTES = 5L * 1024 * 1024 // 5 MB rotation threshold

    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "app-log-worker").apply { isDaemon = true }
    }

    @Volatile
    private var logFile: File? = null

    @Volatile
    var maxLogBytes: Long = DEFAULT_MAX_LOG_BYTES

    /**
     * Pluggable Logcat sink for Android system mirroring.
     * Defaults to null so pure host JVM tests never invoke `android.util.Log`.
     * Set in [app.stmobile.StApplication.onCreate] for production.
     */
    @Volatile
    var logcatSink: ((level: Int, tag: String, msg: String, tr: Throwable?) -> Unit)? = null

    fun init(
        logsDir: File,
        maxBytes: Long = DEFAULT_MAX_LOG_BYTES,
        sink: ((level: Int, tag: String, msg: String, tr: Throwable?) -> Unit)? = null,
    ) {
        logsDir.mkdirs()
        logFile = File(logsDir, "app.log")
        maxLogBytes = maxBytes
        logcatSink = sink
    }

    fun v(tag: String, msg: String) = log(2, tag, msg, null)
    fun d(tag: String, msg: String) = log(3, tag, msg, null)
    fun i(tag: String, msg: String, tr: Throwable? = null) = log(4, tag, msg, tr)
    fun w(tag: String, msg: String, tr: Throwable? = null) = log(5, tag, msg, tr)
    fun e(tag: String, msg: String, tr: Throwable? = null) = log(6, tag, msg, tr)

    /**
     * Synchronously blocks until all currently pending log writes are committed to disk.
     * Crucial for clean process exit and fatal crash reporting.
     */
    fun flush(timeoutMs: Long = 1000L) {
        try {
            worker.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {}
    }

    private fun log(level: Int, tag: String, msg: String, tr: Throwable?) {
        logcatSink?.invoke(level, tag, msg, tr)
        val file = logFile ?: return
        val timestamp = LocalDateTime.now().format(formatter)
        val levelChar = when (level) {
            2 -> "V"
            3 -> "D"
            4 -> "I"
            5 -> "W"
            6 -> "E"
            else -> "?"
        }
        val line = buildString {
            append("$timestamp [$levelChar] [$tag] $msg\n")
            tr?.let {
                val sw = StringWriter()
                it.printStackTrace(PrintWriter(sw))
                append(sw.toString())
            }
        }
        worker.execute {
            try {
                rotateIfNeeded(file, maxLogBytes)
                FileWriter(file, true).use { it.write(line) }
            } catch (_: Exception) {}
        }
    }

    internal fun rotateIfNeeded(file: File, threshold: Long) {
        if (!file.exists() || file.length() < threshold) return
        val backup = File(file.parentFile, "${file.name}.1")
        if (backup.exists()) backup.delete()
        file.renameTo(backup)
    }

    /** Reset state for unit testing teardown. */
    internal fun resetForTesting() {
        flush()
        logFile = null
        maxLogBytes = DEFAULT_MAX_LOG_BYTES
        logcatSink = null
    }
}
