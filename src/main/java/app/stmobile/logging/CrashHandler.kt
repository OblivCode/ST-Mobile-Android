package app.stmobile.logging

import android.content.Context
import android.os.Build
import app.stmobile.BuildConfig
import app.stmobile.node.NodeController
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Global uncaught exception handler registered in `StApplication.onCreate()`.
 * Captures fatal JVM exceptions, complete stack traces, device specs, OS release,
 * memory status, and runtime Node state into `/files/logs/crash.log` before process exit.
 */
class CrashHandler(
    private val logsDir: File,
    private val appVersion: String,
    private val defaultHandler: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler(),
) : Thread.UncaughtExceptionHandler {

    private val isCrashing = AtomicBoolean(false)

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        // Prevent infinite recursive crash loops if writing crash log fails
        if (!isCrashing.compareAndSet(false, true)) {
            defaultHandler?.uncaughtException(thread, throwable)
            return
        }

        try {
            logsDir.mkdirs()
            val report = formatCrashReport(
                thread = thread,
                throwable = throwable,
                appVersion = appVersion,
                manufacturer = Build.MANUFACTURER ?: "Unknown",
                model = Build.MODEL ?: "Unknown",
                device = Build.DEVICE ?: "Unknown",
                osRelease = Build.VERSION.RELEASE ?: "Unknown",
                sdkInt = Build.VERSION.SDK_INT,
                supportedAbis = Build.SUPPORTED_ABIS?.joinToString() ?: "Unknown",
                nodeState = NodeController.status.value.state.name,
                nodePort = NodeController.status.value.port,
            )
            val crashFile = File(logsDir, "crash.log")
            FileWriter(crashFile, false).use { it.write(report) }

            AppLog.e("CrashHandler", "FATAL CRASH on thread '${thread.name}': ${throwable.message}", throwable)
            AppLog.flush(2000L)
        } catch (_: Throwable) {
        } finally {
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        fun install(context: Context, logsDir: File) {
            val appVersion = resolveAppVersion(context)
            val currentHandler = Thread.getDefaultUncaughtExceptionHandler()
            if (currentHandler !is CrashHandler) {
                Thread.setDefaultUncaughtExceptionHandler(CrashHandler(logsDir, appVersion, currentHandler))
            }
        }

        fun resolveAppVersion(context: Context): String {
            return try {
                val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                val verName = pkgInfo.versionName
                if (!verName.isNullOrBlank()) {
                    verName
                } else {
                    val code = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(pkgInfo)
                    "$code"
                }
            } catch (_: Exception) {
                "${BuildConfig.VERSION_CODE}"
            }
        }

        fun formatCrashReport(
            thread: Thread,
            throwable: Throwable,
            appVersion: String,
            manufacturer: String = Build.MANUFACTURER ?: "Unknown",
            model: String = Build.MODEL ?: "Unknown",
            device: String = Build.DEVICE ?: "Unknown",
            osRelease: String = Build.VERSION.RELEASE ?: "Unknown",
            sdkInt: Int = Build.VERSION.SDK_INT,
            supportedAbis: String = Build.SUPPORTED_ABIS?.joinToString() ?: "Unknown",
            nodeState: String = NodeController.status.value.state.name,
            nodePort: Int = NodeController.status.value.port,
        ): String {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val rt = Runtime.getRuntime()
            val freeMb = rt.freeMemory() / (1024 * 1024)
            val totalMb = rt.totalMemory() / (1024 * 1024)
            val maxMb = rt.maxMemory() / (1024 * 1024)

            return buildString {
                append("================================================================================\n")
                append("ST-MOBILE FATAL CRASH REPORT\n")
                append("================================================================================\n")
                append("Timestamp: ${Instant.now()}\n")
                append("App Version: $appVersion (Flavor: ${BuildConfig.FLAVOR})\n")
                append("Device: $manufacturer $model ($device)\n")
                append("Android: $osRelease (API $sdkInt)\n")
                append("Supported ABIs: $supportedAbis\n")
                append("JVM Memory: Free: ${freeMb}MB / Total: ${totalMb}MB / Max: ${maxMb}MB\n")
                append("Thread: ${thread.name} (id: ${thread.id}, priority: ${thread.priority})\n")
                append("Node Status: $nodeState (Port: $nodePort)\n")
                append("\n--- FATAL EXCEPTION ---\n")
                append(sw.toString())
                append("================================================================================\n")
            }
        }
    }
}
