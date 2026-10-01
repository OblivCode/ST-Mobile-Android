package app.stmobile.node

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import app.stmobile.AppPaths
import app.stmobile.MainActivity
import app.stmobile.models.AppConfig
import app.stmobile.models.NodeConfig
import app.stmobile.models.NodeState
import app.stmobile.models.NodeStatus
import app.stmobile.models.NodeStatusListener
import app.stmobile.models.StConfig
import app.stmobile.sillytavern.PayloadManager
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/**
 * Hosts the SillyTavern Node process as a `specialUse` foreground service so
 * the local server survives the app being backgrounded.
 */
class NodeService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): NodeService = this@NodeService
    }

    private val binder = LocalBinder()
    private val listeners = CopyOnWriteArraySet<NodeStatusListener>()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "node-service") }
    private val controller by lazy { NodeController(applicationContext) }

    @Volatile
    private var status = NodeStatus(NodeState.STOPPED, "Idle", NodeController.DEFAULT_PORT)
    @Volatile
    private var stopRequested = false
    @Volatile
    private var explicitPort: Int? = null
    @Volatile
    private var port = NodeController.DEFAULT_PORT

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdownNow()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (intent.hasExtra(EXTRA_PORT)) {
                    val p = intent.getIntExtra(EXTRA_PORT, -1)
                    explicitPort = if (p in 1..65535) p else null
                }
                if (ensureForeground("Starting…")) {
                    stopRequested = false
                    sendStatus(NodeState.STARTING, "Starting SillyTavern…")
                    worker.execute { runServer() }
                }
            }
            ACTION_STOP -> worker.execute { stopServer(restart = false) }
            ACTION_RESTART -> worker.execute { stopServer(restart = true) }
        }
        return START_STICKY
    }

    // ---- public surface for the UI ----

    fun registerListener(listener: NodeStatusListener) {
        listeners.add(listener)
        listener.onStatus(status)
    }

    fun unregisterListener(listener: NodeStatusListener) {
        listeners.remove(listener)
    }

    fun currentStatus(): NodeStatus = status

    fun restart() {
        worker.execute { stopServer(restart = true) }
    }

    fun stop() {
        worker.execute { stopServer(restart = false) }
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

            sendStatus(NodeState.STARTING, "Launching server on port ${stConfig.port}…")
            val launchResult = controller.start(layout, stConfig, nodeConfig, appConfig)
            this.port = launchResult.effectivePort

            // Internal HTTP readiness probe: wait for the server to accept connections
            sendStatus(NodeState.STARTING, "Waiting for server to accept connections…")
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

            sendStatus(NodeState.RUNNING, "Running", controller.pid())

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

    private fun stopServer(restart: Boolean) {
        val running = controller.isRunning()
        if (running) {
            sendStatus(NodeState.STOPPING, "Stopping…")
            controller.stop()
        }
        stopRequested = !restart
        if (restart) {
            stopRequested = false
            sendStatus(NodeState.STARTING, "Restarting…")
            startForeground(NOTIFICATION_ID, buildNotification("Restarting…"))
            runServer()
        } else {
            sendStatus(NodeState.STOPPED, "Stopped")
            finish()
        }
    }

    private fun finish() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun sendStatus(state: NodeState, message: String, pid: Long? = null) {
        status = NodeStatus(state, message, port, pid)
        for (listener in listeners) listener.onStatus(status)
        val manager = getSystemService(NotificationManager::class.java)
        if (state == NodeState.STOPPED && !status.isActive) {
            manager.cancel(NOTIFICATION_ID)
        } else {
            manager.notify(NOTIFICATION_ID, buildNotification("$state: $message"))
        }
    }

    private fun ensureForeground(message: String): Boolean = try {
        startForeground(NOTIFICATION_ID, buildNotification(message))
        true
    } catch (t: Throwable) {
        sendStatus(NodeState.ERROR, t.message ?: "Foreground not allowed")
        stopSelf()
        false
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

        fun start(context: Context, port: Int? = null) {
            val intent = Intent(context, NodeService::class.java).setAction(ACTION_START)
            if (port != null && port in 1..65535) {
                intent.putExtra(EXTRA_PORT, port)
            }
            context.startForegroundService(intent)
        }
    }
}
