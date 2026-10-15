package app.stmobile.foreground

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import app.stmobile.R
import app.stmobile.StApplication
import app.stmobile.models.AppConfig
import app.stmobile.models.NodeState
import app.stmobile.models.NodeStatus
import app.stmobile.node.NodeController
import app.stmobile.logging.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Hosts the Android Foreground Service session for ST-Mobile.
 * Strictly active-only lifecycle: runs while the server is active (STARTING, RUNNING, STOPPING).
 * Manages WakeLockHelper, BackgroundTimeoutManager, and ServiceNotificationHelper.
 */
class ForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private val lock = Any()

    private lateinit var wakeLockHelper: WakeLockHelper
    private val timeoutManager = BackgroundTimeoutManager(
        onTimeout = { handleBackgroundTimeout() },
    )

    @Volatile
    private var isVectorizing = false

    private val generationTracker = GenerationTracker(
        onFirstStarted = {
            wakeLockHelper.acquireToken(WakeLockHelper.TOKEN_INFERENCE)
            timeoutManager.pause()
            NodeController.updateGeneratingState(true)
            updateNotification()
        },
        onAllEnded = {
            wakeLockHelper.releaseToken(WakeLockHelper.TOKEN_INFERENCE)
            val isForeground = StApplication.instance?.isAppInForeground ?: false
            if (!isForeground) {
                if (!isVectorizing) {
                    timeoutManager.resume()
                }
                if (AppConfig(applicationContext).notifyOnCompletion) {
                    ServiceNotificationHelper.showCompletionNotification(
                        context = this,
                        title = getString(R.string.notification_completion_title),
                        message = getString(R.string.notification_completion_msg),
                    )
                }
            }
            NodeController.updateGeneratingState(false)
            updateNotification()
        },
    )

    @Volatile
    private var hasActiveSession = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppLog.i("ForegroundService", "Service onCreate")
        wakeLockHelper = WakeLockHelper(this)
        ServiceNotificationHelper.createChannel(this)
        instance = this

        serviceScope.launch {
            NodeController.status.collect { status ->
                if (status.isActive) {
                    hasActiveSession = true
                    updateNotification()
                } else if (hasActiveSession && (status.state == NodeState.STOPPED || status.state == NodeState.ERROR)) {
                    hasActiveSession = false
                    cleanupSession()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    ServiceNotificationHelper.cancelNotification(this@ForegroundService)
                    stopSelf()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLog.i("ForegroundService", "Service onDestroy")
        serviceScope.cancel()
        cleanupSession()
        timeoutManager.shutdown()
        if (hasActiveSession) {
            hasActiveSession = false
            NodeController.forceStop()
        }
        if (instance === this) {
            instance = null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLog.i("ForegroundService", "onStartCommand: action=${intent?.action}, startId=$startId")
        when (intent?.action) {
            ACTION_START -> {
                hasActiveSession = true
                val notification = ServiceNotificationHelper.buildNotification(
                    this,
                    NodeController.status.value,
                    customText = getString(R.string.status_starting),
                )
                ServiceNotificationHelper.startForeground(this, notification)

                val port = if (intent.hasExtra(EXTRA_PORT)) {
                    val p = intent.getIntExtra(EXTRA_PORT, -1)
                    if (p in 1..65535) p else null
                } else null

                NodeController.executeStart(
                    context = applicationContext,
                    explicitPort = port,
                    onIpcStarted = { onGenerationStarted("ipc") },
                    onIpcEnded = { onGenerationEnded("ipc") },
                    onVectorStarted = { onVectorStarted() },
                    onVectorEnded = { onVectorEnded() },
                    onIpcDisconnected = { onSourceDisconnected("ipc") },
                    onProcessTerminated = { onProcessTerminated() },
                )
            }
            ACTION_STOP -> {
                cleanupSession()
                NodeController.executeStop(applicationContext)
            }
            ACTION_RESTART -> {
                hasActiveSession = true
                val notification = ServiceNotificationHelper.buildNotification(
                    this,
                    NodeController.status.value,
                    customText = getString(R.string.status_restarting),
                )
                ServiceNotificationHelper.startForeground(this, notification)
                cleanupSession()
                NodeController.executeStop(applicationContext) {
                    val port = if (intent.hasExtra(EXTRA_PORT)) {
                        val p = intent.getIntExtra(EXTRA_PORT, -1)
                        if (p in 1..65535) p else null
                    } else null

                    NodeController.executeStart(
                        context = applicationContext,
                        explicitPort = port,
                        onIpcStarted = { onGenerationStarted("ipc") },
                        onIpcEnded = { onGenerationEnded("ipc") },
                        onVectorStarted = { onVectorStarted() },
                        onVectorEnded = { onVectorEnded() },
                        onIpcDisconnected = { onSourceDisconnected("ipc") },
                        onProcessTerminated = { onProcessTerminated() },
                    )
                }
            }
        }
        return START_NOT_STICKY
    }

    fun onGenerationStarted(source: String) {
        generationTracker.onStarted(source)
    }

    fun onGenerationEnded(source: String) {
        generationTracker.onEnded(source)
    }

    fun onSourceDisconnected(source: String) {
        onGenerationEnded(source)
    }

    fun onProcessTerminated() = synchronized(lock) {
        AppLog.i("ForegroundService", "onProcessTerminated received; cleaning up background timers and wakelock")
        timeoutManager.cancel()
        generationTracker.reset(notify = false)
        wakeLockHelper.forceRelease()
        NodeController.updateGeneratingState(false)
    }

    fun onVectorStarted() = synchronized(lock) {
        AppLog.i("ForegroundService", "onVectorStarted")
        isVectorizing = true
        wakeLockHelper.acquireToken(WakeLockHelper.TOKEN_VECTOR)
        timeoutManager.pause()
        updateNotification()
    }

    fun onVectorEnded() = synchronized(lock) {
        if (!isVectorizing) return@synchronized
        AppLog.i("ForegroundService", "onVectorEnded")
        isVectorizing = false
        wakeLockHelper.releaseToken(WakeLockHelper.TOKEN_VECTOR)
        val isForeground = StApplication.instance?.isAppInForeground ?: false
        if (!isForeground) {
            if (!generationTracker.isGenerating) {
                timeoutManager.resume()
            }
            if (AppConfig(applicationContext).notifyOnCompletion) {
                ServiceNotificationHelper.showCompletionNotification(
                    context = this,
                    title = getString(R.string.notification_vector_completion_title),
                    message = getString(R.string.notification_vector_completion_msg),
                )
            }
        }
        updateNotification()
    }

    fun onAppForegrounded() = synchronized(lock) {
        AppLog.d("ForegroundService", "onAppForegrounded; cancelling idle timeout and releasing idle wakelock")
        timeoutManager.cancel()
        wakeLockHelper.releaseToken(WakeLockHelper.TOKEN_IDLE_TIMEOUT)
        ServiceNotificationHelper.cancelCompletionNotification(this)
        updateNotification()
    }

    fun onAppBackgrounded(context: Context) {
        if (NodeController.isRunning() || NodeController.status.value.isActive) {
            val appConfig = AppConfig(context)
            val timeoutMinutes = appConfig.backgroundTimeoutMinutes
            AppLog.i("ForegroundService", "onAppBackgrounded (generating=${generationTracker.isGenerating}, vectorizing=$isVectorizing, timeoutMinutes=$timeoutMinutes)")
            synchronized(lock) {
                val sources = generationTracker.activeSources
                if (sources == setOf("webview") && !sources.contains("ipc")) {
                    generationTracker.clearSource("webview")
                }
                if (timeoutMinutes > 0) {
                    val timeoutMs = timeoutMinutes * 60 * 1000L + 60_000L
                    wakeLockHelper.acquireToken(WakeLockHelper.TOKEN_IDLE_TIMEOUT, timeoutMs)
                    timeoutManager.schedule(timeoutMinutes)
                    if (generationTracker.isGenerating || isVectorizing) {
                        timeoutManager.pause()
                    }
                } else {
                    timeoutManager.cancel()
                    wakeLockHelper.releaseToken(WakeLockHelper.TOKEN_IDLE_TIMEOUT)
                }
            }
            updateNotification()
        }
    }

    fun onTimeoutConfigChanged(newMinutes: Int) = synchronized(lock) {
        AppLog.i("ForegroundService", "onTimeoutConfigChanged(newMinutes=$newMinutes)")
        val isForeground = StApplication.instance?.isAppInForeground ?: false
        if (!isForeground && (NodeController.isRunning() || NodeController.status.value.isActive)) {
            if (newMinutes > 0) {
                val timeoutMs = newMinutes * 60 * 1000L + 60_000L
                wakeLockHelper.acquireToken(WakeLockHelper.TOKEN_IDLE_TIMEOUT, timeoutMs)
                timeoutManager.schedule(newMinutes)
                if (generationTracker.isGenerating || isVectorizing) {
                    timeoutManager.pause()
                }
            } else {
                timeoutManager.cancel()
                wakeLockHelper.releaseToken(WakeLockHelper.TOKEN_IDLE_TIMEOUT)
            }
            updateNotification()
        }
    }

    private fun handleBackgroundTimeout() {
        synchronized(lock) {
            wakeLockHelper.releaseToken(WakeLockHelper.TOKEN_IDLE_TIMEOUT)
            val isForeground = StApplication.instance?.isAppInForeground ?: false
            val isBusy = generationTracker.isGenerating || isVectorizing

            AppLog.w("ForegroundService", "handleBackgroundTimeout triggered (isForeground=$isForeground, isBusy=$isBusy)")
            if (!isForeground && !isBusy && (NodeController.isRunning() || NodeController.status.value.isActive)) {
                AppConfig(applicationContext).wasRunningBeforeKill = false
                cleanupSession()
                NodeController.executeStop(applicationContext)
            }
        }
    }

    private fun updateNotification() {
        if (!hasActiveSession) return
        val (isGenerating, isVec, targetEpoch, currentStatus) = synchronized(lock) {
            val generating = generationTracker.isGenerating
            val vec = isVectorizing
            val epoch = if (!generating && !vec && timeoutManager.isScheduled && !timeoutManager.isPaused) {
                timeoutManager.targetEpochMs
            } else {
                0L
            }
            NotificationState(generating, vec, epoch, NodeController.status.value)
        }

        val notification = ServiceNotificationHelper.buildNotification(
            context = this,
            status = currentStatus,
            isGenerating = isGenerating,
            isVectorizing = isVec,
            idleTargetEpochMs = targetEpoch,
        )
        ServiceNotificationHelper.updateNotification(this, notification)
    }

    private data class NotificationState(
        val isGenerating: Boolean,
        val isVectorizing: Boolean,
        val targetEpochMs: Long,
        val status: NodeStatus,
    )

    private fun cleanupSession() {
        synchronized(lock) {
            isVectorizing = false
            timeoutManager.cancel()
            generationTracker.reset(notify = false)
            wakeLockHelper.forceRelease()
        }
    }

    companion object {
        const val ACTION_START = "app.stmobile.action.START"
        const val ACTION_STOP = "app.stmobile.action.STOP"
        const val ACTION_RESTART = "app.stmobile.action.RESTART"
        const val EXTRA_PORT = "app.stmobile.extra.PORT"

        @Volatile
        var instance: ForegroundService? = null
            private set

        fun start(context: Context, port: Int? = null) {
            if (NodeController.status.value.state == NodeState.RUNNING &&
                (port == null || port == NodeController.status.value.port)
            ) return

            val intent = Intent(context, ForegroundService::class.java).setAction(ACTION_START)
            if (port != null && port in 1..65535) {
                intent.putExtra(EXTRA_PORT, port)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            if (NodeController.status.value.state == NodeState.STOPPED) return
            val intent = Intent(context, ForegroundService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }

        fun restart(context: Context) {
            val intent = Intent(context, ForegroundService::class.java).setAction(ACTION_RESTART)
            context.startService(intent)
        }

        fun onGenerationStarted(source: String) {
            instance?.onGenerationStarted(source)
        }

        fun onGenerationEnded(source: String) {
            instance?.onGenerationEnded(source)
        }

        fun onVectorStarted() {
            instance?.onVectorStarted()
        }

        fun onVectorEnded() {
            instance?.onVectorEnded()
        }

        fun onTimeoutConfigChanged(newMinutes: Int) {
            instance?.onTimeoutConfigChanged(newMinutes)
        }

        fun onProcessTerminated() {
            instance?.onProcessTerminated()
        }

        fun onAppForegrounded() {
            instance?.onAppForegrounded()
        }

        fun onAppBackgrounded(context: Context) {
            instance?.onAppBackgrounded(context)
        }
    }
}
