package app.stmobile.foreground

/**
 * Thread-safe tracker for multi-source LLM generation sessions.
 * Manages active source tokens (e.g. "webview", "ipc") and fires
 * edge-triggered callbacks when entering and leaving the generating state.
 */
class GenerationTracker(
    private val onFirstStarted: () -> Unit,
    private val onAllEnded: () -> Unit,
) {
    private val lock = Any()
    private val sourceCounts = mutableMapOf<String, Int>()

    val activeSources: Set<String>
        get() = synchronized(lock) { sourceCounts.keys.toSet() }

    val isGenerating: Boolean
        get() = synchronized(lock) { sourceCounts.isNotEmpty() }

    val activeCount: Int
        get() = synchronized(lock) { sourceCounts.size }

    /**
     * Registers a generation start for the given source token with reference counting.
     * Returns true if this was an edge transition (0 -> 1 active sources).
     */
    fun onStarted(source: String): Boolean {
        var shouldNotifyFirstStarted = false
        val edgeTransition = synchronized(lock) {
            val wasGenerating = sourceCounts.isNotEmpty()
            val current = sourceCounts[source] ?: 0
            sourceCounts[source] = current + 1
            if (!wasGenerating && sourceCounts.isNotEmpty()) {
                shouldNotifyFirstStarted = true
                true
            } else {
                false
            }
        }
        if (shouldNotifyFirstStarted) {
            onFirstStarted()
        }
        return edgeTransition
    }

    /**
     * Registers a generation end for the given source token with reference counting.
     * Returns true if this was an edge transition (1 -> 0 active sources).
     */
    fun onEnded(source: String): Boolean {
        var shouldNotifyAllEnded = false
        val edgeTransition = synchronized(lock) {
            val wasGenerating = sourceCounts.isNotEmpty()
            val current = sourceCounts[source] ?: return false
            if (current > 1) {
                sourceCounts[source] = current - 1
                false
            } else {
                sourceCounts.remove(source)
                if (wasGenerating && sourceCounts.isEmpty()) {
                    shouldNotifyAllEnded = true
                    true
                } else {
                    false
                }
            }
        }
        if (shouldNotifyAllEnded) {
            onAllEnded()
        }
        return edgeTransition
    }

    /**
     * Completely removes all reference counts for [source].
     * Returns true if this caused an edge transition (1 -> 0 active sources).
     */
    fun clearSource(source: String): Boolean {
        var shouldNotifyAllEnded = false
        val edgeTransition = synchronized(lock) {
            val wasGenerating = sourceCounts.isNotEmpty()
            val removed = sourceCounts.remove(source)
            if (removed != null && wasGenerating && sourceCounts.isEmpty()) {
                shouldNotifyAllEnded = true
                true
            } else {
                false
            }
        }
        if (shouldNotifyAllEnded) {
            onAllEnded()
        }
        return edgeTransition
    }

    /**
     * Clears all active source tokens.
     * If [notify] is true and generation was active, fires [onAllEnded].
     * Suppressing notifications ([notify] = false) is useful during session cleanup
     * to avoid triggering unwanted timer restarts or state transitions.
     */
    fun reset(notify: Boolean = true): Boolean {
        var shouldNotifyAllEnded = false
        val wasGenerating = synchronized(lock) {
            val generating = sourceCounts.isNotEmpty()
            sourceCounts.clear()
            if (generating && notify) {
                shouldNotifyAllEnded = true
            }
            generating
        }
        if (shouldNotifyAllEnded) {
            onAllEnded()
        }
        return wasGenerating
    }
}
