package app.stmobile.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import app.stmobile.models.NodeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Minimal Android Foreground Service wrapper.
 * Strictly active-only lifecycle: runs while the server is active (`STARTING`, `RUNNING`, `STOPPING`),
 * with instant [stopForeground] and [stopSelf] on stop or error (zero idle background footprint).
 */
class NodeForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    @Volatile
    private var hasActiveSession = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ServiceNotificationHelper.createChannel(this)

        serviceScope.launch {
            ServerLifecycleManager.status.collect { status ->
                if (status.isActive) {
                    hasActiveSession = true
                    ServiceNotificationHelper.updateNotification(
                        this@NodeForegroundService,
                        ServiceNotificationHelper.buildNotification(
                            this@NodeForegroundService,
                            status,
                            "${status.state}: ${status.message}",
                        ),
                    )
                } else if (hasActiveSession && (status.state == NodeState.STOPPED || status.state == NodeState.ERROR)) {
                    hasActiveSession = false
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    ServiceNotificationHelper.cancelNotification(this@NodeForegroundService)
                    stopSelf()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        if (hasActiveSession) {
            hasActiveSession = false
            ServerLifecycleManager.forceStop()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                hasActiveSession = true
                val notification = ServiceNotificationHelper.buildNotification(
                    this,
                    ServerLifecycleManager.status.value,
                    "Starting…",
                )
                ServiceNotificationHelper.startForeground(this, notification)

                val port = if (intent.hasExtra(EXTRA_PORT)) {
                    val p = intent.getIntExtra(EXTRA_PORT, -1)
                    if (p in 1..65535) p else null
                } else null

                ServerLifecycleManager.executeStart(applicationContext, port)
            }
            ACTION_STOP -> {
                ServerLifecycleManager.executeStop(applicationContext)
            }
            ACTION_RESTART -> {
                hasActiveSession = true
                val notification = ServiceNotificationHelper.buildNotification(
                    this,
                    ServerLifecycleManager.status.value,
                    "Restarting…",
                )
                ServiceNotificationHelper.startForeground(this, notification)
                ServerLifecycleManager.executeRestart(applicationContext)
            }
        }
        return START_NOT_STICKY
    }

    companion object {
        const val ACTION_START = "app.stmobile.action.START"
        const val ACTION_STOP = "app.stmobile.action.STOP"
        const val ACTION_RESTART = "app.stmobile.action.RESTART"
        const val EXTRA_PORT = "app.stmobile.extra.PORT"
    }
}
