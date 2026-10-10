package app.stmobile.foreground

import app.stmobile.logging.AppLog
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Manages the background idle countdown timer to gracefully stop ForegroundService
 * when the app remains backgrounded past the configured time limit.
 * Supports pausing and resuming during active generation.
 */
class BackgroundTimeoutManager(
    private val scheduler: ScheduledExecutorService = ScheduledThreadPoolExecutor(1) { r ->
        Thread(r, "node-timeout").apply { isDaemon = true }
    }.apply {
        // Purges canceled timer tasks from memory immediately
        removeOnCancelPolicy = true
    },
    private val onTimeout: () -> Unit,
) {

    @Volatile
    private var scheduledTask: ScheduledFuture<*>? = null

    @Volatile
    var isScheduled: Boolean = false
        private set

    @Volatile
    var isPaused: Boolean = false
        private set

    @Volatile
    var targetEpochMs: Long = 0L
        private set

    @Volatile
    var savedRemainingMillis: Long = 0L
        private set

    @Volatile
    private var targetMonotonicMs: Long = 0L

    private fun nowMonotonicMs(): Long = System.nanoTime() / 1_000_000L

    /**
     * Schedules the background timeout countdown.
     * If [timeoutDuration] <= 0, cancels any pending timeout and disables the timer.
     */
    @Synchronized
    fun schedule(timeoutDuration: Int, unit: TimeUnit = TimeUnit.MINUTES) {
        cancel()
        if (timeoutDuration <= 0) return

        val totalMs = unit.toMillis(timeoutDuration.toLong())
        savedRemainingMillis = totalMs
        val nowMono = nowMonotonicMs()
        targetMonotonicMs = nowMono + totalMs
        targetEpochMs = System.currentTimeMillis() + totalMs
        isScheduled = true
        isPaused = false
        AppLog.d("BackgroundTimeoutManager", "Scheduled countdown: ${totalMs}ms (duration=$timeoutDuration $unit)")

        scheduledTask = scheduler.schedule({
            val shouldFire = synchronized(this) {
                if (!isScheduled || isPaused) {
                    false
                } else {
                    isScheduled = false
                    isPaused = false
                    targetEpochMs = 0L
                    targetMonotonicMs = 0L
                    savedRemainingMillis = 0L
                    scheduledTask = null
                    true
                }
            }
            if (shouldFire) {
                AppLog.i("BackgroundTimeoutManager", "Background timeout expired; triggering callback")
                onTimeout()
            }
        }, totalMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Pauses the timer countdown, preserving the remaining duration.
     * Called when active inference generation starts.
     */
    @Synchronized
    fun pause() {
        if (!isScheduled || isPaused) return
        val currentMonoMs = nowMonotonicMs()
        savedRemainingMillis = maxOf(0L, targetMonotonicMs - currentMonoMs)
        scheduledTask?.cancel(false)
        scheduledTask = null
        isPaused = true
        targetEpochMs = 0L
        targetMonotonicMs = 0L
        AppLog.d("BackgroundTimeoutManager", "Paused countdown; remaining: ${savedRemainingMillis}ms")
    }

    /**
     * Resumes the timer countdown with the remaining duration.
     * Called when active inference generation completes.
     */
    @Synchronized
    fun resume() {
        if (!isScheduled || !isPaused || savedRemainingMillis <= 0L) return

        val durationMs = savedRemainingMillis
        val nowMono = nowMonotonicMs()
        targetMonotonicMs = nowMono + durationMs
        targetEpochMs = System.currentTimeMillis() + durationMs
        isPaused = false
        AppLog.d("BackgroundTimeoutManager", "Resumed countdown with remaining: ${durationMs}ms")

        scheduledTask = scheduler.schedule({
            val shouldFire = synchronized(this) {
                if (!isScheduled || isPaused) {
                    false
                } else {
                    isScheduled = false
                    isPaused = false
                    targetEpochMs = 0L
                    targetMonotonicMs = 0L
                    savedRemainingMillis = 0L
                    scheduledTask = null
                    true
                }
            }
            if (shouldFire) {
                AppLog.i("BackgroundTimeoutManager", "Background timeout expired; triggering callback")
                onTimeout()
            }
        }, durationMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Returns remaining milliseconds, or 0 if not scheduled.
     */
    fun getRemainingMillis(): Long {
        return when {
            !isScheduled -> 0L
            isPaused -> savedRemainingMillis
            else -> maxOf(0L, targetMonotonicMs - nowMonotonicMs())
        }
    }

    /**
     * Cancels any pending countdown timer.
     */
    @Synchronized
    fun cancel() {
        scheduledTask?.cancel(false)
        scheduledTask = null
        isScheduled = false
        isPaused = false
        targetEpochMs = 0L
        targetMonotonicMs = 0L
        savedRemainingMillis = 0L
        AppLog.d("BackgroundTimeoutManager", "Cancelled countdown")
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
