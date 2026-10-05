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
import java.security.MessageDigest

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
}
