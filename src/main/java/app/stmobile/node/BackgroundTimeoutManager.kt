package app.stmobile.node

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Manages the background idle countdown timer to gracefully stop NodeService
 * when the app remains backgrounded past the configured time limit.
 */
class BackgroundTimeoutManager(
    private val scheduler: ScheduledExecutorService = ScheduledThreadPoolExecutor(1) { r ->
        Thread(r, "node-timeout").apply { isDaemon = true }
    }.apply {
        // Purges canceled timer tasks from memory immediately instead of holding references until delay elapses
        removeOnCancelPolicy = true
    },
    private val onTimeout: () -> Unit,
) {

    // @Volatile ensures thread-safe atomic visibility between the caller thread and executor thread
    @Volatile
    private var scheduledTask: ScheduledFuture<*>? = null

    @Volatile
    var isScheduled: Boolean = false
        private set

    /**
     * Schedules the background timeout countdown.
     * If [timeoutMinutes] <= 0, cancels any pending timeout and disables the timer.
     */
    @Synchronized
    fun schedule(timeoutMinutes: Int, unit: TimeUnit = TimeUnit.MINUTES) {
        cancel()
        if (timeoutMinutes <= 0) return

        isScheduled = true
        scheduledTask = scheduler.schedule({
            synchronized(this) {
                isScheduled = false
                scheduledTask = null
            }
            onTimeout()
        }, timeoutMinutes.toLong(), unit)
    }

    /**
     * Cancels any pending countdown timer.
     */
    @Synchronized
    fun cancel() {
        scheduledTask?.cancel(false)
        scheduledTask = null
        isScheduled = false
    }

    /**
     * Shuts down the scheduler.
     */
    @Synchronized
    fun shutdown() {
        cancel()
        scheduler.shutdownNow()
    }
}
