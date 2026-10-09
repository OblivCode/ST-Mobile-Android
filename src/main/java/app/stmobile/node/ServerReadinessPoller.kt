package app.stmobile.node

import java.net.HttpURLConnection
import java.net.URL

enum class ReadinessStatus {
    READY,
    TIMEOUT,
    PROCESS_EXITED,
    CANCELLED,
}

object ServerReadinessPoller {
    /**
     * Polls the target port on 127.0.0.1 until HTTP 200..499 is received,
     * or the process terminates, stop is requested, or timeout expires.
     */
    fun poll(
        port: Int,
        maxWaitMs: Long = 30_000,
        intervalMs: Long = 250,
        probeTimeoutMs: Int = 500,
        isProcessAlive: () -> Boolean = { true },
        isStopRequested: () -> Boolean = { false },
    ): ReadinessStatus {
        val probeUrl = URL("http://127.0.0.1:$port")
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            if (isStopRequested()) {
                return ReadinessStatus.CANCELLED
            }
            if (!isProcessAlive()) {
                return ReadinessStatus.PROCESS_EXITED
            }

            try {
                val conn = probeUrl.openConnection() as HttpURLConnection
                conn.connectTimeout = probeTimeoutMs
                conn.readTimeout = probeTimeoutMs
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..499) {
                    return ReadinessStatus.READY
                }
            } catch (_: Exception) {
                // Endpoint not accepting connections yet
            }

            try {
                Thread.sleep(intervalMs)
            } catch (_: InterruptedException) {
                return ReadinessStatus.CANCELLED
            }
        }

        return ReadinessStatus.TIMEOUT
    }
}
