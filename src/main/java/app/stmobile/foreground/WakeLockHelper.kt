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
 * Manages CPU wakefulness during active LLM inference, vector indexing,
 * and background idle countdowns.
 * Uses named token holders so releasing one token does not prematurely release
 * the wake lock if other tokens are still active.
 */
class WakeLockHelper(
    private val acquirer: WakeLockAcquirer,
) {
    constructor(context: Context) : this(AndroidWakeLockAcquirer(context.applicationContext))

    private val activeTokens = mutableMapOf<String, Long>()

    val isHeld: Boolean
        @Synchronized
        get() = acquirer.isHeld

    /**
     * Backward-compatible acquire for a default inference token.
     */
    @Synchronized
    fun acquire(timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
        acquireToken(TOKEN_INFERENCE, timeoutMs)
    }

    /**
     * Backward-compatible release for the default inference token.
     */
    @Synchronized
    fun release() {
        releaseToken(TOKEN_INFERENCE)
    }

    /**
     * Acquires or renews a named wake lock token with a specific timeout.
     */
    @Synchronized
    fun acquireToken(token: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
        AppLog.d("WakeLockHelper", "Acquiring token '$token' (timeoutMs=$timeoutMs)")
        activeTokens[token] = timeoutMs
        val maxTimeout = activeTokens.values.maxOrNull() ?: timeoutMs
        acquirer.acquire(maxTimeout)
    }

    /**
     * Releases a named wake lock token.
     * The underlying wake lock is released only when no active tokens remain.
     */
    @Synchronized
    fun releaseToken(token: String) {
        val removed = activeTokens.remove(token)
        if (removed != null) {
            AppLog.d("WakeLockHelper", "Released token '$token'; remaining active tokens: ${activeTokens.keys}")
        }
        if (activeTokens.isEmpty()) {
            if (acquirer.isHeld) {
                AppLog.d("WakeLockHelper", "All tokens released; releasing underlying PARTIAL_WAKE_LOCK")
                acquirer.release()
            }
        } else {
            val remainingMax = activeTokens.values.maxOrNull() ?: DEFAULT_TIMEOUT_MS
            acquirer.acquire(remainingMax)
        }
    }

    /**
     * Returns true if a specific token is currently active.
     */
    @Synchronized
    fun hasToken(token: String): Boolean = activeTokens.containsKey(token)

    /**
     * Releases all tokens and forces the underlying wake lock to release.
     */
    @Synchronized
    fun forceRelease() {
        AppLog.d("WakeLockHelper", "Force-releasing all tokens and PARTIAL_WAKE_LOCK")
        activeTokens.clear()
        try {
            acquirer.release()
        } catch (_: Exception) {}
    }

    companion object {
        const val TOKEN_INFERENCE = "inference"
        const val TOKEN_IDLE_TIMEOUT = "idle_timeout"
        const val TOKEN_VECTOR = "vector"
        const val DEFAULT_TIMEOUT_MS = 15 * 60 * 1000L // 15 minutes
    }
}
