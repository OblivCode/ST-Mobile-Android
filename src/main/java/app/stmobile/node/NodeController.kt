package app.stmobile.node

import android.content.Context
import app.stmobile.AppPaths
import app.stmobile.models.AppConfig
import app.stmobile.models.NodeConfig
import app.stmobile.models.StConfig
import app.stmobile.sillytavern.PayloadManager
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

class PortInUseException(val port: Int) :
    Exception("Port $port is in use and autoPortFallback is disabled")

/**
 * Launches and supervises the SillyTavern Node process.
 * Owned by [NodeService]; the process is a child of this app process.
 */
class NodeController(private val context: Context) {

    @Volatile
    private var process: Process? = null

    @Volatile
    var currentPort: Int? = null
        private set

    fun isRunning(): Boolean = process?.isAlive == true

    fun pid(): Long? {
        val current = process?.takeIf { it.isAlive } ?: return null
        return try {
            // Process.pid() is not guaranteed across Android API levels; reflect.
            val method = current.javaClass.getMethod("pid")
            (method.invoke(current) as? Long)
        } catch (_: Exception) {
            null
        }
    }

    data class LaunchResult(
        val process: Process,
        val effectivePort: Int,
    )

    /**
     * Starts the launcher with the ST server and returns once the process is spawned.
     */
    fun start(
        layout: PayloadManager.Layout,
        stConfig: StConfig,
        nodeConfig: NodeConfig,
        appConfig: AppConfig,
    ): LaunchResult {
        val paths = AppPaths(context)
        val stdout = File(layout.logsDir, "node_stdout.log")
        val stderr = File(layout.logsDir, "node_stderr.log")
        rotateIfNeeded(stdout)
        rotateIfNeeded(stderr)

        val targetPort = stConfig.port
        val effectivePort = resolvePort(targetPort, appConfig.autoPortFallback)
        currentPort = effectivePort

        val cmd = mutableListOf<String>()
        // 1. Program
        cmd.add(layout.launcher.absolutePath)

        // 2. Node/V8 runtime options strictly BEFORE script entry point
        cmd.add("--max-old-space-size=${nodeConfig.clampedMaxOldSpaceSizeMb}")
        for (extraOption in nodeConfig.extraNodeOptions) {
            if (extraOption.isNotBlank()) cmd.add(extraOption)
        }

        // 3. Script entry file
        cmd.add(layout.stEntry.absolutePath)

        // 4. SillyTavern CLI arguments AFTER script entry point
        cmd.add("--configPath")
        cmd.add(layout.configFile.absolutePath)
        cmd.add("--dataRoot")
        cmd.add(layout.dataDir.absolutePath)
        cmd.add("--browserLaunchEnabled")
        cmd.add("false")
        cmd.add("--port")
        cmd.add(effectivePort.toString())

        val pb = ProcessBuilder(cmd)
        pb.directory(layout.stDir)
        pb.environment().apply {
            put("LD_LIBRARY_PATH", layout.nativeLibDir)
            put("HOME", paths.filesDir.absolutePath)
            put("TMPDIR", layout.nodeTmpDir.absolutePath)
            put("TMP", layout.nodeTmpDir.absolutePath)
            put("TEMP", layout.nodeTmpDir.absolutePath)
            put("NODE_ENV", nodeConfig.nodeEnv)
            put("TZ", nodeConfig.timezone.ifBlank { "UTC" })
            put("SILLYTAVERN_PORT", effectivePort.toString())
            put("ST_ANDROID", "1")

            val nodeOptions = nodeConfig.toNodeOptions()
            if (nodeOptions.isNotBlank()) {
                put("NODE_OPTIONS", nodeOptions)
            }

            if (appConfig.basicAuthEnabled) {
                put("SILLYTAVERN_BASICAUTHMODE", "true")
                put("SILLYTAVERN_BASICAUTHUSER_USERNAME", appConfig.basicAuthUsername)
                put("SILLYTAVERN_BASICAUTHUSER_PASSWORD", appConfig.basicAuthPassword)
            }

            for ((k, v) in nodeConfig.extraEnv) {
                put(k, v)
            }
        }
        pb.redirectOutput(stdout)
        pb.redirectError(stderr)

        val started = pb.start()
        process = started
        return LaunchResult(started, effectivePort)
    }

    fun stop() {
        val current = process ?: return
        try {
            current.destroy()
            if (!current.waitFor(3, TimeUnit.SECONDS)) current.destroyForcibly()
        } catch (_: Exception) {
            current.destroyForcibly()
        } finally {
            if (process === current) {
                process = null
                currentPort = null
            }
        }
    }

    private fun resolvePort(targetPort: Int, autoFallback: Boolean): Int {
        if (isPortAvailable(targetPort)) return targetPort
        if (!autoFallback) throw PortInUseException(targetPort)
        return allocateEphemeralPort()
    }

    private fun isPortAvailable(port: Int): Boolean = try {
        ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).use { true }
    } catch (_: IOException) {
        false
    }

    private fun allocateEphemeralPort(): Int {
        return ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
    }

    companion object {
        const val DEFAULT_PORT = 8000
        private const val MAX_LOG_BYTES = 10L * 1024 * 1024

        /** Keeps logs bounded: rotate to "<name>.1" once over the cap. */
        fun rotateIfNeeded(file: File) {
            if (!file.exists() || file.length() <= MAX_LOG_BYTES) return
            val backup = File(file.parentFile, "${file.name}.1")
            if (backup.exists()) backup.delete()
            file.renameTo(backup)
        }
    }
}
