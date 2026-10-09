package app.stmobile.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            assertFalse("Occupied port must not report as available", available)

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

    @Test
    fun testPortBoundaryEdgeCasesAvailability() {
        assertFalse("Port 0 must be reported unavailable", PortResolver.isPortAvailable(0))
        assertFalse("Port 65536 must be reported unavailable", PortResolver.isPortAvailable(65536))
        assertFalse("Negative port -1 must be reported unavailable", PortResolver.isPortAvailable(-1))
        assertFalse("Excessive port 100000 must be reported unavailable", PortResolver.isPortAvailable(100000))
    }

    @Test
    fun testValidBoundaryPortFallbackWhenOccupiedOrPrivileged() {
        // Port 1 is within 1..65535, but on non-root POSIX systems it is privileged (<1024).
        // With autoFallback = true, it must either resolve to 1 (if root) or safely allocate ephemeral port.
        val resolved1 = PortResolver.resolvePort(1, autoFallback = true)
        assertTrue("Port 1 resolution must yield valid port", resolved1 in 1..65535)

        // Port 65535 is maximum valid port bound
        val resolvedMax = PortResolver.resolvePort(65535, autoFallback = true)
        assertTrue("Port 65535 resolution must yield valid port", resolvedMax in 1..65535)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testPortZeroThrowsWhenNoFallback() {
        PortResolver.resolvePort(0, autoFallback = false)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testPortAbove65535ThrowsWhenNoFallback() {
        PortResolver.resolvePort(65536, autoFallback = false)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testNegativePortThrowsWhenNoFallback() {
        PortResolver.resolvePort(-1, autoFallback = false)
    }

    @Test
    fun testOutOfBoundsPortsFallbackWhenEnabled() {
        val resolvedZero = PortResolver.resolvePort(0, autoFallback = true)
        assertTrue("Port 0 with fallback should allocate ephemeral port", resolvedZero in 1..65535)

        val resolvedUpper = PortResolver.resolvePort(65536, autoFallback = true)
        assertTrue("Port 65536 with fallback should allocate ephemeral port", resolvedUpper in 1..65535)

        val resolvedNegative = PortResolver.resolvePort(-9999, autoFallback = true)
        assertTrue("Negative port with fallback should allocate ephemeral port", resolvedNegative in 1..65535)
    }
}
