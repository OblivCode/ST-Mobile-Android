package app.stmobile.testutils

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reusable mock HttpURLConnection for 100% offline JVM unit testing of HTTP download
 * and REST API client components (RemotePayloadSource, UpdateChecker).
 */
class FakeHttpURLConnection(
    url: URL,
    private val responseCode: Int,
    private val responseMessage: String = "OK",
    private val responseHeaders: Map<String, String> = emptyMap(),
    private val responseData: ByteArray = ByteArray(0),
) : HttpURLConnection(url) {

    var isDisconnected = false
    val requestedProperties = mutableMapOf<String, String>()

    private var cachedInputStream: InputStream? = null
    private var cachedErrorStream: InputStream? = null

    override fun connect() {}

    override fun disconnect() {
        isDisconnected = true
    }

    override fun usingProxy(): Boolean = false

    override fun getResponseCode(): Int = responseCode

    override fun getResponseMessage(): String = responseMessage

    override fun getHeaderField(name: String): String? = responseHeaders.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    override fun getHeaderFields(): Map<String, List<String>> =
        responseHeaders.mapValues { listOf(it.value) }

    override fun getContentLength(): Int {
        val headerLength = getHeaderField("Content-Length")?.toIntOrNull()
        if (headerLength != null) return headerLength
        return if (responseData.isNotEmpty()) responseData.size else -1
    }

    override fun getContentLengthLong(): Long {
        val headerLength = getHeaderField("Content-Length")?.toLongOrNull()
        if (headerLength != null) return headerLength
        return if (responseData.isNotEmpty()) responseData.size.toLong() else -1L
    }

    override fun setRequestProperty(key: String, value: String) {
        requestedProperties[key] = value
        super.setRequestProperty(key, value)
    }

    override fun getInputStream(): InputStream {
        if (isDisconnected) {
            throw IOException("Connection is closed")
        }
        if (responseCode >= 400) {
            throw IOException("HTTP $responseCode $responseMessage")
        }
        return cachedInputStream ?: ByteArrayInputStream(responseData).also { cachedInputStream = it }
    }

    override fun getErrorStream(): InputStream? {
        if (isDisconnected) {
            return null
        }
        if (responseCode < 400) return null
        return cachedErrorStream ?: ByteArrayInputStream(responseData).also { cachedErrorStream = it }
    }
}
