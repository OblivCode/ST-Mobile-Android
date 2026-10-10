package app.stmobile.foreground

import android.content.Context
import android.os.PowerManager
import app.stmobile.logging.AppLog

interface WakeLockAcquirer {
    fun acquire(timeoutMs: Long)
    fun release()
    val isHeld: Boolean
}

class AndroidWakeLockAcquirer(context: Context) : WakeLockAcquirer {
    private val wakeLock: PowerManager.WakeLock? = try {
        val pm = context.getSystemService(PowerManager::class.java)
        pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "stmobile:inference")?.apply {
            setReferenceCounted(false)
        }
    } catch (_: Exception) {
        null
    }

    override fun acquire(timeoutMs: Long) {
        try {
            wakeLock?.acquire(timeoutMs)
        } catch (_: Exception) {}
    }

    override fun release() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock.release()
            }
        } catch (_: Exception) {}
    }

    override val isHeld: Boolean
        get() = wakeLock?.isHeld == true
}

/**
 * Manages CPU wakefulness during active LLM inference.
 * Acquires PowerManager.PARTIAL_WAKE_LOCK strictly during active generation,
 * with a 15-minute maximum timeout safety cap.
 */
class WakeLockHelper(
    private val acquirer: WakeLockAcquirer,
) {
    constructor(context: Context) : this(AndroidWakeLockAcquirer(context.applicationContext))

    val isHeld: Boolean
        get() = acquirer.isHeld

    @Synchronized
    fun acquire(timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
        AppLog.d("WakeLockHelper", "Acquiring PARTIAL_WAKE_LOCK (timeoutMs=$timeoutMs)")
        acquirer.acquire(timeoutMs)
    }

    @Synchronized
    fun release() {
        if (acquirer.isHeld) {
            AppLog.d("WakeLockHelper", "Releasing PARTIAL_WAKE_LOCK")
            acquirer.release()
        }
    }

    @Synchronized
    fun forceRelease() {
        AppLog.d("WakeLockHelper", "Force-releasing PARTIAL_WAKE_LOCK")
        try {
            acquirer.release()
        } catch (_: Exception) {}
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 15 * 60 * 1000L // 15 minutes
    }
}
