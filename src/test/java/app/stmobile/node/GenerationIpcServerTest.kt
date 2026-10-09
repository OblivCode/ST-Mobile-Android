package app.stmobile.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class GenerationIpcServerTest {

    @Test
    fun testGenerationEventsDispatchedOverTcp() {
        val startLatch = CountDownLatch(1)
        val endLatch = CountDownLatch(1)
        val disconnectLatch = CountDownLatch(1)

        val server = GenerationIpcServer(
            onGenerationStarted = { startLatch.countDown() },
            onGenerationEnded = { endLatch.countDown() },
            onClientDisconnected = { disconnectLatch.countDown() },
        )

        try {
            server.start()
            assertTrue("IPC server port must be bound", server.port in 1..65535)

            val client = Socket("127.0.0.1", server.port)
            val writer = client.getOutputStream().bufferedWriter(Charsets.UTF_8)

            writer.write("GEN_START\n")
            writer.flush()
            assertTrue("GEN_START must trigger onGenerationStarted callback", startLatch.await(2, TimeUnit.SECONDS))

            writer.write("GEN_END\n")
            writer.flush()
            assertTrue("GEN_END must trigger onGenerationEnded callback", endLatch.await(2, TimeUnit.SECONDS))

            client.close()
            assertTrue("Client close must trigger onClientDisconnected callback", disconnectLatch.await(2, TimeUnit.SECONDS))
        } finally {
            server.stop()
        }
    }

    @Test
    fun testServerStopCleansUpSockets() {
        val server = GenerationIpcServer({}, {}, {})
        server.start()
        val port = server.port
        assertTrue(port > 0)

        server.stop()
        assertEquals(-1, server.port)
    }
}
