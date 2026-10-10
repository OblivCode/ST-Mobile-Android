package app.stmobile.node

import app.stmobile.logging.AppLog
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight loopback TCP server listening for IPC generation events from
 * the Node.js preload hook (generation_hook.cjs).
 */
class GenerationIpcServer(
    private val authToken: String? = null,
    private val onGenerationStarted: () -> Unit,
    private val onGenerationEnded: () -> Unit,
    private val onClientDisconnected: () -> Unit,
) {

    constructor(
        onGenerationStarted: () -> Unit,
        onGenerationEnded: () -> Unit,
        onClientDisconnected: () -> Unit,
    ) : this(
        authToken = null,
        onGenerationStarted = onGenerationStarted,
        onGenerationEnded = onGenerationEnded,
        onClientDisconnected = onClientDisconnected,
    )

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
        AppLog.i("GenerationIpcServer", "Started IPC server on 127.0.0.1:${ss.localPort} (authRequired=${authToken != null})")

        val th = Thread({
            while (running.get() && !ss.isClosed) {
                try {
                    val client = ss.accept()
                    val accepted = synchronized(clientSockets) {
                        if (clientSockets.isNotEmpty()) {
                            false
                        } else {
                            clientSockets.add(client)
                            true
                        }
                    }
                    if (!accepted) {
                        AppLog.w("GenerationIpcServer", "Rejected concurrent client connection from ${client.remoteSocketAddress}")
                        try {
                            client.close()
                        } catch (_: Exception) {}
                        continue
                    }
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
            var clientGenerating = false
            try {
                AppLog.d("GenerationIpcServer", "Accepted client connection from ${client.remoteSocketAddress}")
                client.soTimeout = 10_000
                val input = client.getInputStream().buffered()

                if (authToken != null) {
                    val authLine = readBoundedLine(input)?.trim()
                    if (authLine != "AUTH $authToken") {
                        // Reject and close connection immediately if invalid or missing
                        AppLog.w("GenerationIpcServer", "Client authentication failed")
                        return@Thread
                    }
                }

                while (running.get() && !client.isClosed) {
                    val line = try {
                        readBoundedLine(input)
                    } catch (_: SocketTimeoutException) {
                        // Idle timeout with no pending bytes: keep connection alive
                        continue
                    } ?: break

                    when (line.trim()) {
                        "GEN_START" -> {
                            AppLog.d("GenerationIpcServer", "Received GEN_START from client")
                            clientGenerating = true
                            onGenerationStarted()
                        }
                        "GEN_END" -> {
                            AppLog.d("GenerationIpcServer", "Received GEN_END from client")
                            clientGenerating = false
                            onGenerationEnded()
                        }
                    }
                }
            } catch (_: Exception) {
                // Client disconnection, line limit exceeded, or socket error
            } finally {
                AppLog.d("GenerationIpcServer", "Client disconnected")
                synchronized(clientSockets) {
                    clientSockets.remove(client)
                }
                try {
                    client.close()
                } catch (_: Exception) {}
                if (clientGenerating) {
                    clientGenerating = false
                    try {
                        onGenerationEnded()
                    } catch (_: Exception) {}
                }
                onClientDisconnected()
            }
        }, "ipc-client-reader").apply {
            isDaemon = true
        }
        readerThread.start()
    }

    private fun readBoundedLine(inputStream: InputStream, maxBytes: Int = 128): String? {
        val baos = ByteArrayOutputStream(maxBytes)
        while (true) {
            val b = inputStream.read()
            if (b == -1) {
                if (baos.size() == 0) return null
                break
            }
            if (b == '\n'.code) {
                break
            }
            if (b == '\r'.code) {
                continue
            }
            if (baos.size() >= maxBytes) {
                throw IOException("Line exceeded maximum allowed length of $maxBytes bytes")
            }
            baos.write(b)
        }
        return baos.toString(Charsets.UTF_8.name())
    }

    @Synchronized
    fun stop() {
        if (!running.getAndSet(false)) return
        AppLog.i("GenerationIpcServer", "Stopped IPC server")
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        val toClose = synchronized(clientSockets) {
            val list = clientSockets.toList()
            clientSockets.clear()
            list
        }
        for (sock in toClose) {
            try {
                sock.close()
            } catch (_: Exception) {}
        }

        try {
            acceptThread?.interrupt()
        } catch (_: Exception) {}
        acceptThread = null
    }
}
