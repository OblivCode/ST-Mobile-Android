package app.stmobile.node

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight loopback TCP server listening for IPC generation events from
 * the Node.js preload hook (generation_hook.cjs).
 */
class GenerationIpcServer(
    private val onGenerationStarted: () -> Unit,
    private val onGenerationEnded: () -> Unit,
    private val onClientDisconnected: () -> Unit,
) {

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val clientSockets = Collections.synchronizedSet(mutableSetOf<Socket>())

    val port: Int
        get() = serverSocket?.localPort ?: -1

    @Synchronized
    fun start() {
        if (running.get()) return
        val ss = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1")).apply {
            reuseAddress = true
        }
        serverSocket = ss
        running.set(true)

        val th = Thread({
            while (running.get() && !ss.isClosed) {
                try {
                    val client = ss.accept()
                    clientSockets.add(client)
                    handleClient(client)
                } catch (_: SocketException) {
                    break
                } catch (_: Exception) {
                    if (!running.get()) break
                }
            }
        }, "ipc-accept").apply {
            isDaemon = true
        }
        acceptThread = th
        th.start()
    }

    private fun handleClient(client: Socket) {
        val readerThread = Thread({
            try {
                val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
                while (running.get() && !client.isClosed) {
                    val line = reader.readLine() ?: break
                    when (line.trim()) {
                        "GEN_START" -> onGenerationStarted()
                        "GEN_END" -> onGenerationEnded()
                    }
                }
            } catch (_: Exception) {
                // Client disconnection or socket error
            } finally {
                clientSockets.remove(client)
                try {
                    client.close()
                } catch (_: Exception) {}
                onClientDisconnected()
            }
        }, "ipc-client-reader").apply {
            isDaemon = true
        }
        readerThread.start()
    }

    @Synchronized
    fun stop() {
        if (!running.getAndSet(false)) return
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        val toClose = clientSockets.toList()
        for (sock in toClose) {
            try {
                sock.close()
            } catch (_: Exception) {}
        }
        clientSockets.clear()

        try {
            acceptThread?.interrupt()
        } catch (_: Exception) {}
        acceptThread = null
    }
}
