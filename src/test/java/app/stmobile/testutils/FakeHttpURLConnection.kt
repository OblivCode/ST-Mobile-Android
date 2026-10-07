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

    override fun connect() {}

    override fun disconnect() {
        isDisconnected = true
    }

    override fun usingProxy(): Boolean = false

    override fun getResponseCode(): Int = responseCode

    override fun getResponseMessage(): String = responseMessage

    override fun getHeaderField(name: String): String? = responseHeaders.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    override fun getContentLength(): Int = responseData.size

    override fun getContentLengthLong(): Long = responseData.size.toLong()

    override fun setRequestProperty(key: String, value: String) {
        requestedProperties[key] = value
        super.setRequestProperty(key, value)
    }

    override fun getInputStream(): InputStream {
        if (responseCode !in 200..299) {
            throw IOException("HTTP $responseCode $responseMessage")
        }
        return ByteArrayInputStream(responseData)
    }

    override fun getErrorStream(): InputStream? {
        return if (responseCode !in 200..299) ByteArrayInputStream(responseData) else null
    }
}
