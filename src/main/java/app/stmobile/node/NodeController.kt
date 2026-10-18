package app.stmobile.node

import android.content.Context
import android.content.Intent
import app.stmobile.AppPaths
import app.stmobile.models.AppConfig
import app.stmobile.models.NodeConfig
import app.stmobile.models.NodeState
import app.stmobile.models.NodeStatus
import app.stmobile.models.StConfig
import app.stmobile.payload.PayloadManager
import app.stmobile.logging.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Unified singleton controller and state repository for the SillyTavern Node.js process.
 * Manages process execution, log rotation, loopback IPC, readiness polling, and status tracking.
 */
object NodeController {

    const val DEFAULT_PORT = PortResolver.DEFAULT_PORT
    private const val MAX_LOG_BYTES = 10L * 1024 * 1024

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "node-controller-worker").apply { isDaemon = true }
    }

    @Volatile
    private var process: Process? = null

    @Volatile
    var currentPort: Int? = null
        private set

    @Volatile
    private var stopRequested = false

    @Volatile
    private var activeStoppingExpected: AtomicBoolean? = null

    @Volatile
    private var ipcServer: GenerationIpcServer? = null

    private val _status = MutableStateFlow(NodeStatus(NodeState.STOPPED, "Idle", DEFAULT_PORT))
    val status: StateFlow<NodeStatus> = _status.asStateFlow()

    fun isRunning(): Boolean = process?.isAlive == true

    fun pid(): Long? {
        val current = process?.takeIf { it.isAlive } ?: return null
        return try {
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

    fun buildLaunchSpec(
        layout: PayloadManager.Layout,
        stConfig: StConfig,
        nodeConfig: NodeConfig,
        appConfig: AppConfig,
        homeDir: File,
        ipcPort: Int? = null,
        ipcToken: String? = null,
        effectiveMaxOldSpaceMb: Int = nodeConfig.clampedMaxOldSpaceSizeMb,
    ): ProcessLaunchSpec {
        val stdout = File(layout.logsDir, "node_stdout.log")
        val stderr = File(layout.logsDir, "node_stderr.log")

        val targetPort = stConfig.port
        val effectivePort = PortResolver.resolvePort(targetPort, appConfig.autoPortFallback)

        val cmd = mutableListOf<String>()
        // 1. Program
        cmd.add(layout.launcher.absolutePath)

        // 2. Node/V8 runtime options strictly BEFORE script entry point
        cmd.add("--max-old-space-size=$effectiveMaxOldSpaceMb")
        cmd.add("--max-semi-space-size=${nodeConfig.clampedMaxSemiSpaceSizeMb}")
        cmd.add("--concurrent-turbofan-max-threads=2")
        cmd.add("--concurrent-marking-max-worker-num=2")
        if (nodeConfig.optimizeForSize) {
            cmd.add("--optimize-for-size")
        }
        for (extraOption in nodeConfig.extraNodeOptions) {
            if (extraOption.isNotBlank()) cmd.add(extraOption)
        }
        val hook = layout.generationHook
        if (hook != null && hook.exists()) {
            cmd.add("--require")
            cmd.add(hook.absolutePath)
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
        env["UV_THREADPOOL_SIZE"] = nodeConfig.clampedUvThreadPoolSize.toString()

        if (ipcPort != null && ipcPort > 0) {
            env["ST_GEN_PORT"] = ipcPort.toString()
        }

        if (!ipcToken.isNullOrBlank()) {
            env["ST_GEN_TOKEN"] = ipcToken
        }

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

    fun rotateIfNeeded(file: File) {
        if (!file.exists() || file.length() <= MAX_LOG_BYTES) return
        val backup = File(file.parentFile, "${file.name}.1")
        if (backup.exists()) backup.delete()
        file.renameTo(backup)
    }

    /**
     * Executes process start on background worker thread.
     * Called by ForegroundService on start command.
     */
    fun executeStart(
        context: Context,
        explicitPort: Int?,
        onIpcStarted: () -> Unit,
        onIpcEnded: () -> Unit,
        onVectorStarted: () -> Unit = {},
        onVectorEnded: () -> Unit = {},
        onIpcDisconnected: () -> Unit,
        onProcessTerminated: () -> Unit,
    ) {
        AppLog.i("NodeController", "executeStart requested (explicitPort=$explicitPort, isRunning=${isRunning()})")
        if (isRunning() && !stopRequested && _status.value.state != NodeState.STOPPING) {
            sendStatus(NodeState.RUNNING, "Running", pid())
            return
        }
        stopRequested = false
        activeStoppingExpected = null
        sendStatus(NodeState.STARTING, "Starting SillyTavern…")

        worker.execute {
            stopRequested = false
            activeStoppingExpected = null
            runServer(
                context = context,
                explicitPort = explicitPort,
                onIpcStarted = onIpcStarted,
                onIpcEnded = onIpcEnded,
                onVectorStarted = onVectorStarted,
                onVectorEnded = onVectorEnded,
                onIpcDisconnected = onIpcDisconnected,
                onProcessTerminated = onProcessTerminated,
            )
        }
    }

    /**
     * Executes server stop on background worker.
     */
    fun executeStop(context: Context, onStopped: (() -> Unit)? = null) {
        AppLog.i("NodeController", "executeStop requested")
        AppConfig(context).wasRunningBeforeKill = false
        stopRequested = true
        activeStoppingExpected?.set(true)
        sendStatus(NodeState.STOPPING, "Stopping…")
        worker.execute {
            stopServerInternal()
            onStopped?.invoke()
        }
    }

    fun forceStop() {
        AppLog.w("NodeController", "forceStop requested")
        stopRequested = true
        activeStoppingExpected?.set(true)
        stopServerInternal(forceImmediate = true)
        if (_status.value.state != NodeState.ERROR) {
            _status.value = NodeStatus(NodeState.STOPPED, "Stopped", currentPort ?: DEFAULT_PORT)
        }
    }

    fun updateGeneratingState(isGenerating: Boolean) {
        val current = _status.value
        if (current.state == NodeState.RUNNING) {
            val msg = if (isGenerating) "Generating response…" else "Running"
            _status.value = current.copy(message = msg, isGenerating = isGenerating)
        }
    }

    private fun runServer(
        context: Context,
        explicitPort: Int?,
        onIpcStarted: () -> Unit,
        onIpcEnded: () -> Unit,
        onVectorStarted: () -> Unit,
        onVectorEnded: () -> Unit,
        onIpcDisconnected: () -> Unit,
        onProcessTerminated: () -> Unit,
    ) {
        if (isRunning()) return
        val paths = AppPaths(context)

        try {
            val layout = PayloadManager(context).getExistingLayout()
            if (stopRequested) return

            val appConfig = AppConfig(context)
            val nodeConfig = NodeConfig.load(context)
            val stConfig = StConfig.fromFileOrDefault(paths.configFile) {
                context.assets.open("default_config.yaml")
            }

            explicitPort?.let { stConfig.port = it }
            val targetPort = explicitPort ?: stConfig.port
            currentPort = targetPort

            // Generate ephemeral auth token for loopback IPC
            val genToken = UUID.randomUUID().toString()

            // Start Generation IPC server before spawning Node
            val ipc = GenerationIpcServer(
                authToken = genToken,
                onGenerationStarted = onIpcStarted,
                onGenerationEnded = onIpcEnded,
                onVectorStarted = onVectorStarted,
                onVectorEnded = onVectorEnded,
                onClientDisconnected = onIpcDisconnected,
            )
            ipc.start()
            this.ipcServer = ipc

            sendStatus(NodeState.STARTING, "Launching server on port $targetPort…")
            val effectiveMaxOldSpace = nodeConfig.resolveEffectiveMaxOldSpace(context)
            val spec = buildLaunchSpec(layout, stConfig, nodeConfig, appConfig, paths.filesDir, ipc.port, genToken, effectiveMaxOldSpace)
            rotateIfNeeded(spec.stdoutFile)
            rotateIfNeeded(spec.stderrFile)
            currentPort = spec.effectivePort

            val safeCmd = spec.command.map { arg ->
                if (arg.contains("=")) {
                    val key = arg.substringBefore("=")
                    if (key.contains("password", ignoreCase = true) || key.contains("token", ignoreCase = true)) {
                        "$key=******"
                    } else arg
                } else arg
            }
            AppLog.i("NodeController", "Spawning Node server on port ${spec.effectivePort}: ${safeCmd.joinToString(" ")}")

            val pb = ProcessBuilder(spec.command)
            pb.directory(spec.directory)
            pb.environment().putAll(spec.environment)
            pb.redirectOutput(spec.stdoutFile)
            pb.redirectError(spec.stderrFile)

            val startedProcess = pb.start()
            this.process = startedProcess
            AppLog.i("NodeController", "Node process spawned (PID=${pid()})")

            val isFallback = spec.effectivePort != targetPort
            val probeMsg = if (isFallback) {
                "Port $targetPort occupied (fell back to ${spec.effectivePort}). Waiting for server…"
            } else {
                "Waiting for server to accept connections…"
            }
            sendStatus(NodeState.STARTING, probeMsg)

            val readiness = ServerReadinessPoller.poll(
                port = spec.effectivePort,
                maxWaitMs = 30_000,
                intervalMs = 250,
                probeTimeoutMs = 500,
                isProcessAlive = { isRunning() },
                isStopRequested = { stopRequested },
            )
            AppLog.i("NodeController", "Readiness polling finished with status: $readiness")

            if (readiness == ReadinessStatus.CANCELLED || stopRequested) {
                AppLog.i("NodeController", "Startup cancelled or stop requested")
                stopServerInternal()
                sendStatus(NodeState.STOPPED, "Stopped")
                return
            }

            if (readiness != ReadinessStatus.READY) {
                val errorMsg = if (!isRunning() || readiness == ReadinessStatus.PROCESS_EXITED) {
                    "Server process terminated unexpectedly"
                } else {
                    "Server failed to respond within 30 seconds"
                }
                AppLog.e("NodeController", "Server failed readiness check: $errorMsg")
                stopServerInternal()
                sendStatus(NodeState.ERROR, errorMsg)
                onProcessTerminated()
                return
            }

            AppConfig(context).wasRunningBeforeKill = true
            val runningMsg = if (isFallback) "Running (port ${spec.effectivePort})" else "Running"
            sendStatus(NodeState.RUNNING, runningMsg, pid())
            AppLog.i("NodeController", "Server is ready and accepting requests on port ${spec.effectivePort}")

            val stoppingToken = AtomicBoolean(false)
            this.activeStoppingExpected = stoppingToken
            val currentIpc = ipc

            val supervisor = Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                val exit = try {
                    startedProcess.waitFor()
                } catch (_: Exception) {
                    null
                }
                currentIpc.stop()
                if (this.process === startedProcess) {
                    this.ipcServer = null
                    this.process = null
                }
                onProcessTerminated()

                val wasStopping = stoppingToken.get() || stopRequested || _status.value.state == NodeState.STOPPING
                AppLog.i("NodeController", "Node process terminated: exitCode=$exit, wasStopping=$wasStopping")
                if (wasStopping) {
                    if (_status.value.state != NodeState.STARTING) {
                        sendStatus(NodeState.STOPPED, "Stopped")
                    }
                } else if (exit == 0) {
                    sendStatus(NodeState.STOPPED, "Server exited")
                } else {
                    sendStatus(NodeState.ERROR, "Server exited with code ${exit ?: "?"}")
                }
            }, "node-supervisor").apply {
                isDaemon = true
            }
            supervisor.start()

        } catch (e: PortInUseException) {
            AppLog.e("NodeController", "Port in use error during startup: ${e.message}", e)
            ipcServer?.stop()
            ipcServer = null
            sendStatus(NodeState.ERROR, e.message ?: "Port in use")
            onProcessTerminated()
        } catch (t: Throwable) {
            AppLog.e("NodeController", "Unexpected error starting server: ${t.message}", t)
            ipcServer?.stop()
            ipcServer = null
            sendStatus(NodeState.ERROR, t.message ?: "Start failed")
            onProcessTerminated()
        }
    }

    private fun stopServerInternal(forceImmediate: Boolean = false) {
        ipcServer?.stop()
        ipcServer = null

        val current = process
        if (current != null) {
            try {
                if (forceImmediate) {
                    current.destroyForcibly()
                } else {
                    current.destroy()
                    if (!current.waitFor(3, TimeUnit.SECONDS)) current.destroyForcibly()
                }
            } catch (_: Exception) {
                current.destroyForcibly()
            } finally {
                if (process === current) {
                    process = null
                }
            }
        }
        sendStatus(NodeState.STOPPED, "Stopped")
    }

    private fun sendStatus(state: NodeState, message: String, pid: Long? = null) {
        _status.value = NodeStatus(state, message, currentPort ?: DEFAULT_PORT, pid)
    }
}
