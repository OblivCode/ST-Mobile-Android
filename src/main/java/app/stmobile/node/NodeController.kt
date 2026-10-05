package app.stmobile.node

import android.content.Context
import app.stmobile.AppPaths
import app.stmobile.models.AppConfig
import app.stmobile.models.NodeConfig
import app.stmobile.models.StConfig
import app.stmobile.sillytavern.PayloadManager
import java.io.File
import java.util.concurrent.TimeUnit

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

    data class ProcessLaunchSpec(
        val command: List<String>,
        val directory: File,
        val environment: Map<String, String>,
        val effectivePort: Int,
        val stdoutFile: File,
        val stderrFile: File,
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
        val spec = buildLaunchSpec(layout, stConfig, nodeConfig, appConfig, paths.filesDir)
        rotateIfNeeded(spec.stdoutFile)
        rotateIfNeeded(spec.stderrFile)
        currentPort = spec.effectivePort

        val pb = ProcessBuilder(spec.command)
        pb.directory(spec.directory)
        pb.environment().putAll(spec.environment)
        pb.redirectOutput(spec.stdoutFile)
        pb.redirectError(spec.stderrFile)

        val started = pb.start()
        process = started
        return LaunchResult(started, spec.effectivePort)
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

    companion object {
        const val DEFAULT_PORT = PortResolver.DEFAULT_PORT
        private const val MAX_LOG_BYTES = 10L * 1024 * 1024

        fun buildLaunchSpec(
            layout: PayloadManager.Layout,
            stConfig: StConfig,
            nodeConfig: NodeConfig,
            appConfig: AppConfig,
            homeDir: File,
        ): ProcessLaunchSpec {
            val stdout = File(layout.logsDir, "node_stdout.log")
            val stderr = File(layout.logsDir, "node_stderr.log")

            val targetPort = stConfig.port
            val effectivePort = PortResolver.resolvePort(targetPort, appConfig.autoPortFallback)

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

            val env = mutableMapOf<String, String>()
            env["LD_LIBRARY_PATH"] = layout.nativeLibDir
            env["HOME"] = homeDir.absolutePath
            env["TMPDIR"] = layout.nodeTmpDir.absolutePath
            env["TMP"] = layout.nodeTmpDir.absolutePath
            env["TEMP"] = layout.nodeTmpDir.absolutePath
            env["NODE_ENV"] = nodeConfig.nodeEnv
            env["TZ"] = nodeConfig.timezone.ifBlank { "UTC" }
            env["SILLYTAVERN_PORT"] = effectivePort.toString()
            env["ST_ANDROID"] = "1"

            val nodeOptions = nodeConfig.toNodeOptions()
            if (nodeOptions.isNotBlank()) {
                env["NODE_OPTIONS"] = nodeOptions
            }

            if (appConfig.basicAuthEnabled) {
                env["SILLYTAVERN_BASICAUTHMODE"] = "true"
                env["SILLYTAVERN_BASICAUTHUSER_USERNAME"] = appConfig.basicAuthUsername
                env["SILLYTAVERN_BASICAUTHUSER_PASSWORD"] = appConfig.basicAuthPassword
            }

            for ((k, v) in nodeConfig.extraEnv) {
                env[k] = v
            }

            return ProcessLaunchSpec(
                command = cmd,
                directory = layout.stDir,
                environment = env,
                effectivePort = effectivePort,
                stdoutFile = stdout,
                stderrFile = stderr,
            )
        }

        /** Keeps logs bounded: rotate to "<name>.1" once over the cap. */
        fun rotateIfNeeded(file: File) {
            if (!file.exists() || file.length() <= MAX_LOG_BYTES) return
            val backup = File(file.parentFile, "${file.name}.1")
            if (backup.exists()) backup.delete()
            file.renameTo(backup)
        }
    }
}
