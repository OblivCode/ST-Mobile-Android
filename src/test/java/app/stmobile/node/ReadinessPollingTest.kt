package app.stmobile.node

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ReadinessPollingTest {

    private val threadPool = Executors.newCachedThreadPool { r ->
        Thread(r, "readiness-test-pool").apply { isDaemon = true }
    }

    @After
    fun tearDown() {
        threadPool.shutdownNow()
    }

    private fun createFakeHttpServer(statusCode: Int = 200): ServerSocket {
        val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 50)
        }
        val serverThread = Thread {
            while (!server.isClosed) {
                try {
                    val client = server.accept()
                    threadPool.submit {
                        try {
                            client.getInputStream().bufferedReader().readLine()
                            val response = "HTTP/1.1 $statusCode OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK"
                            client.getOutputStream().write(response.toByteArray())
                            client.getOutputStream().flush()
                        } catch (_: Exception) {
                        } finally {
                            try { client.close() } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {
                    break
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
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
        val serverReady = AtomicBoolean(false)
        val startLatch = CountDownLatch(1)
        val probeCount = AtomicInteger(0)

        // Bind directly to port 0 to prevent TOCTOU port race with allocateEphemeralPort()
        val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 50)
        }
        val port = server.localPort

        val listenerThread = Thread {
            while (!server.isClosed) {
                try {
                    val client = server.accept()
                    threadPool.submit {
                        try {
                            if (!serverReady.get()) {
                                // Simulate endpoint not ready yet by closing client connection immediately
                                client.close()
                            } else {
                                client.getInputStream().bufferedReader().readLine()
                                val response = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                client.getOutputStream().write(response.toByteArray())
                                client.getOutputStream().flush()
                                client.close()
                            }
                        } catch (_: Exception) {
                            try { client.close() } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {
                    break
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

        val delayedStarter = Thread {
            if (startLatch.await(5, TimeUnit.SECONDS)) {
                serverReady.set(true)
            }
        }.apply {
            isDaemon = true
            start()
        }

        try {
            val status = ServerReadinessPoller.poll(
                port = port,
                maxWaitMs = 3000,
                intervalMs = 40,
                probeTimeoutMs = 150,
                isProcessAlive = {
                    if (probeCount.incrementAndGet() >= 2) {
                        startLatch.countDown()
                    }
                    true
                },
            )
            assertEquals("Delayed server startup should be polled successfully", ReadinessStatus.READY, status)
            assertTrue(serverReady.get())
        } finally {
            startLatch.countDown()
            server.close()
        }
    }

    @Test
    fun testServerTimeoutWhenPortUnopened() {
        val freePort = PortResolver.allocateEphemeralPort()
        val status = ServerReadinessPoller.poll(
            port = freePort,
            maxWaitMs = 150,
            intervalMs = 20,
            probeTimeoutMs = 50,
        )

        assertEquals(ReadinessStatus.TIMEOUT, status)
    }

    @Test
    fun testProcessDiedAbortsPollingImmediately() {
        val freePort = PortResolver.allocateEphemeralPort()
        val status = ServerReadinessPoller.poll(
            port = freePort,
            maxWaitMs = 5000,
            intervalMs = 50,
            probeTimeoutMs = 100,
            isProcessAlive = { false },
        )

        assertEquals(ReadinessStatus.PROCESS_EXITED, status)
    }

    @Test
    fun testStopRequestedAbortsPollingImmediately() {
        val freePort = PortResolver.allocateEphemeralPort()
        val status = ServerReadinessPoller.poll(
            port = freePort,
            maxWaitMs = 5000,
            intervalMs = 50,
            probeTimeoutMs = 100,
            isStopRequested = { true },
        )

        assertEquals(ReadinessStatus.CANCELLED, status)
    }
}
