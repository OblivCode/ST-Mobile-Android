package app.stmobile.sillytavern

import app.stmobile.AppPaths
import app.stmobile.testutils.FakeSharedPreferences
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PayloadManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun createTarBytes(entries: Map<String, ByteArray>): ByteArray {
        val baos = ByteArrayOutputStream()
        TarArchiveOutputStream(baos).use { tos ->
            tos.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            for ((name, data) in entries) {
                val entry = TarArchiveEntry(name)
                entry.size = data.size.toLong()
                entry.mode = 0b111_101_101 // rwxr-xr-x
                tos.putArchiveEntry(entry)
                tos.write(data)
                tos.closeArchiveEntry()
            }
        }
        return baos.toByteArray()
    }

    private fun createTestSetup(
        tarEntries: Map<String, ByteArray> = mapOf(
            "st/server.js" to "console.log('ST');".toByteArray(),
            "st/package.json" to "{\"name\":\"sillytavern\"}".toByteArray()
        ),
        payloadVersion: String = "v1.0.0",
        customSha256: String? = null,
        defaultConfigText: String = "port: 8000\nlisten: false\n",
    ): Triple<PayloadManager, AppPaths, FakeSharedPreferences> {
        val filesDir = tempFolder.newFolder("files")
        val cacheDir = tempFolder.newFolder("cache")
        val nativeLibDir = tempFolder.newFolder("lib").absolutePath
        val paths = AppPaths(filesDir = filesDir, cacheDir = cacheDir, nativeLibDir = nativeLibDir)
        val prefs = FakeSharedPreferences()

        val tarBytes = createTarBytes(tarEntries)
        val actualSha = customSha256 ?: sha256(tarBytes)

        val manifestJson = """
            {
              "payload_version": "$payloadVersion",
              "st_version": "1.12.0",
              "node_version": "20.10.0",
              "bundle": "st_bundle.tar",
              "bundle_sha256": "$actualSha"
            }
        """.trimIndent()

        val assetStore = mutableMapOf<String, ByteArray>(
            "payload_manifest.json" to manifestJson.toByteArray(),
            "st_bundle.tar" to tarBytes,
            "default_config.yaml" to defaultConfigText.toByteArray()
        )

        val assetOpener: (String) -> InputStream = { name ->
            val data = assetStore[name] ?: throw IOException("Asset not found: $name")
            ByteArrayInputStream(data)
        }

        val manager = PayloadManager(paths = paths, prefs = prefs, assetOpener = assetOpener)
        return Triple(manager, paths, prefs)
    }

    @Test
    fun testExtractionNeededWhenNotInstalled() {
        val (manager, paths, _) = createTestSetup()
        assertTrue("Extraction must be needed when app is freshly installed", manager.isExtractionNeeded())
    }

    @Test
    fun testFirstRunExtractionSuccess() {
        val (manager, paths, prefs) = createTestSetup()

        var progressReported = false
        val layout = manager.extract { progress ->
            progressReported = true
        }

        assertTrue("Progress callback should be triggered", progressReported)
        assertTrue("server.js entry must exist", paths.stEntry.exists())
        assertEquals("console.log('ST');", paths.stEntry.readText())
        assertTrue("config.yaml must be seeded", paths.configFile.exists())
        assertTrue("Installed version must be saved in prefs", prefs.contains("installed_payload_version"))
        assertEquals("v1.0.0", prefs.getString("installed_payload_version", null))
        assertFalse("Extraction should no longer be needed", manager.isExtractionNeeded())

        // Verify getExistingLayout runs cleanly
        val existing = manager.getExistingLayout()
        assertEquals(layout.stEntry, existing.stEntry)
        assertEquals(layout.configFile, existing.configFile)
    }

    @Test
    fun testExtractionNeededWhenManifestVersionUpdated() {
        val (manager, paths, prefs) = createTestSetup(payloadVersion = "v1.0.0")
        manager.extract {}
        assertFalse(manager.isExtractionNeeded())

        // Simulate app update by writing newer version stamp in manifest
        val newManifestJson = """
            {
              "payload_version": "v1.0.1",
              "bundle": "st_bundle.tar"
            }
        """.trimIndent()
        val tarBytes = createTarBytes(mapOf("st/server.js" to "console.log('V2');".toByteArray()))
        val updatedOpener: (String) -> InputStream = { name ->
            when (name) {
                "payload_manifest.json" -> ByteArrayInputStream(newManifestJson.toByteArray())
                "st_bundle.tar" -> ByteArrayInputStream(tarBytes)
                "default_config.yaml" -> ByteArrayInputStream("port: 8000\n".toByteArray())
                else -> throw IOException("Not found: $name")
            }
        }

        val updatedManager = PayloadManager(paths = paths, prefs = prefs, assetOpener = updatedOpener)
        assertTrue("Extraction must be needed after version bump", updatedManager.isExtractionNeeded())
    }

    @Test
    fun testExtractionNeededWhenEntryFileMissing() {
        val (manager, paths, _) = createTestSetup()
        manager.extract {}
        assertFalse(manager.isExtractionNeeded())

        // Delete server.js
        paths.stEntry.delete()
        assertTrue("Extraction must be needed if server.js entry is deleted", manager.isExtractionNeeded())
    }

    @Test
    fun testReExtractionAfterUpdatePreservesUserDataAndConfig() {
        val (manager, paths, prefs) = createTestSetup(payloadVersion = "v1.0.0")
        manager.extract {}

        // User customizes config.yaml and adds custom chat/bot
        paths.configFile.writeText("port: 9999\ncustomKey: preserved\n")
        val userBot = File(paths.dataDir, "characters/custom_bot.json").apply {
            parentFile?.mkdirs()
            writeText("{\"name\":\"Alice\"}")
        }

        // Newer version extraction
        val v2Tar = createTarBytes(mapOf("st/server.js" to "console.log('V2_NEW');".toByteArray()))
        val v2Sha = sha256(v2Tar)
        val v2Manifest = """
            {
              "payload_version": "v2.0.0",
              "bundle": "st_bundle.tar",
              "bundle_sha256": "$v2Sha"
            }
        """.trimIndent()

        val v2Opener: (String) -> InputStream = { name ->
            when (name) {
                "payload_manifest.json" -> ByteArrayInputStream(v2Manifest.toByteArray())
                "st_bundle.tar" -> ByteArrayInputStream(v2Tar)
                "default_config.yaml" -> ByteArrayInputStream("port: 8000\n".toByteArray())
                else -> throw IOException("Not found: $name")
            }
        }

        val v2Manager = PayloadManager(paths = paths, prefs = prefs, assetOpener = v2Opener)
        v2Manager.extract {}

        // Assert code tree updated
        assertEquals("console.log('V2_NEW');", paths.stEntry.readText())
        assertEquals("v2.0.0", prefs.getString("installed_payload_version", null))

        // Assert user config and data were preserved!
        assertTrue("User bot must survive payload upgrade", userBot.exists())
        assertEquals("{\"name\":\"Alice\"}", userBot.readText())
        assertEquals("port: 9999\ncustomKey: preserved\n", paths.configFile.readText())
    }

    @Test
    fun testTruncatedTarThrowsAndCleansUp() {
        val filesDir = tempFolder.newFolder("files_trunc")
        val cacheDir = tempFolder.newFolder("cache_trunc")
        val paths = AppPaths(filesDir = filesDir, cacheDir = cacheDir, nativeLibDir = "/tmp")
        val prefs = FakeSharedPreferences()

        val fullTar = createTarBytes(mapOf("st/server.js" to "console.log('Trunc');".toByteArray()))
        // Slice archive mid-file
        val truncatedTar = fullTar.copyOf(fullTar.size / 3)

        val manifest = """
            {
              "payload_version": "v1.0.0",
              "bundle": "st_bundle.tar"
            }
        """.trimIndent()

        val opener: (String) -> InputStream = { name ->
            when (name) {
                "payload_manifest.json" -> ByteArrayInputStream(manifest.toByteArray())
                "st_bundle.tar" -> ByteArrayInputStream(truncatedTar)
                else -> throw IOException("Not found: $name")
            }
        }

        val manager = PayloadManager(paths, prefs, opener)
        var failed = false
        try {
            manager.extract {}
        } catch (_: Exception) {
            failed = true
        }

        assertTrue("Truncated archive must throw", failed)
        val tmpNew = File(paths.tmpDir, "st_new")
        assertFalse("Staging directory tmpNew must be deleted on error", tmpNew.exists())
        assertFalse("Installed version must not be recorded on error", prefs.contains("installed_payload_version"))
    }

    @Test
    fun testLowSpaceIOExceptionCleansUp() {
        val filesDir = tempFolder.newFolder("files_lowspace")
        val cacheDir = tempFolder.newFolder("cache_lowspace")
        val paths = AppPaths(filesDir = filesDir, cacheDir = cacheDir, nativeLibDir = "/tmp")
        val prefs = FakeSharedPreferences()

        val manifest = """
            {
              "payload_version": "v1.0.0",
              "bundle": "st_bundle.tar"
            }
        """.trimIndent()

        // InputStream that throws IOException("No space left on device") mid-stream
        val opener: (String) -> InputStream = { name ->
            when (name) {
                "payload_manifest.json" -> ByteArrayInputStream(manifest.toByteArray())
                "st_bundle.tar" -> object : InputStream() {
                    override fun read(): Int = throw IOException("No space left on device")
                }
                else -> throw IOException("Not found: $name")
            }
        }

        val manager = PayloadManager(paths, prefs, opener)
        var failed = false
        try {
            manager.extract {}
        } catch (e: IOException) {
            failed = true
            assertEquals("No space left on device", e.message)
        }

        assertTrue("Low-space exception must be propagated", failed)
        val tmpNew = File(paths.tmpDir, "st_new")
        assertFalse("Staging directory must be cleaned up on low space", tmpNew.exists())
        assertFalse(prefs.contains("installed_payload_version"))
    }

    @Test
    fun testChecksumMismatchThrowsAndCleansUp() {
        val (manager, paths, prefs) = createTestSetup(customSha256 = "0000000000000000000000000000000000000000000000000000000000000000")

        var threw = false
        try {
            manager.extract {}
        } catch (e: IllegalStateException) {
            threw = true
            assertTrue(e.message?.contains("checksum mismatch") == true)
        }

        assertTrue("Checksum mismatch must throw IllegalStateException", threw)
        val tmpNew = File(paths.tmpDir, "st_new")
        assertFalse("Staging directory must be cleaned up on checksum failure", tmpNew.exists())
        assertFalse(prefs.contains("installed_payload_version"))
    }

    @Test
    fun testZipSlipPathTraversalBlocked() {
        val evilTar = createTarBytes(mapOf("st/../../outside.txt" to "evil".toByteArray()))
        val manifest = """
            {
              "payload_version": "v1.0.0",
              "bundle": "st_bundle.tar"
            }
        """.trimIndent()

        val filesDir = tempFolder.newFolder("files_evil")
        val cacheDir = tempFolder.newFolder("cache_evil")
        val paths = AppPaths(filesDir = filesDir, cacheDir = cacheDir, nativeLibDir = "/tmp")
        val prefs = FakeSharedPreferences()

        val opener: (String) -> InputStream = { name ->
            when (name) {
                "payload_manifest.json" -> ByteArrayInputStream(manifest.toByteArray())
                "st_bundle.tar" -> ByteArrayInputStream(evilTar)
                else -> throw IOException("Not found: $name")
            }
        }

        val manager = PayloadManager(paths, prefs, opener)
        var threw = false
        try {
            manager.extract {}
        } catch (e: IllegalStateException) {
            threw = true
            assertTrue(e.message?.contains("Unsafe archive entry") == true)
        }

        assertTrue("Path traversal entry must throw IllegalStateException", threw)
    }

    @Test
    fun testResetPayload() {
        val (manager, paths, prefs) = createTestSetup()
        manager.extract {}

        assertTrue(paths.stDir.exists())
        assertTrue(prefs.contains("installed_payload_version"))

        val resetResult = manager.resetPayload()
        assertTrue("resetPayload must return true", resetResult)
        assertFalse("stDir must be deleted", paths.stDir.exists())
        assertFalse("Version stamp must be cleared from prefs", prefs.contains("installed_payload_version"))
        assertTrue("Extraction must be needed after reset", manager.isExtractionNeeded())
    }

    @Test
    fun testIsInstalledHelper() {
        val (manager, _, _) = createTestSetup()
        assertFalse("Should not be installed initially", manager.isInstalled())
        manager.extract {}
        assertTrue("Should be installed after extraction", manager.isInstalled())
        manager.resetPayload()
        assertFalse("Should not be installed after reset", manager.isInstalled())
    }

    @Test
    fun testExtractionCancellationCleansUpStaging() {
        val (manager, paths, prefs) = createTestSetup()
        var threw = false
        try {
            manager.extract(isCancelled = { true }) {}
        } catch (e: java.util.concurrent.CancellationException) {
            threw = true
            assertEquals("Payload extraction cancelled", e.message)
        }

        assertTrue("Cancellation exception must be thrown", threw)
        val tmpNew = File(paths.tmpDir, "st_new")
        assertFalse("Staging directory must be cleaned up on cancellation", tmpNew.exists())
        assertFalse("Installed version must not be recorded", prefs.contains("installed_payload_version"))
        assertFalse("App must not be considered installed", manager.isInstalled())
    }

    private fun createTarGzBytes(entries: Map<String, ByteArray>): ByteArray {
        val tarBytes = createTarBytes(entries)
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gz ->
            gz.write(tarBytes)
        }
        return baos.toByteArray()
    }

    private fun createZipBytes(entries: Map<String, ByteArray>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            for ((name, data) in entries) {
                val entry = ZipEntry(name)
                zos.putNextEntry(entry)
                zos.write(data)
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    private class FakeHttpURLConnection(
        url: URL,
        private val responseCode: Int,
        private val responseMessage: String = "OK",
        private val responseHeaders: Map<String, String> = emptyMap(),
        private val responseData: ByteArray = ByteArray(0),
    ) : HttpURLConnection(url) {
        var isDisconnected = false
        override fun connect() {}
        override fun disconnect() { isDisconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = responseCode
        override fun getResponseMessage() = responseMessage
        override fun getHeaderField(name: String): String? = responseHeaders.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        override fun getContentLength(): Int = responseData.size
        override fun getContentLengthLong(): Long = responseData.size.toLong()
        override fun getInputStream(): InputStream = ByteArrayInputStream(responseData)
    }

    @Test
    fun testAssetPayloadSourceExtraction() {
        val (manager, paths, prefs) = createTestSetup()
        val tar = createTarBytes(mapOf("st/server.js" to "console.log('ASSET');".toByteArray()))
        val tarSha = sha256(tar)
        val manifest = PayloadManager.Manifest("v1.0.0", "1.12.0", "20.10.0", "st_bundle.tar", tarSha)
        val source = AssetPayloadSource(
            assetName = "st_bundle.tar",
            manifestProvider = { manifest },
            assetOpener = { name ->
                if (name == "st_bundle.tar") {
                    ByteArrayInputStream(tar)
                } else throw IOException("Not found: $name")
            }
        )

        var progressReported = false
        manager.extract(source) { progressReported = true }

        assertTrue(progressReported)
        assertTrue(paths.stEntry.exists())
        assertEquals("console.log('ASSET');", paths.stEntry.readText())
        assertEquals("v1.0.0", prefs.getString("installed_payload_version", null))
        assertTrue(manager.isInstalled())
    }

    @Test
    fun testRemotePayloadSourceDirectStreaming() {
        val (manager, paths, prefs) = createTestSetup()
        val tarBytes = createTarBytes(mapOf("st/server.js" to "console.log('REMOTE');".toByteArray()))
        val tarSha = sha256(tarBytes)

        val fakeConn = FakeHttpURLConnection(
            url = URL("https://example.com/st_bundle.tar"),
            responseCode = 200,
            responseMessage = "OK",
            responseData = tarBytes,
        )

        val remoteSource = RemotePayloadSource(
            url = "https://example.com/st_bundle.tar",
            versionStamp = "v1.19.0-remote",
            expectedSha256 = tarSha,
            urlOpener = { fakeConn },
        )

        var byteProgressSeen = false
        manager.extract(remoteSource) { p ->
            if (p.bytesProcessed > 0L) byteProgressSeen = true
        }

        assertTrue("Progress should report streamed bytes", byteProgressSeen)
        assertTrue(paths.stEntry.exists())
        assertEquals("console.log('REMOTE');", paths.stEntry.readText())
        assertEquals("v1.19.0-remote", prefs.getString("installed_payload_version", null))
        assertTrue(manager.isInstalled())
    }

    @Test
    fun testRemotePayloadSourceChecksumMismatchRollback() {
        val (manager, paths, prefs) = createTestSetup()
        val tarBytes = createTarBytes(mapOf("st/server.js" to "console.log('REMOTE');".toByteArray()))

        val fakeConn = FakeHttpURLConnection(
            url = URL("https://example.com/st_bundle.tar"),
            responseCode = 200,
            responseData = tarBytes,
        )

        val remoteSource = RemotePayloadSource(
            url = "https://example.com/st_bundle.tar",
            versionStamp = "v1.19.0-remote",
            expectedSha256 = "0000000000000000000000000000000000000000000000000000000000000000",
            urlOpener = { fakeConn },
        )

        var threw = false
        try {
            manager.extract(remoteSource) {}
        } catch (e: IllegalStateException) {
            threw = true
            assertTrue(e.message?.contains("checksum mismatch") == true)
        }

        assertTrue(threw)
        assertFalse(File(paths.tmpDir, "st_new").exists())
        assertFalse(prefs.contains("installed_payload_version"))
        assertFalse(manager.isInstalled())
    }

    @Test
    fun testRemotePayloadSourceCancellationAbortsAndCleansUp() {
        val (manager, paths, prefs) = createTestSetup()
        val tarBytes = createTarBytes(mapOf("st/server.js" to "console.log('REMOTE');".toByteArray()))

        val fakeConn = FakeHttpURLConnection(
            url = URL("https://example.com/st_bundle.tar"),
            responseCode = 200,
            responseData = tarBytes,
        )

        val remoteSource = RemotePayloadSource(
            url = "https://example.com/st_bundle.tar",
            versionStamp = "v1.19.0-remote",
            expectedSha256 = sha256(tarBytes),
            urlOpener = { fakeConn },
        )

        var threw = false
        try {
            manager.extract(remoteSource, isCancelled = { true }) {}
        } catch (e: CancellationException) {
            threw = true
        }

        assertTrue("Cancellation should throw CancellationException", threw)
        assertFalse(File(paths.tmpDir, "st_new").exists())
        assertFalse(manager.isInstalled())
    }

    @Test
    fun testRemotePayloadSourceHttpRedirectHandling() {
        val (manager, paths, prefs) = createTestSetup()
        val tarBytes = createTarBytes(mapOf("st/server.js" to "console.log('REDIRECT');".toByteArray()))

        var openerCount = 0
        val urlOpener: (String) -> HttpURLConnection = { urlStr ->
            openerCount++
            if (urlStr == "https://example.com/initial.tar") {
                FakeHttpURLConnection(
                    url = URL(urlStr),
                    responseCode = 302,
                    responseHeaders = mapOf("Location" to "https://example.com/final.tar"),
                )
            } else {
                FakeHttpURLConnection(
                    url = URL(urlStr),
                    responseCode = 200,
                    responseData = tarBytes,
                )
            }
        }

        val remoteSource = RemotePayloadSource(
            url = "https://example.com/initial.tar",
            versionStamp = "v1.19.0-redirect",
            expectedSha256 = sha256(tarBytes),
            urlOpener = urlOpener,
        )

        manager.extract(remoteSource) {}
        assertEquals(2, openerCount)
        assertTrue(paths.stEntry.exists())
        assertEquals("console.log('REDIRECT');", paths.stEntry.readText())
        assertEquals("v1.19.0-redirect", prefs.getString("installed_payload_version", null))
    }

    @Test
    fun testRemotePayloadSourceHttpErrorThrows() {
        val (manager, paths, _) = createTestSetup()
        val fakeConn = FakeHttpURLConnection(
            url = URL("https://example.com/404.tar"),
            responseCode = 404,
            responseMessage = "Not Found",
        )

        val remoteSource = RemotePayloadSource(
            url = "https://example.com/404.tar",
            versionStamp = "v1.19.0",
            urlOpener = { fakeConn },
        )

        var threw = false
        try {
            manager.extract(remoteSource) {}
        } catch (e: IOException) {
            threw = true
            assertTrue(e.message?.contains("HTTP 404") == true)
        }

        assertTrue(threw)
        assertFalse(File(paths.tmpDir, "st_new").exists())
    }

    @Test
    fun testLocalArchivePayloadSourceTarGz() {
        val (manager, paths, prefs) = createTestSetup()
        val tarGzBytes = createTarGzBytes(mapOf("st/server.js" to "console.log('TARGZ');".toByteArray()))
        val expectedSha = sha256(tarGzBytes)

        val localSource = LocalArchivePayloadSource(
            streamOpener = { ByteArrayInputStream(tarGzBytes) },
            versionStamp = "v1.19.0-targz",
            expectedSha256 = expectedSha,
            archiveName = "sillytavern.tar.gz",
        )

        manager.extract(localSource) {}
        assertTrue(paths.stEntry.exists())
        assertEquals("console.log('TARGZ');", paths.stEntry.readText())
        assertEquals("v1.19.0-targz", prefs.getString("installed_payload_version", null))
        assertTrue(manager.isInstalled())
    }

    @Test
    fun testLocalArchivePayloadSourceZipWithEofDrain() {
        val (manager, paths, prefs) = createTestSetup()
        val zipBytes = createZipBytes(mapOf("st/server.js" to "console.log('ZIP_DRAIN');".toByteArray()))
        val expectedSha = sha256(zipBytes)

        val localSource = LocalArchivePayloadSource(
            streamOpener = { ByteArrayInputStream(zipBytes) },
            versionStamp = "v1.19.0-zip",
            expectedSha256 = expectedSha,
            archiveName = "sillytavern.zip",
        )

        manager.extract(localSource) {}
        assertTrue("server.js must be extracted from zip", paths.stEntry.exists())
        assertEquals("console.log('ZIP_DRAIN');", paths.stEntry.readText())
        assertEquals("v1.19.0-zip", prefs.getString("installed_payload_version", null))
        assertTrue("Payload should be installed after valid zip extraction with EOF drain", manager.isInstalled())
    }

    @Test
    fun testLocalArchiveZipSlipBlocked() {
        val (manager, paths, _) = createTestSetup()
        val evilZip = createZipBytes(mapOf("../../evil.txt" to "evil".toByteArray()))

        val localSource = LocalArchivePayloadSource(
            streamOpener = { ByteArrayInputStream(evilZip) },
            versionStamp = "evil",
            archiveName = "evil.zip",
        )

        var threw = false
        try {
            manager.extract(localSource) {}
        } catch (e: IllegalStateException) {
            threw = true
            assertTrue(e.message?.contains("Unsafe archive entry") == true)
        }

        assertTrue("ZipSlip entry in zip archive must throw", threw)
        assertFalse(File(paths.tmpDir, "st_new").exists())
    }

    @Test
    fun testRootDirectoryNormalization() {
        val (manager, paths, prefs) = createTestSetup()
        // Archive with single root directory "SillyTavern-1.19.0/" instead of "st/"
        val zipBytes = createZipBytes(
            mapOf(
                "SillyTavern-1.19.0/server.js" to "console.log('NORMALIZED');".toByteArray(),
                "SillyTavern-1.19.0/package.json" to "{\"version\":\"1.19.0\"}".toByteArray(),
            )
        )

        val localSource = LocalArchivePayloadSource(
            streamOpener = { ByteArrayInputStream(zipBytes) },
            versionStamp = "v1.19.0-gh",
            archiveName = "SillyTavern-1.19.0.zip",
        )

        manager.extract(localSource) {}
        assertTrue("server.js must be normalized to root of stDir", paths.stEntry.exists())
        assertEquals("console.log('NORMALIZED');", paths.stEntry.readText())
        assertTrue("package.json must also be promoted", File(paths.stDir, "package.json").exists())
    }

    @Test
    fun testAtomicRollbackWhenSwapFails() {
        val (manager, paths, prefs) = createTestSetup()
        // 1. Initial valid installation
        manager.extract {}
        paths.stEntry.writeText("WORKING_VERSION")

        // 2. Corrupt archive with no server.js
        val badZip = createZipBytes(mapOf("readme.txt" to "no server.js".toByteArray()))
        val badSource = LocalArchivePayloadSource(
            streamOpener = { ByteArrayInputStream(badZip) },
            versionStamp = "bad-payload",
            archiveName = "bad.zip",
        )

        var threw = false
        try {
            manager.extract(badSource) {}
        } catch (e: IllegalStateException) {
            threw = true
            assertTrue(e.message?.contains("server.js not found") == true)
        }

        assertTrue("Bad archive missing server.js must throw", threw)
        // Verify original working installation was restored cleanly by rollback!
        assertTrue("Original server.js must survive rollback", paths.stEntry.exists())
        assertEquals("WORKING_VERSION", paths.stEntry.readText())
    }

    @Test
    fun testIsInstalledSurvivesCustomVersion() {
        val (manager, paths, prefs) = createTestSetup(payloadVersion = "v1.0.0")
        val customZip = createZipBytes(mapOf("st/server.js" to "console.log('CUSTOM');".toByteArray()))
        val customSource = LocalArchivePayloadSource(
            streamOpener = { ByteArrayInputStream(customZip) },
            versionStamp = "custom-ota-2.0",
            archiveName = "custom.zip",
        )

        manager.extract(customSource) {}
        assertTrue("Must be considered installed even when version differs from asset manifest", manager.isInstalled())
        assertEquals("custom-ota-2.0", prefs.getString("installed_payload_version", null))
    }

    @Test
    fun testProgressReportingInputStreamEmaSpeedAndCancellation() {
        val data = ByteArray(1024) { it.toByte() }
        var reportedBytes = 0L
        var reportedSpeed = 0L
        var isCancelledFlag = false

        val stream = ProgressReportingInputStream(
            delegate = ByteArrayInputStream(data),
            totalBytes = 1024L,
            isCancelled = { isCancelledFlag },
            onProgress = { bytes, _, speed ->
                reportedBytes = bytes
                reportedSpeed = speed
            },
            throttleIntervalMs = 0L,
        )

        val buf = ByteArray(512)
        val read1 = stream.read(buf)
        assertEquals(512, read1)
        assertEquals(512L, reportedBytes)

        isCancelledFlag = true
        var threw = false
        try {
            stream.read(buf)
        } catch (e: CancellationException) {
            threw = true
            assertEquals("Payload extraction cancelled", e.message)
        }
        assertTrue("Stream must abort on cancellation", threw)
        stream.close()
    }
}
