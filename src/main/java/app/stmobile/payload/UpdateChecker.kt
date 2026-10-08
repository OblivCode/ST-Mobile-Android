package app.stmobile.payload

import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Metadata representing an available remote SillyTavern release bundle.
 */
data class UpdateInfo(
    val tagName: String,
    val title: String,
    val publishedAt: String,
    val changelog: String,
    val bundleUrl: String,
    val bundleSize: Long,
    val payloadVersion: String,
    val stVersion: String,
    val stCommit: String? = null,
    val bundleSha256: String? = null,
)

/**
 * Result state returned by the update verification engine.
 */
sealed class UpdateCheckResult {
    data class UpToDate(
        val currentVersion: String,
        val latestVersion: String,
    ) : UpdateCheckResult()

    data class UpdateAvailable(
        val currentVersion: String,
        val update: UpdateInfo,
    ) : UpdateCheckResult()

    data class Error(
        val message: String,
        val isRateLimited: Boolean = false,
        val cause: Throwable? = null,
    ) : UpdateCheckResult()
}

/**
 * Queries GitHub Releases API, discovers release assets, extracts release metadata
 * and dual checksums, and performs semantic version comparison.
 */
class UpdateChecker(
    private val apiUrl: String = DEFAULT_API_URL,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 15_000,
    private val userAgent: String = "ST-Mobile/1.0",
    private val urlOpener: (String) -> HttpURLConnection = { urlStr ->
        (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("Accept", "application/vnd.github.v3+json")
            setRequestProperty("User-Agent", userAgent)
        }
    },
) {

    /**
     * Checks for available updates by querying GitHub Releases and comparing against
     * the currently installed payload version and SillyTavern version.
     */
    suspend fun checkForUpdates(
        currentPayloadVersion: String?,
        currentStVersion: String?,
    ): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val releaseJsonText = fetchString(apiUrl)
            val releaseJson = JSONObject(releaseJsonText)

            val tagName = releaseJson.optString("tag_name").trim()
            val title = releaseJson.optString("name").ifBlank { tagName }
            val publishedAt = releaseJson.optString("published_at")
            val changelog = releaseJson.optString("body")
            val assetsArray = releaseJson.optJSONArray("assets")

            if (assetsArray == null || assetsArray.length() == 0) {
                return@withContext UpdateCheckResult.Error("No release assets found in latest release ($tagName)")
            }

            var bundleUrl: String? = null
            var bundleSize: Long = 0L
            var manifestUrl: String? = null

            for (i in 0 until assetsArray.length()) {
                val asset = assetsArray.getJSONObject(i)
                val assetName = asset.optString("name")
                if (assetName == "st_bundle.tar.gz") {
                    bundleUrl = asset.optString("browser_download_url")
                    bundleSize = asset.optLong("size", 0L)
                } else if (assetName == "payload_manifest.json") {
                    manifestUrl = asset.optString("browser_download_url")
                }
            }

            if (bundleUrl == null) {
                return@withContext UpdateCheckResult.Error("No SillyTavern payload bundle found in release ($tagName)")
            }

            var remotePayloadVersion = tagName
            var remoteStVersion = tagName.removePrefix("v").removePrefix("V").removePrefix("st-")
            var remoteCommit: String? = null
            var bundleSha256: String? = null

            if (!manifestUrl.isNullOrBlank()) {
                runCatching {
                    val manifestText = fetchString(manifestUrl)
                    val manifestJson = JSONObject(manifestText)
                    remotePayloadVersion = manifestJson.optString("payload_version").ifBlank { remotePayloadVersion }
                    remoteStVersion = manifestJson.optString("st_version").ifBlank { remoteStVersion }
                    remoteCommit = manifestJson.optString("st_commit").ifBlank { null }
                    bundleSha256 = manifestJson.optString("bundle_sha256").ifBlank { null }
                }
            }

            val updateInfo = UpdateInfo(
                tagName = tagName,
                title = title,
                publishedAt = publishedAt,
                changelog = changelog,
                bundleUrl = bundleUrl,
                bundleSize = bundleSize,
                payloadVersion = remotePayloadVersion,
                stVersion = remoteStVersion,
                stCommit = remoteCommit,
                bundleSha256 = bundleSha256,
            )

            val currentDisplay = currentStVersion ?: currentPayloadVersion ?: "none"
            val isNewer = isNewerVersion(
                remoteStVersion = remoteStVersion,
                currentStVersion = currentStVersion,
                remotePayloadVersion = remotePayloadVersion,
                currentPayloadVersion = currentPayloadVersion,
            )

            if (isNewer) {
                UpdateCheckResult.UpdateAvailable(
                    currentVersion = currentDisplay,
                    update = updateInfo,
                )
            } else {
                UpdateCheckResult.UpToDate(
                    currentVersion = currentDisplay,
                    latestVersion = remoteStVersion,
                )
            }
        } catch (e: RateLimitException) {
            UpdateCheckResult.Error(
                message = e.message ?: "GitHub API rate limit exceeded",
                isRateLimited = true,
                cause = e,
            )
        } catch (t: Throwable) {
            val message = when {
                t is java.net.SocketTimeoutException -> "Connection timed out while checking for updates"
                t is java.net.UnknownHostException -> "Unable to resolve GitHub host. Check internet connection"
                else -> t.message ?: "Failed to check for updates"
            }
            UpdateCheckResult.Error(
                message = message,
                isRateLimited = false,
                cause = t,
            )
        }
    }

    private fun fetchString(urlStr: String): String {
        var currentUrl = urlStr
        var redirectsFollowed = 0
        val maxRedirects = 5

        while (redirectsFollowed <= maxRedirects) {
            val conn = urlOpener(currentUrl)
            conn.instanceFollowRedirects = false
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

            if (status == 403) {
                conn.disconnect()
                throw RateLimitException("GitHub API rate limit exceeded. Please try again later.")
            }

            if (status !in 200..299) {
                val errorMsg = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: conn.responseMessage
                conn.disconnect()
                throw IOException("HTTP $status $errorMsg while fetching $currentUrl")
            }

            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.also {
                conn.disconnect()
            }
        }

        throw IOException("Too many HTTP redirects ($redirectsFollowed)")
    }

    class RateLimitException(message: String) : IOException(message)

    companion object {
        const val DEFAULT_API_URL = "https://api.github.com/repos/OblivCode/ST-Mobile-Android/releases/latest"

        /**
         * Parses a semantic version string into integer triple (major, minor, patch).
         * Safely ignores trailing pre-release or commit tags.
         */
        fun parseSemVer(version: String?): Triple<Int, Int, Int>? {
            if (version.isNullOrBlank()) return null
            val clean = version.trim()
                .removePrefix("v").removePrefix("V")
                .removePrefix("st-")
                .substringBefore('-')
                .substringBefore('+')
                .substringBefore(' ')
            val parts = clean.split('.')
            if (parts.size < 2) return null
            val major = parts.getOrNull(0)?.toIntOrNull() ?: return null
            val minor = parts.getOrNull(1)?.toIntOrNull() ?: return null
            val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
            return Triple(major, minor, patch)
        }

        /**
         * Compares two semantic version strings numerically.
         * Returns > 0 if v1 > v2, < 0 if v1 < v2, and 0 if equal or unparseable.
         */
        fun compareSemVer(v1: String?, v2: String?): Int {
            val sem1 = parseSemVer(v1) ?: return 0
            val sem2 = parseSemVer(v2) ?: return 0

            if (sem1.first != sem2.first) return sem1.first.compareTo(sem2.first)
            if (sem1.second != sem2.second) return sem1.second.compareTo(sem2.second)
            return sem1.third.compareTo(sem2.third)
        }

        /**
         * Evaluates whether [remoteStVersion] / [remotePayloadVersion] represents a newer
         * release compared to current local state.
         *
         * Enforces downgrade prevention: if remote is strictly older SemVer, returns false.
         */
        fun isNewerVersion(
            remoteStVersion: String?,
            currentStVersion: String?,
            remotePayloadVersion: String?,
            currentPayloadVersion: String?,
        ): Boolean {
            // Not installed locally: any release is considered newer
            if (currentStVersion.isNullOrBlank() && currentPayloadVersion.isNullOrBlank()) {
                return true
            }

            // Both have parseable SemVer
            val semCmp = compareSemVer(remoteStVersion, currentStVersion)
            if (semCmp > 0) return true
            if (semCmp < 0) return false // Downgrade prevention

            // SemVer is identical (e.g. both 1.19.0). Check if payload stamps differ.
            if (!remotePayloadVersion.isNullOrBlank() && !currentPayloadVersion.isNullOrBlank()) {
                // If remote payload stamp differs and current is a fallback or previous build
                if (currentPayloadVersion == "local-archive" || currentPayloadVersion == "saf-archive") {
                    return true
                }
                return remotePayloadVersion != currentPayloadVersion
            }

            return false
        }
    }
}
