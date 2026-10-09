package app.stmobile.node

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

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
        val startNano = System.nanoTime()
        val maxWaitNanos = TimeUnit.MILLISECONDS.toNanos(maxWaitMs)

        while (System.nanoTime() - startNano < maxWaitNanos) {
            if (isStopRequested()) {
                return ReadinessStatus.CANCELLED
            }
            if (!isProcessAlive()) {
                return ReadinessStatus.PROCESS_EXITED
            }

            var conn: HttpURLConnection? = null
            try {
                conn = (probeUrl.openConnection() as HttpURLConnection).apply {
                    requestMethod = "HEAD"
                    connectTimeout = probeTimeoutMs
                    readTimeout = probeTimeoutMs
                    instanceFollowRedirects = false
                }
                val code = conn.responseCode
                if (code in 200..499) {
                    return ReadinessStatus.READY
                }
            } catch (_: Exception) {
                // Endpoint not accepting connections yet
            } finally {
                try {
                    conn?.inputStream?.close()
                } catch (_: Exception) {}
                try {
                    conn?.errorStream?.close()
                } catch (_: Exception) {}
                try {
                    conn?.disconnect()
                } catch (_: Exception) {}
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
