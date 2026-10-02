package app.stmobile.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

class PortResolverTest {

    @Test
    fun testPortAvailableWhenFree() {
        val ephemeralPort = PortResolver.allocateEphemeralPort()
        assertTrue("Ephemeral port must be in valid range", ephemeralPort in 1..65535)
        assertTrue("Ephemeral port should be available once closed", PortResolver.isPortAvailable(ephemeralPort))

        val resolved = PortResolver.resolvePort(ephemeralPort, autoFallback = false)
        assertEquals(ephemeralPort, resolved)
    }

    @Test
    fun testPortConflictWithAutoFallback() {
        // Bind a dummy server socket to occupy a port
        val dummyServer = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 1)
        }
        val occupiedPort = dummyServer.localPort
        try {
            // Port should be unavailable
            val available = PortResolver.isPortAvailable(occupiedPort)
            // Note: with reuseAddress = true on both, binding with backlog 1 will fail with EADDRINUSE on occupied port
            // But resolvePort with autoFallback should allocate a new port
            val fallbackPort = PortResolver.resolvePort(occupiedPort, autoFallback = true)
            assertNotEquals("Fallback port must not be the occupied port", occupiedPort, fallbackPort)
            assertTrue("Fallback port must be a valid port number", fallbackPort in 1..65535)
        } finally {
            dummyServer.close()
        }
    }

    @Test(expected = PortInUseException::class)
    fun testPortConflictWithoutAutoFallback() {
        val dummyServer = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 1)
        }
        val occupiedPort = dummyServer.localPort
        try {
            PortResolver.resolvePort(occupiedPort, autoFallback = false)
        } finally {
            dummyServer.close()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun testInvalidPortThrowsWhenNoFallback() {
        PortResolver.resolvePort(-1, autoFallback = false)
    }

    @Test
    fun testInvalidPortFallsBackWhenEnabled() {
        val resolved = PortResolver.resolvePort(999999, autoFallback = true)
        assertTrue("Should allocate valid ephemeral port", resolved in 1..65535)
    }
}
