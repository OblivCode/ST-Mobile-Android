package app.stmobile.node

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.stmobile.AppPaths
import app.stmobile.MainActivity
import app.stmobile.StApplication
import app.stmobile.models.AppConfig
import app.stmobile.models.NodeConfig
import app.stmobile.models.NodeState
import app.stmobile.models.NodeStatus
import app.stmobile.models.StConfig
import app.stmobile.sillytavern.PayloadManager
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hosts the SillyTavern Node process as a `specialUse` foreground service so
 * the local server survives the app being backgrounded.
 */
class NodeService : Service() {

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "node-service") }
    private val controller by lazy { NodeController(applicationContext) }
    private val timeoutManager by lazy { BackgroundTimeoutManager { handleBackgroundTimeout() } }

    // @Volatile guarantees immediate cross-thread visibility between the main thread
    // (UI/lifecycle commands) and the background worker/supervisor threads.
    @Volatile
    private var status = NodeStatus(NodeState.STOPPED, "Idle", NodeController.DEFAULT_PORT)
    @Volatile
    private var stopRequested = false
    @Volatile
    private var explicitPort: Int? = null
    @Volatile
    private var port = NodeController.DEFAULT_PORT

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        timeoutManager.shutdown()
        worker.shutdownNow()
        if (controller.isRunning()) controller.stop()
        // Preserve fatal error messages (e.g. port conflict, crash exit) so onDestroy()
        // does not overwrite them with a generic "Stopped", allowing the user to inspect the error on the Dashboard.
        if (_status.value.state != NodeState.ERROR) {
            _status.value = NodeStatus(NodeState.STOPPED, _status.value.message.ifBlank { "Stopped" }, port)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // Clear explicitPort when not provided to avoid leaking custom ports across normal starts
                explicitPort = if (intent.hasExtra(EXTRA_PORT)) {
                    val p = intent.getIntExtra(EXTRA_PORT, -1)
                    if (p in 1..65535) p else null
                } else {
                    null
                }
                if (controller.isRunning()) {
                    sendStatus(NodeState.RUNNING, "Running", controller.pid())
                    return START_NOT_STICKY
                }
                if (ensureForeground("Starting…")) {
                    stopRequested = false
                    sendStatus(NodeState.STARTING, "Starting SillyTavern…")
                    worker.execute { runServer() }
                }
            }
            ACTION_STOP -> {
                AppConfig(applicationContext).wasRunningBeforeKill = false
                worker.execute { stopServer(restart = false) }
            }
            ACTION_RESTART -> worker.execute { stopServer(restart = true) }
        }
        // START_NOT_STICKY: Prevents Android from silently reviving Node.js in the background
        // while the phone is locked. On-Demand Resume in MainActivity will restart the server
        // when the user actually opens the app.
        return START_NOT_STICKY
    }

    // ---- internals ----

    private fun runServer() {
        if (controller.isRunning()) return
        val paths = AppPaths(applicationContext)
        try {
            val layout = PayloadManager(applicationContext).getExistingLayout()
            if (stopRequested) {
                finish()
                return
            }

            val appConfig = AppConfig(applicationContext)
            val nodeConfig = NodeConfig.load(applicationContext)
            val stConfig = StConfig.fromFileOrDefault(paths.configFile) {
                applicationContext.assets.open("default_config.yaml")
            }

            explicitPort?.let {
                stConfig.port = it
            }

            val targetPort = explicitPort ?: stConfig.port
            this.port = targetPort
            sendStatus(NodeState.STARTING, "Launching server on port $targetPort…")
            val launchResult = controller.start(layout, stConfig, nodeConfig, appConfig)
            this.port = launchResult.effectivePort

            val isFallback = launchResult.effectivePort != targetPort
            val probeMsg = if (isFallback) {
                "Port $targetPort occupied (fell back to ${launchResult.effectivePort}). Waiting for server…"
            } else {
                "Waiting for server to accept connections…"
            }
            sendStatus(NodeState.STARTING, probeMsg)

            var serverReady = false
            val probeUrl = URL("http://127.0.0.1:${launchResult.effectivePort}")
            val maxProbes = 120 // 120 * 250ms = 30 seconds
            for (i in 0 until maxProbes) {
                if (stopRequested) break
                if (!controller.isRunning()) {
                    break
                }
                try {
                    val conn = probeUrl.openConnection() as HttpURLConnection
                    conn.connectTimeout = 500
                    conn.readTimeout = 500
                    val code = conn.responseCode
                    conn.disconnect()
                    if (code in 200..499) {
                        serverReady = true
                        break
                    }
                } catch (_: Exception) {
                    // Endpoint not listening yet
                }
                try {
                    Thread.sleep(250)
                } catch (_: InterruptedException) {
                    break
                }
            }

            if (stopRequested) {
                controller.stop()
                finish()
                return
            }

            if (!serverReady) {
                if (!controller.isRunning()) {
                    sendStatus(NodeState.ERROR, "Server process terminated unexpectedly")
                } else {
                    sendStatus(NodeState.ERROR, "Server failed to respond within 30 seconds")
                    controller.stop()
                }
                finish()
                return
            }

            AppConfig(applicationContext).wasRunningBeforeKill = true
            val runningMsg = if (isFallback) "Running (port ${launchResult.effectivePort})" else "Running"
            sendStatus(NodeState.RUNNING, runningMsg, controller.pid())

            val isForeground = StApplication.instance?.isAppInForeground ?: false
            if (!isForeground) {
                scheduleBackgroundTimeout()
            }

            // Supervise process exit on a dedicated thread so the worker executor
            // remains free to handle incoming stop() and restart() commands.
            val supervisor = Thread({
                val exit = try {
                    launchResult.process.waitFor()
                } catch (_: Exception) {
                    null
                }
                val wasStopping = stopRequested || status.state == NodeState.STOPPING
                if (wasStopping) {
                    sendStatus(NodeState.STOPPED, "Stopped")
                } else if (exit == 0) {
                    sendStatus(NodeState.STOPPED, "Server exited")
                } else {
                    sendStatus(NodeState.ERROR, "Server exited with code ${exit ?: "?"}")
                }
                finish()
            }, "node-supervisor")
            supervisor.isDaemon = true
            supervisor.start()

        } catch (e: PortInUseException) {
            sendStatus(NodeState.ERROR, e.message ?: "Port in use")
            finish()
        } catch (t: Throwable) {
            sendStatus(NodeState.ERROR, t.message ?: "Start failed")
            finish()
        }
    }

    private fun stopServer(restart: Boolean, reason: String = "Stopped") {
        timeoutManager.cancel()
        val running = controller.isRunning()
        if (running) {
            sendStatus(NodeState.STOPPING, "Stopping…")
            controller.stop()
        }
        stopRequested = !restart
        if (restart) {
            stopRequested = false
            sendStatus(NodeState.STARTING, "Restarting…")
            ensureForeground("Restarting…")
            runServer()
        } else {
            AppConfig(applicationContext).wasRunningBeforeKill = false
            sendStatus(NodeState.STOPPED, reason)
            finish()
        }
    }

    private fun finish() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun sendStatus(state: NodeState, message: String, pid: Long? = null) {
        val newStatus = NodeStatus(state, message, port, pid)
        status = newStatus
        _status.value = newStatus
        val manager = getSystemService(NotificationManager::class.java)
        if (state == NodeState.STOPPED && !newStatus.isActive) {
            manager.cancel(NOTIFICATION_ID)
        } else {
            manager.notify(NOTIFICATION_ID, buildNotification("$state: $message"))
        }
    }

    private fun ensureForeground(message: String): Boolean = try {
        // Android 14+ (API 34) strictly requires FOREGROUND_SERVICE_TYPE_SPECIAL_USE for specialUse services.
        // On Android 8–13, passing this constant throws a NoSuchFieldError, so use the standard 2-argument API.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(message),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(message))
        }
        true
    } catch (t: Throwable) {
        sendStatus(NodeState.ERROR, t.message ?: "Foreground not allowed")
        stopSelf()
        false
    }

    private fun handleBackgroundTimeout() {
        val isForeground = StApplication.instance?.isAppInForeground ?: false
        if (!isForeground && (controller.isRunning() || status.isActive)) {
            AppConfig(applicationContext).wasRunningBeforeKill = false
            sendStatus(NodeState.STOPPING, "Stopping (background timeout)…")
            worker.execute {
                stopServer(restart = false, reason = "Stopped (background timeout)")
            }
        }
    }

    private fun scheduleBackgroundTimeout() {
        val appConfig = AppConfig(applicationContext)
        timeoutManager.schedule(appConfig.backgroundTimeoutMinutes)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, NodeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ST Mobile")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(status.isActive)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "ST Mobile server", NotificationManager.IMPORTANCE_LOW)
        channel.setShowBadge(false)
        channel.enableVibration(false)
        channel.setSound(null, null)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "app.stmobile.action.START"
        const val ACTION_STOP = "app.stmobile.action.STOP"
        const val ACTION_RESTART = "app.stmobile.action.RESTART"
        const val EXTRA_PORT = "app.stmobile.extra.PORT"
        private const val CHANNEL_ID = "st_mobile_node"
        private const val NOTIFICATION_ID = 1001

        // Publishes the active running service instance across all threads immediately
        @Volatile
        private var instance: NodeService? = null

        fun onAppForegrounded() {
            instance?.timeoutManager?.cancel()
        }

        fun onAppBackgrounded(context: Context) {
            instance?.let { service ->
                if (service.controller.isRunning() || service.status.isActive) {
                    service.scheduleBackgroundTimeout()
                }
            }
        }

        private val _status = MutableStateFlow(NodeStatus(NodeState.STOPPED, "Idle", NodeController.DEFAULT_PORT))
        val status: StateFlow<NodeStatus> = _status.asStateFlow()

        fun start(context: Context, port: Int? = null) {
            if (_status.value.state == NodeState.RUNNING && (port == null || port == _status.value.port)) return
            val intent = Intent(context, NodeService::class.java).setAction(ACTION_START)
            if (port != null && port in 1..65535) {
                intent.putExtra(EXTRA_PORT, port)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            if (_status.value.state == NodeState.STOPPED) return
            val intent = Intent(context, NodeService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }

        fun restart(context: Context) {
            val intent = Intent(context, NodeService::class.java).setAction(ACTION_RESTART)
            context.startService(intent)
        }
    }
}
