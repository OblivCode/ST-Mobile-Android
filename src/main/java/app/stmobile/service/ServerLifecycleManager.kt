package app.stmobile.service

import android.content.Context
import android.content.Intent
import app.stmobile.AppPaths
import app.stmobile.StApplication
import app.stmobile.models.AppConfig
import app.stmobile.models.NodeConfig
import app.stmobile.models.NodeState
import app.stmobile.models.NodeStatus
import app.stmobile.models.StConfig
import app.stmobile.payload.PayloadManager
import app.stmobile.runtime.BackgroundTimeoutManager
import app.stmobile.runtime.NodeController
import app.stmobile.runtime.PortInUseException
import app.stmobile.runtime.ReadinessStatus
import app.stmobile.runtime.ServerReadinessPoller
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decoupled singleton repository managing the SillyTavern Node.js process lifecycle,
 * status state machine, readiness polling, and idle background countdowns.
 */
object ServerLifecycleManager {

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "server-lifecycle") }
    private var controller: NodeController? = null
    private val timeoutManager = BackgroundTimeoutManager { handleBackgroundTimeout() }

    private val _status = MutableStateFlow(NodeStatus(NodeState.STOPPED, "Idle", NodeController.DEFAULT_PORT))
    val status: StateFlow<NodeStatus> = _status.asStateFlow()

    @Volatile
    private var stopRequested = false
    @Volatile
    private var activeStoppingExpected: AtomicBoolean? = null
    @Volatile
    private var explicitPort: Int? = null
    @Volatile
    private var port = NodeController.DEFAULT_PORT
    @Volatile
    private var appContext: Context? = null

    // ---- External API ----

    fun start(context: Context, port: Int? = null) {
        if (_status.value.state == NodeState.RUNNING && (port == null || port == _status.value.port)) return
        val intent = Intent(context, NodeForegroundService::class.java).setAction(NodeForegroundService.ACTION_START)
        if (port != null && port in 1..65535) {
            intent.putExtra(NodeForegroundService.EXTRA_PORT, port)
        }
        context.startForegroundService(intent)
    }

    fun stop(context: Context) {
        if (_status.value.state == NodeState.STOPPED) return
        val intent = Intent(context, NodeForegroundService::class.java).setAction(NodeForegroundService.ACTION_STOP)
        context.startService(intent)
    }

    fun restart(context: Context) {
        val intent = Intent(context, NodeForegroundService::class.java).setAction(NodeForegroundService.ACTION_RESTART)
        context.startService(intent)
    }

    fun onAppForegrounded() {
        timeoutManager.cancel()
    }

    fun onAppBackgrounded(context: Context) {
        if (controller?.isRunning() == true || _status.value.isActive) {
            val appConfig = AppConfig(context)
            timeoutManager.schedule(appConfig.backgroundTimeoutMinutes)
        }
    }

    // ---- Internal Service Commands ----

    internal fun executeStart(context: Context, port: Int?) {
        this.appContext = context.applicationContext
        this.explicitPort = port
        val ctrl = getController(context)
        if (ctrl.isRunning()) {
            sendStatus(NodeState.RUNNING, "Running", ctrl.pid())
            return
        }
        stopRequested = false
        activeStoppingExpected = null
        sendStatus(NodeState.STARTING, "Starting SillyTavern…")
        worker.execute { runServer(context) }
    }

    internal fun executeStop(context: Context) {
        this.appContext = context.applicationContext
        AppConfig(context).wasRunningBeforeKill = false
        stopRequested = true
        activeStoppingExpected?.set(true)
        sendStatus(NodeState.STOPPING, "Stopping…")
        worker.execute { stopServer(context, restart = false) }
    }

    internal fun executeRestart(context: Context) {
        this.appContext = context.applicationContext
        stopRequested = true
        activeStoppingExpected?.set(true)
        sendStatus(NodeState.STARTING, "Restarting…")
        worker.execute { stopServer(context, restart = true) }
    }

    internal fun forceStop() {
        stopRequested = true
        activeStoppingExpected?.set(true)
        timeoutManager.shutdown()
        controller?.stop()
        if (_status.value.state != NodeState.ERROR) {
            _status.value = NodeStatus(NodeState.STOPPED, _status.value.message.ifBlank { "Stopped" }, port)
        }
    }

    // ---- Internals ----

    private fun getController(context: Context): NodeController {
        return controller ?: synchronized(this) {
            controller ?: NodeController(context.applicationContext).also { controller = it }
        }
    }

    private fun runServer(context: Context) {
        val ctrl = getController(context)
        if (ctrl.isRunning()) return
        val paths = AppPaths(context)
        try {
            val layout = PayloadManager(context).getExistingLayout()
            if (stopRequested) {
                return
            }

            val appConfig = AppConfig(context)
            val nodeConfig = NodeConfig.load(context)
            val stConfig = StConfig.fromFileOrDefault(paths.configFile) {
                context.assets.open("default_config.yaml")
            }

            explicitPort?.let {
                stConfig.port = it
            }

            val targetPort = explicitPort ?: stConfig.port
            this.port = targetPort
            sendStatus(NodeState.STARTING, "Launching server on port $targetPort…")
            val launchResult = ctrl.start(layout, stConfig, nodeConfig, appConfig)
            this.port = launchResult.effectivePort

            val isFallback = launchResult.effectivePort != targetPort
            val probeMsg = if (isFallback) {
                "Port $targetPort occupied (fell back to ${launchResult.effectivePort}). Waiting for server…"
            } else {
                "Waiting for server to accept connections…"
            }
            sendStatus(NodeState.STARTING, probeMsg)

            val readiness = ServerReadinessPoller.poll(
                port = launchResult.effectivePort,
                maxWaitMs = 30_000,
                intervalMs = 250,
                probeTimeoutMs = 500,
                isProcessAlive = { ctrl.isRunning() },
                isStopRequested = { stopRequested },
            )

            if (readiness == ReadinessStatus.CANCELLED || stopRequested) {
                ctrl.stop()
                sendStatus(NodeState.STOPPED, "Stopped")
                return
            }

            if (readiness != ReadinessStatus.READY) {
                if (!ctrl.isRunning() || readiness == ReadinessStatus.PROCESS_EXITED) {
                    sendStatus(NodeState.ERROR, "Server process terminated unexpectedly")
                } else {
                    sendStatus(NodeState.ERROR, "Server failed to respond within 30 seconds")
                    ctrl.stop()
                }
                return
            }

            AppConfig(context).wasRunningBeforeKill = true
            val runningMsg = if (isFallback) "Running (port ${launchResult.effectivePort})" else "Running"
            sendStatus(NodeState.RUNNING, runningMsg, ctrl.pid())

            val isForeground = StApplication.instance?.isAppInForeground ?: false
            if (!isForeground) {
                timeoutManager.schedule(appConfig.backgroundTimeoutMinutes)
            }

            val stoppingToken = AtomicBoolean(false)
            this.activeStoppingExpected = stoppingToken

            val supervisor = Thread({
                val exit = try {
                    launchResult.process.waitFor()
                } catch (_: Exception) {
                    null
                }
                val wasStopping = stoppingToken.get() || stopRequested || _status.value.state == NodeState.STOPPING
                if (wasStopping) {
                    if (_status.value.state != NodeState.STARTING) {
                        sendStatus(NodeState.STOPPED, "Stopped")
                    }
                } else if (exit == 0) {
                    sendStatus(NodeState.STOPPED, "Server exited")
                } else {
                    sendStatus(NodeState.ERROR, "Server exited with code ${exit ?: "?"}")
                }
            }, "node-supervisor")
            supervisor.isDaemon = true
            supervisor.start()

        } catch (e: PortInUseException) {
            sendStatus(NodeState.ERROR, e.message ?: "Port in use")
        } catch (t: Throwable) {
            sendStatus(NodeState.ERROR, t.message ?: "Start failed")
        }
    }

    private fun stopServer(context: Context, restart: Boolean, reason: String = "Stopped") {
        timeoutManager.cancel()
        activeStoppingExpected?.set(true)
        val ctrl = getController(context)
        if (ctrl.isRunning()) {
            sendStatus(NodeState.STOPPING, "Stopping…")
            ctrl.stop()
        }
        stopRequested = !restart
        if (restart) {
            stopRequested = false
            sendStatus(NodeState.STARTING, "Restarting…")
            runServer(context)
        } else {
            AppConfig(context).wasRunningBeforeKill = false
            sendStatus(NodeState.STOPPED, reason)
        }
    }

    private fun handleBackgroundTimeout() {
        val ctx = appContext ?: return
        val isForeground = StApplication.instance?.isAppInForeground ?: false
        if (!isForeground && (controller?.isRunning() == true || _status.value.isActive)) {
            AppConfig(ctx).wasRunningBeforeKill = false
            stopRequested = true
            activeStoppingExpected?.set(true)
            sendStatus(NodeState.STOPPING, "Stopping (background timeout)…")
            worker.execute {
                stopServer(ctx, restart = false, reason = "Stopped (background timeout)")
            }
        }
    }

    private fun sendStatus(state: NodeState, message: String, pid: Long? = null) {
        val newStatus = NodeStatus(state, message, port, pid)
        _status.value = newStatus
    }
}
