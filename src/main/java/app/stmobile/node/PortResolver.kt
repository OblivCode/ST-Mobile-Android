package app.stmobile.node

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

class PortInUseException(val port: Int) :
    Exception("Port $port is in use and autoPortFallback is disabled")

/**
 * Resolves listening ports with loopback probing and ephemeral fallback.
 * Pure Kotlin/Java with zero Android SDK dependencies for fast JVM unit testing.
 */
object PortResolver {

    const val DEFAULT_PORT = 8000

    /**
     * Resolves the target port. If occupied and [autoFallback] is true,
     * allocates a kernel-assigned ephemeral port.
     */
    fun resolvePort(targetPort: Int, autoFallback: Boolean): Int {
        if (targetPort in 1..65535 && isPortAvailable(targetPort)) {
            return targetPort
        }
        if (!autoFallback) {
            if (targetPort !in 1..65535) {
                throw IllegalArgumentException("Invalid target port: $targetPort")
            }
            throw PortInUseException(targetPort)
        }
        return allocateEphemeralPort()
    }

    /**
     * Probes loopback port availability on 127.0.0.1.
     * Does not use SO_REUSEADDR so ports occupied on 0.0.0.0 are not falsely reported as available.
     */
    fun isPortAvailable(port: Int): Boolean {
        if (port !in 1..65535) return false
        return try {
            ServerSocket().use { socket ->
                socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 1)
                true
            }
        } catch (_: IOException) {
            false
        }
    }

    /**
     * Asks the OS kernel for a free ephemeral port on loopback.
     */
    fun allocateEphemeralPort(): Int {
        return ServerSocket().use { socket ->
            // SO_REUSEADDR prevents false collision on the kernel-assigned ephemeral port
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 1)
            socket.localPort
        }
    }
}
