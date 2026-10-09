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
    private val activeSources = mutableSetOf<String>()

    val isGenerating: Boolean get() = synchronized(lock) { activeSources.isNotEmpty() }
    val activeCount: Int get() = synchronized(lock) { activeSources.size }

    /**
     * Registers a generation start for the given source token.
     * Returns true if this was an edge transition (0 -> 1 active sources).
     */
    fun onStarted(source: String): Boolean = synchronized(lock) {
        val wasGenerating = activeSources.isNotEmpty()
        activeSources.add(source)
        if (!wasGenerating && activeSources.isNotEmpty()) {
            onFirstStarted()
            true
        } else {
            false
        }
    }

    /**
     * Registers a generation end for the given source token.
     * Returns true if this was an edge transition (1 -> 0 active sources).
     */
    fun onEnded(source: String): Boolean = synchronized(lock) {
        val wasGenerating = activeSources.isNotEmpty()
        activeSources.remove(source)
        if (wasGenerating && activeSources.isEmpty()) {
            onAllEnded()
            true
        } else {
            false
        }
    }

    /**
     * Clears all active source tokens and fires onAllEnded if previously generating.
     * Useful for supervisor process termination or hard service resets.
     */
    fun reset(): Boolean = synchronized(lock) {
        val wasGenerating = activeSources.isNotEmpty()
        activeSources.clear()
        if (wasGenerating) {
            onAllEnded()
            true
        } else {
            false
        }
    }
}
