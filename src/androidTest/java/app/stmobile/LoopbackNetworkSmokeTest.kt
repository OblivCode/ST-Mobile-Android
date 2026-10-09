package app.stmobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.stmobile.node.PortResolver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class LoopbackNetworkSmokeTest {

    private val processesToClean = mutableListOf<Process>()

    @After
    fun tearDown() {
        for (proc in processesToClean) {
            try {
                proc.destroy()
                if (!proc.waitFor(1, TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                }
            } catch (_: Exception) {
                proc.destroyForcibly()
            }
        }
        processesToClean.clear()
    }

    private fun spawnTestHttpServer(port: Int): Process {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val nativeLibDir = targetContext.applicationInfo.nativeLibraryDir
        val launcher = File(nativeLibDir, "libstnode.so")

        val script = "require('http').createServer((req, res) => {" +
            "  res.writeHead(200, {'Content-Type': 'text/plain'});" +
            "  res.end('ST_LOOPBACK_OK');" +
            "}).listen($port, '127.0.0.1', () => {" +
            "  console.log('LISTENING');" +
            "});"

        val pb = ProcessBuilder(launcher.absolutePath, "-e", script)
        pb.environment()["LD_LIBRARY_PATH"] = nativeLibDir

        val process = pb.start()
        processesToClean.add(process)
        return process
    }

    private fun pollHttpReady(port: Int, maxAttempts: Int = 30): String {
        val url = URL("http://127.0.0.1:$port/")
        for (i in 0 until maxAttempts) {
            try {
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 300
                conn.readTimeout = 300
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    return body
                }
                conn.disconnect()
            } catch (_: Exception) {
                // Not listening yet
            }
            Thread.sleep(100)
        }
        throw IllegalStateException("Server on port $port failed to respond within timeout")
    }

    @Test
    fun testLoopbackNetworkingUnderAndroidSelinux() {
        val port = PortResolver.allocateEphemeralPort()
        val server = spawnTestHttpServer(port)

        val response = pollHttpReady(port)
        assertEquals("ST_LOOPBACK_OK", response)
        assertTrue("Server process must remain alive", server.isAlive)
    }

    @Test
    fun testProcessKillAndRestartRecovery() {
        val port1 = PortResolver.allocateEphemeralPort()
        val server1 = spawnTestHttpServer(port1)

        val response1 = pollHttpReady(port1)
        assertEquals("ST_LOOPBACK_OK", response1)

        // Kill server 1
        server1.destroyForcibly()
        server1.waitFor(3, TimeUnit.SECONDS)
        assertTrue("Server 1 must be terminated", !server1.isAlive)

        // Start server 2 on fresh port
        val port2 = PortResolver.allocateEphemeralPort()
        val server2 = spawnTestHttpServer(port2)

        val response2 = pollHttpReady(port2)
        assertEquals("ST_LOOPBACK_OK", response2)
        assertTrue("Server 2 must be alive after restart", server2.isAlive)
    }
}
