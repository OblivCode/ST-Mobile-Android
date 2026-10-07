package app.stmobile.sillytavern

import app.stmobile.testutils.FakeHttpURLConnection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URL

class UpdateCheckerTest {

    // --- SemVer Unit Tests ---

    @Test
    fun testParseSemVer() {
        assertEquals(Triple(1, 19, 0), UpdateChecker.parseSemVer("1.19.0"))
        assertEquals(Triple(1, 19, 1), UpdateChecker.parseSemVer("v1.19.1"))
        assertEquals(Triple(2, 0, 0), UpdateChecker.parseSemVer("V2.0.0"))
        assertEquals(Triple(1, 19, 0), UpdateChecker.parseSemVer("st-1.19.0-7e8663cd"))
        assertEquals(Triple(0, 3, 0), UpdateChecker.parseSemVer("v0.3.0-beta"))
        assertNull(UpdateChecker.parseSemVer("invalid"))
        assertNull(UpdateChecker.parseSemVer(""))
        assertNull(UpdateChecker.parseSemVer(null))
    }

    @Test
    fun testCompareSemVer() {
        assertTrue(UpdateChecker.compareSemVer("1.19.1", "1.19.0") > 0)
        assertTrue(UpdateChecker.compareSemVer("1.20.0", "1.19.5") > 0)
        assertTrue(UpdateChecker.compareSemVer("2.0.0", "1.99.99") > 0)
        assertTrue(UpdateChecker.compareSemVer("1.19.0", "1.19.1") < 0)
        assertEquals(0, UpdateChecker.compareSemVer("1.19.0", "1.19.0"))
        assertEquals(0, UpdateChecker.compareSemVer("st-1.19.0-7e8663cd", "1.19.0"))
    }

    @Test
    fun testIsNewerVersion() {
        // Not installed: any release is newer
        assertTrue(UpdateChecker.isNewerVersion("1.19.0", null, "st-1.19.0-7e8663cd", null))
        assertTrue(UpdateChecker.isNewerVersion("1.19.0", "", "st-1.19.0-7e8663cd", ""))

        // Newer SemVer
        assertTrue(UpdateChecker.isNewerVersion("1.19.1", "1.19.0", "st-1.19.1-abc", "st-1.19.0-def"))
        assertTrue(UpdateChecker.isNewerVersion("2.0.0", "1.19.0", "st-2.0.0-abc", "st-1.19.0-def"))

        // Downgrade prevention
        assertFalse(UpdateChecker.isNewerVersion("1.18.0", "1.19.0", "st-1.18.0-abc", "st-1.19.0-def"))

        // Exact same versions
        assertFalse(UpdateChecker.isNewerVersion("1.19.0", "1.19.0", "st-1.19.0-7e8663cd", "st-1.19.0-7e8663cd"))

        // Sideloaded archive override
        assertTrue(UpdateChecker.isNewerVersion("1.19.0", "1.19.0", "st-1.19.0-7e8663cd", "local-archive"))
        assertTrue(UpdateChecker.isNewerVersion("1.19.0", "1.19.0", "st-1.19.0-7e8663cd", "saf-archive"))

        // Same SemVer but newer payload patch/commit stamp
        assertTrue(UpdateChecker.isNewerVersion("1.19.0", "1.19.0", "st-1.19.0-newcommit", "st-1.19.0-oldcommit"))
    }

    // --- GitHub Releases API Client Tests ---

    @Test
    fun testCheckForUpdates_successWithManifest() = runBlocking {
        val releaseJson = """
            {
              "tag_name": "v0.3.0",
              "name": "ST-Mobile v0.3.0",
              "published_at": "2026-10-06T12:00:00Z",
              "body": "Changelog: added OTA updates",
              "assets": [
                {
                  "name": "st_bundle.tar.gz",
                  "size": 44102912,
                  "browser_download_url": "https://github.com/OblivCode/ST-Mobile-Android/releases/download/v0.3.0/st_bundle.tar.gz"
                },
                {
                  "name": "payload_manifest.json",
                  "size": 458,
                  "browser_download_url": "https://github.com/OblivCode/ST-Mobile-Android/releases/download/v0.3.0/payload_manifest.json"
                }
              ]
            }
        """.trimIndent()

        val manifestJson = """
            {
              "payload_version": "st-1.19.1-8f9774de",
              "st_version": "1.19.1",
              "st_commit": "8f9774de12345678",
              "bundle": "st_bundle.tar.gz",
              "bundle_sha256": "expected-sha-999"
            }
        """.trimIndent()

        val checker = UpdateChecker(
            apiUrl = "https://example.com/api/releases/latest",
            urlOpener = { urlStr ->
                when (urlStr) {
                    "https://example.com/api/releases/latest" -> FakeHttpURLConnection(
                        url = URL(urlStr),
                        responseCode = 200,
                        responseData = releaseJson.toByteArray(),
                    )
                    "https://github.com/OblivCode/ST-Mobile-Android/releases/download/v0.3.0/payload_manifest.json" -> FakeHttpURLConnection(
                        url = URL(urlStr),
                        responseCode = 200,
                        responseData = manifestJson.toByteArray(),
                    )
                    else -> error("Unexpected URL: $urlStr")
                }
            }
        )

        val result = checker.checkForUpdates(
            currentPayloadVersion = "st-1.19.0-7e8663cd",
            currentStVersion = "1.19.0",
        )

        assertTrue("Must detect update available", result is UpdateCheckResult.UpdateAvailable)
        val update = (result as UpdateCheckResult.UpdateAvailable).update
        assertEquals("v0.3.0", update.tagName)
        assertEquals("1.19.1", update.stVersion)
        assertEquals("8f9774de12345678", update.stCommit)
        assertEquals("expected-sha-999", update.bundleSha256)
        assertEquals("https://github.com/OblivCode/ST-Mobile-Android/releases/download/v0.3.0/st_bundle.tar.gz", update.bundleUrl)
        assertEquals(44102912L, update.bundleSize)
    }

