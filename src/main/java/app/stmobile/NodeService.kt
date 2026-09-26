package app.stmobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Hosts the SillyTavern Node process as a `specialUse` foreground service so
 * the local server survives the app being backgrounded (PLAN.md §6).
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
                port = intent.getIntExtra(EXTRA_PORT, port).takeIf { it in 1..65535 } ?: NodeController.DEFAULT_PORT
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
        try {
            val layout = PayloadManager(applicationContext).ensureExtracted { message ->
                sendStatus(NodeState.STARTING, message)
            }
            if (stopRequested) {
                finish()
                return
            }
            sendStatus(NodeState.STARTING, "Launching server on port $port…")
            val process = controller.start(layout, port)
            sendStatus(NodeState.RUNNING, "Running", controller.pid())

            val exit = try {
                process.waitFor()
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
        } catch (t: Throwable) {
            sendStatus(NodeState.ERROR, t.message ?: "Start failed")
        } finally {
            if (status.state != NodeState.RUNNING) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
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
            // ensure foreground stays up
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

        fun start(context: Context, port: Int = NodeController.DEFAULT_PORT) {
            val intent = Intent(context, NodeService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PORT, port)
            context.startForegroundService(intent)
        }
    }
}