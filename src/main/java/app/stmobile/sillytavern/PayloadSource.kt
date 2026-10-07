package app.stmobile.sillytavern

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException

/**
 * Pluggable abstraction representing an archive source for the SillyTavern web payload.
 * Supports bundled APK assets (full flavor), remote release streaming via HTTP (slim flavor / OTA),
 * and local storage archives (.zip / .tar / .tar.gz) via SAF.
 */
interface PayloadSource {
    /** Version or identifier stamp to record upon successful extraction. */
    val versionStamp: String

    /** Expected SHA-256 hex digest of the raw archive stream, or null if unverified / untrusted. */
    val expectedSha256: String?

    /** Human-readable source descriptor for logging and UI display. */
    val sourceDescription: String

    /**
     * Opens the stream for extraction and verification.
     * The callback [onProgress] receives bytes read so far, total bytes (-1 if unknown),
     * and smoothed transfer speed in bytes per second.
     */
    fun openStream(
        isCancelled: () -> Boolean,
        onProgress: (bytesRead: Long, totalBytes: Long, speedBytesPerSec: Long) -> Unit,
    ): InputStream
}

/**
 * Extracts payload from pre-bundled APK assets (`st_bundle.tar`), used by the full build flavor.
 */
class AssetPayloadSource(
    val assetName: String = "st_bundle.tar",
    private val manifestProvider: () -> PayloadManager.Manifest,
    private val assetOpener: (String) -> InputStream,
) : PayloadSource {

    private val manifest by lazy { manifestProvider() }

    override val versionStamp: String
        get() = manifest.payloadVersion

    override val expectedSha256: String?
        get() = manifest.bundleSha256

    override val sourceDescription: String
        get() = "Asset ($assetName)"

    override fun openStream(
        isCancelled: () -> Boolean,
        onProgress: (bytesRead: Long, totalBytes: Long, speedBytesPerSec: Long) -> Unit,
    ): InputStream {
        val raw = assetOpener(assetName)
        val totalBytes = runCatching { raw.available().toLong() }.getOrDefault(-1L)
        return ProgressReportingInputStream(raw, totalBytes, isCancelled, onProgress)
    }
}

/**
 * Streams payload release bundle from remote HTTP/HTTPS endpoints (e.g. GitHub Releases).
 * Follows redirects (301, 302, 303, 307, 308) up to 5 hops, enforces explicit socket timeouts,
 * sets User-Agent, and disconnects connection upon stream closure or cancellation.
 */
class RemotePayloadSource(
    val url: String,
    override val versionStamp: String,
    override val expectedSha256: String? = null,
    override val sourceDescription: String = "Remote ($url)",
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
    private val userAgent: String = "ST-Mobile/1.0",
    private val urlOpener: (String) -> HttpURLConnection = { urlStr ->
        (URL(urlStr).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("User-Agent", userAgent)
        }
    },
) : PayloadSource {

    override fun openStream(
        isCancelled: () -> Boolean,
        onProgress: (bytesRead: Long, totalBytes: Long, speedBytesPerSec: Long) -> Unit,
    ): InputStream {
        if (isCancelled()) throw CancellationException("Download cancelled before connecting")

        var currentUrl = url
        var connection: HttpURLConnection? = null
        var redirectsFollowed = 0
        val maxRedirects = 5

        while (redirectsFollowed <= maxRedirects) {
            if (isCancelled()) {
                connection?.disconnect()
                throw CancellationException("Download cancelled during connection setup")
            }

            val conn = urlOpener(currentUrl)
            connection = conn
            conn.connect()

            val status = conn.responseCode
            if (status in 300..399) {
                val location = conn.getHeaderField("Location")
                    ?: throw IOException("HTTP redirect $status without Location header")
                currentUrl = if (location.startsWith("http://") || location.startsWith("https://")) {
                    location
                } else {
                    URL(URL(currentUrl), location).toString()
                }
                conn.disconnect()
                redirectsFollowed++
                continue
            }

            if (status !in 200..299) {
                conn.disconnect()
                throw IOException("HTTP $status ${conn.responseMessage} while fetching $currentUrl")
            }

            val totalBytes = conn.contentLengthLong.takeIf { it >= 0 }
                ?: conn.contentLength.toLong()

            val rawStream = conn.inputStream
            return ProgressReportingInputStream(
                delegate = rawStream,
                totalBytes = totalBytes,
                isCancelled = isCancelled,
                onProgress = onProgress,
                onClose = { conn.disconnect() },
            )
        }

        throw IOException("Too many HTTP redirects (exceeded limit of $maxRedirects)")
    }
}