    @Test
    fun testCheckForUpdates_upToDate() = runBlocking {
        val releaseJson = """
            {
              "tag_name": "v0.2.0",
              "assets": [
                {
                  "name": "st_bundle.tar.gz",
                  "size": 42000000,
                  "browser_download_url": "https://example.com/download/st_bundle.tar.gz"
                },
                {
                  "name": "payload_manifest.json",
                  "browser_download_url": "https://example.com/download/payload_manifest.json"
                }
              ]
            }
        """.trimIndent()

        val manifestJson = """
            {
              "payload_version": "st-1.19.0-7e8663cd",
              "st_version": "1.19.0"
            }
        """.trimIndent()

        val checker = UpdateChecker(
            apiUrl = "https://example.com/releases/latest",
            urlOpener = { urlStr ->
                if (urlStr.endsWith("payload_manifest.json")) {
                    FakeHttpURLConnection(URL(urlStr), 200, responseData = manifestJson.toByteArray())
                } else {
                    FakeHttpURLConnection(URL(urlStr), 200, responseData = releaseJson.toByteArray())
                }
            }
        )

        val result = checker.checkForUpdates(
            currentPayloadVersion = "st-1.19.0-7e8663cd",
            currentStVersion = "1.19.0",
        )

        assertTrue("Must report up-to-date", result is UpdateCheckResult.UpToDate)
        assertEquals("1.19.0", (result as UpdateCheckResult.UpToDate).latestVersion)
    }

    @Test
    fun testCheckForUpdates_fallbackWithoutManifest() = runBlocking {
        val releaseJson = """
            {
              "tag_name": "v1.20.0",
              "assets": [
                {
                  "name": "st_bundle.tar.gz",
                  "size": 45000000,
                  "browser_download_url": "https://example.com/download/st_bundle.tar.gz"
                }
              ]
            }
        """.trimIndent()

        val checker = UpdateChecker(
            apiUrl = "https://example.com/releases/latest",
            urlOpener = { FakeHttpURLConnection(URL(it), 200, responseData = releaseJson.toByteArray()) }
        )

        val result = checker.checkForUpdates(
            currentPayloadVersion = "st-1.19.0-7e8663cd",
            currentStVersion = "1.19.0",
        )

        assertTrue(result is UpdateCheckResult.UpdateAvailable)
        val update = (result as UpdateCheckResult.UpdateAvailable).update
        assertEquals("1.20.0", update.stVersion)
        assertNull(update.bundleSha256)
    }

    @Test
    fun testCheckForUpdates_missingBundleError() = runBlocking {
        val releaseJson = """
            {
              "tag_name": "v0.3.0",
              "assets": [
                {
                  "name": "checksums.txt",
                  "size": 100,
                  "browser_download_url": "https://example.com/checksums.txt"
                }
              ]
            }
        """.trimIndent()

        val checker = UpdateChecker(
            apiUrl = "https://example.com/releases/latest",
            urlOpener = { FakeHttpURLConnection(URL(it), 200, responseData = releaseJson.toByteArray()) }
        )

        val result = checker.checkForUpdates("st-1.19.0", "1.19.0")
        assertTrue("Must report error when bundle is missing", result is UpdateCheckResult.Error)
        assertTrue((result as UpdateCheckResult.Error).message.contains("No SillyTavern payload bundle found"))
    }

    @Test
    fun testCheckForUpdates_http403RateLimit() = runBlocking {
        val checker = UpdateChecker(
            apiUrl = "https://api.github.com/releases/latest",
            urlOpener = { FakeHttpURLConnection(URL(it), responseCode = 403, responseMessage = "Forbidden") }
        )

        val result = checker.checkForUpdates("st-1.19.0", "1.19.0")
        assertTrue(result is UpdateCheckResult.Error)
        val err = result as UpdateCheckResult.Error
        assertTrue("Must flag rate limit", err.isRateLimited)
        assertTrue(err.message.contains("rate limit"))
    }

    @Test
    fun testCheckForUpdates_http404NoReleases() = runBlocking {
        val checker = UpdateChecker(
            apiUrl = "https://api.github.com/releases/latest",
            urlOpener = { FakeHttpURLConnection(URL(it), responseCode = 404, responseMessage = "Not Found") }
        )

        val result = checker.checkForUpdates("st-1.19.0", "1.19.0")
        assertTrue(result is UpdateCheckResult.Error)
        assertFalse((result as UpdateCheckResult.Error).isRateLimited)
    }

    @Test
    fun testCheckForUpdates_socketTimeout() = runBlocking {
        val checker = UpdateChecker(
            apiUrl = "https://api.github.com/releases/latest",
            urlOpener = { throw SocketTimeoutException("Read timed out") }
        )

        val result = checker.checkForUpdates("st-1.19.0", "1.19.0")
        assertTrue(result is UpdateCheckResult.Error)
        assertTrue((result as UpdateCheckResult.Error).message.contains("timed out"))
    }
}
