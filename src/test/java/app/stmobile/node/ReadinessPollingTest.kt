package app.stmobile.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean

class ReadinessPollingTest {

    private fun createFakeHttpServer(statusCode: Int = 200): ServerSocket {
        val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 50)
        }
        val serverThread = Thread {
            while (!server.isClosed) {
                try {
                    val client = server.accept()
                    Thread {
                        try {
                            client.getInputStream().bufferedReader().readLine()
                            val response = "HTTP/1.1 $statusCode OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK"
                            client.getOutputStream().write(response.toByteArray())
                            client.getOutputStream().flush()
                        } catch (_: Exception) {
                        } finally {
                            try { client.close() } catch (_: Exception) {}
                        }
                    }.apply { isDaemon = true; start() }
                } catch (_: Exception) {
                    break
                }
            }
        }
        serverThread.isDaemon = true
        serverThread.start()
        return server
    }

    @Test
    fun testServerReadyImmediately() {
        val server = createFakeHttpServer(statusCode = 200)
        try {
            val port = server.localPort
            val status = ServerReadinessPoller.poll(
                port = port,
                maxWaitMs = 2000,
                intervalMs = 50,
                probeTimeoutMs = 200,
            )
            assertEquals(ReadinessStatus.READY, status)
        } finally {
            server.close()
        }
    }

    @Test
    fun testServerReadyWithAuthOrNotFoundHttpCodes() {
        val authServer = createFakeHttpServer(statusCode = 401)
        try {
            val port = authServer.localPort
            val status = ServerReadinessPoller.poll(
                port = port,
                maxWaitMs = 2000,
                intervalMs = 50,
                probeTimeoutMs = 200,
            )
            assertEquals("HTTP 401 should report READY", ReadinessStatus.READY, status)
        } finally {
            authServer.close()
        }
    }

    @Test
    fun testServerDelayedStartup() {
        val ephemeralPort = PortResolver.allocateEphemeralPort()
        val serverStarted = AtomicBoolean(false)
        var server: ServerSocket? = null

        val startThread = Thread {
            Thread.sleep(120)
            server = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), ephemeralPort), 50)
            }
            serverStarted.set(true)
            val listenerThread = Thread {
                while (server?.isClosed == false) {
                    try {
                        val client = server?.accept() ?: break
                        val response = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        client.getOutputStream().write(response.toByteArray())
                        client.getOutputStream().flush()
                        client.close()
                    } catch (_: Exception) {
                        break
                    }
                }
            }
            listenerThread.isDaemon = true
            listenerThread.start()
        }
        startThread.start()

        try {
            val status = ServerReadinessPoller.poll(
                port = ephemeralPort,
                maxWaitMs = 3000,
                intervalMs = 40,
                probeTimeoutMs = 150,
            )
            assertEquals("Delayed server startup should be polled successfully", ReadinessStatus.READY, status)
            assertTrue(serverStarted.get())
        } finally {
            server?.close()
            startThread.join(500)
        }
    }

    @Test
    fun testServerTimeoutWhenPortUnopened() {
        val freePort = PortResolver.allocateEphemeralPort()
        val start = System.currentTimeMillis()
        val status = ServerReadinessPoller.poll(
            port = freePort,
            maxWaitMs = 150,
            intervalMs = 20,
            probeTimeoutMs = 50,
        )
        val elapsed = System.currentTimeMillis() - start

        assertEquals(ReadinessStatus.TIMEOUT, status)
        assertTrue("Timeout must elapse approximately within maxWaitMs window", elapsed >= 120)
    }

    @Test
    fun testProcessDiedAbortsPollingImmediately() {
        val freePort = PortResolver.allocateEphemeralPort()
        val start = System.currentTimeMillis()
        val status = ServerReadinessPoller.poll(
            port = freePort,
            maxWaitMs = 5000,
            intervalMs = 50,
            probeTimeoutMs = 100,
            isProcessAlive = { false },
        )
        val elapsed = System.currentTimeMillis() - start

        assertEquals(ReadinessStatus.PROCESS_EXITED, status)
        assertTrue("Process exit must abort immediately without waiting for maxWaitMs", elapsed < 500)
    }

    @Test
    fun testStopRequestedAbortsPollingImmediately() {
        val freePort = PortResolver.allocateEphemeralPort()
        val start = System.currentTimeMillis()
        val status = ServerReadinessPoller.poll(
            port = freePort,
            maxWaitMs = 5000,
            intervalMs = 50,
            probeTimeoutMs = 100,
            isStopRequested = { true },
        )
        val elapsed = System.currentTimeMillis() - start

        assertEquals(ReadinessStatus.CANCELLED, status)
        assertTrue("Stop request must abort immediately without waiting for maxWaitMs", elapsed < 500)
    }
}