/**
 * Imports payload from a local storage archive (.zip, .tar, or .tar.gz) via SAF or File.
 */
class LocalArchivePayloadSource(
    private val streamOpener: () -> InputStream,
    override val versionStamp: String = "local-archive",
    override val expectedSha256: String? = null,
    override val sourceDescription: String = "Local Archive",
    val archiveName: String = "payload.tar",
    private val sizeProvider: () -> Long = { -1L },
) : PayloadSource {

    constructor(
        file: File,
        versionStamp: String = file.nameWithoutExtension,
        expectedSha256: String? = null,
    ) : this(
        streamOpener = { file.inputStream() },
        versionStamp = versionStamp,
        expectedSha256 = expectedSha256,
        sourceDescription = "File (${file.name})",
        archiveName = file.name,
        sizeProvider = { file.length() },
    )

    constructor(
        uri: Uri,
        contentResolver: ContentResolver,
        archiveName: String? = null,
        versionStamp: String? = null,
        expectedSha256: String? = null,
    ) : this(
        streamOpener = {
            contentResolver.openInputStream(uri)
                ?: throw IOException("Cannot open input stream for Uri: $uri")
        },
        versionStamp = versionStamp ?: (archiveName ?: "saf-archive"),
        expectedSha256 = expectedSha256,
        sourceDescription = "SAF Uri ($uri)",
        archiveName = archiveName ?: "archive",
        sizeProvider = {
            try {
                contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
            } catch (_: Exception) {
                -1L
            }
        },
    )

    override fun openStream(
        isCancelled: () -> Boolean,
        onProgress: (bytesRead: Long, totalBytes: Long, speedBytesPerSec: Long) -> Unit,
    ): InputStream {
        val totalBytes = sizeProvider()
        val raw = streamOpener()
        return ProgressReportingInputStream(raw, totalBytes, isCancelled, onProgress)
    }
}

/**
 * Helper InputStream tracking bytes read, calculating smoothed transfer speed via EMA,
 * throttling progress callbacks, and periodically verifying cancellation.
 */
internal class ProgressReportingInputStream(
    private val delegate: InputStream,
    private val totalBytes: Long,
    private val isCancelled: () -> Boolean,
    private val onProgress: (bytesRead: Long, totalBytes: Long, speedBytesPerSec: Long) -> Unit,
    private val throttleIntervalMs: Long = 100L,
    private val onClose: (() -> Unit)? = null,
) : InputStream() {

    private var bytesRead: Long = 0L
    private var lastReportTime: Long = System.currentTimeMillis()
    private var lastReportBytes: Long = 0L
    private var smoothedSpeed: Long = 0L

    override fun read(): Int {
        if (isCancelled()) throw CancellationException("Payload extraction cancelled")
        val b = delegate.read()
        if (b != -1) {
            bytesRead++
            checkReport(force = false)
        } else {
            checkReport(force = true)
        }
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (isCancelled()) throw CancellationException("Payload extraction cancelled")
        val count = delegate.read(b, off, len)
        if (count > 0) {
            bytesRead += count
            checkReport(force = false)
        } else if (count == -1) {
            checkReport(force = true)
        }
        return count
    }

    private fun checkReport(force: Boolean) {
        val now = System.currentTimeMillis()
        val timeDiff = now - lastReportTime
        if (force || timeDiff >= throttleIntervalMs) {
            val deltaBytes = bytesRead - lastReportBytes
            if (timeDiff > 0) {
                val instantSpeed = (deltaBytes * 1000L) / timeDiff
                smoothedSpeed = if (smoothedSpeed == 0L) {
                    instantSpeed
                } else {
                    (smoothedSpeed * 0.7 + instantSpeed * 0.3).toLong()
                }
            }
            lastReportTime = now
            lastReportBytes = bytesRead
            onProgress(bytesRead, totalBytes, smoothedSpeed)
        }
    }

    override fun close() {
        try {
            delegate.close()
        } finally {
            onClose?.invoke()
        }
    }

    override fun available(): Int = delegate.available()
}
