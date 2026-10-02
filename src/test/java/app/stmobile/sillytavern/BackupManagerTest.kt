package app.stmobile.sillytavern

import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream

class BackupManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createTestManager(
        dataDir: File = tempFolder.newFolder("data"),
        configFile: File = File(tempFolder.newFolder("config"), "config.yaml"),
        tmpDir: File = tempFolder.newFolder("tmp"),
        stVersion: String = "1.12.11",
        appVersion: String = "0.2.0",
    ): Pair<BackupManager, Triple<File, File, File>> {
        val manager = BackupManager(
            dataDir = dataDir,
            configFile = configFile,
            tmpDir = tmpDir,
            stVersionProvider = { stVersion },
            appVersion = appVersion,
        )
        return manager to Triple(dataDir, configFile, tmpDir)
    }

    @Test
    fun testExportUnencryptedArchiveWithManifest() {
        val (manager, paths) = createTestManager()
        val (dataDir, configFile, tmpDir) = paths

        // Populate sample data
        configFile.writeText("port: 8000\nlisten: true\n")
        val charDir = File(dataDir, "characters").apply { mkdirs() }
        File(charDir, "bot.json").writeText("{\"name\": \"Assistant\"}")

        val exportZip = File(tmpDir, "backup.zip")
        val result = manager.exportToFile(exportZip)

        assertTrue("Export should succeed", result.isSuccess)
        val manifest = result.getOrThrow()
        assertEquals(2, manifest.fileCount) // bot.json + config.yaml
        assertEquals("1.12.11", manifest.stVersion)
        assertEquals("0.2.0", manifest.appVersion)

        // Verify ZIP contents directly
        val zipFile = ZipFile(exportZip)
        assertFalse("Archive should not be encrypted", zipFile.isEncrypted)
        val entryNames = zipFile.fileHeaders.map { it.fileName.replace('\\', '/') }
        assertTrue(entryNames.contains(BackupManager.MANIFEST_NAME))
        assertTrue(entryNames.contains("config.yaml"))
        assertTrue(entryNames.contains("data/characters/bot.json"))
    }

    @Test
    fun testExportAes256EncryptedArchive() {
        val (manager, paths) = createTestManager()
        val (dataDir, configFile, tmpDir) = paths

        configFile.writeText("secretApiKey: 12345\n")
        val chatDir = File(dataDir, "chats").apply { mkdirs() }
        File(chatDir, "chat1.json").writeText("{\"messages\": []}")

        val exportZip = File(tmpDir, "encrypted.zip")
        val password = "SuperSecretPassword123"
        val result = manager.exportToFile(exportZip, password = password)

        assertTrue(result.isSuccess)

        // Inspect with zip4j
        val zipFile = ZipFile(exportZip)
        assertTrue("Archive must be encrypted", zipFile.isEncrypted)

        // Reading without password should fail
        var failedWithoutPassword = false
        try {
            val fileHeader = zipFile.fileHeaders.first { it.fileName.contains("chat1.json") }
            zipFile.getInputStream(fileHeader).use { it.read() }
        } catch (_: Exception) {
            failedWithoutPassword = true
        }
        assertTrue("Reading encrypted entry without password must fail", failedWithoutPassword)

        // Reading with password should succeed
        zipFile.setPassword(password.toCharArray())
        val header = zipFile.fileHeaders.first { it.fileName.contains("chat1.json") }
        val content = zipFile.getInputStream(header).bufferedReader().use { it.readText() }
        assertEquals("{\"messages\": []}", content)
    }

    @Test
    fun testInspectionPreflightUnencrypted() {
        val (manager, paths) = createTestManager()
        val (dataDir, configFile, tmpDir) = paths

        configFile.writeText("port: 9000\n")
        File(dataDir, "test.txt").writeText("hello world")

        val zip = File(tmpDir, "test.zip")
        manager.exportToFile(zip).getOrThrow()

        val info = manager.inspectFile(zip)
        assertTrue(info.isValid)
        assertFalse(info.isEncrypted)
        assertTrue(info.isUnlocked)
        assertNotNull(info.manifest)
        assertEquals(2, info.fileCount)
        assertTrue(info.hasData)
        assertTrue(info.hasConfig)
        assertNull(info.errorMessage)
    }

    @Test
    fun testInspectionPreflightEncryptedAndValidation() {
        val (manager, paths) = createTestManager()
        val (dataDir, configFile, tmpDir) = paths

        configFile.writeText("port: 9000\n")
        File(dataDir, "test.txt").writeText("encrypted content")

        val zip = File(tmpDir, "encrypted.zip")
        val password = "StrongPassword456"
        manager.exportToFile(zip, password = password).getOrThrow()

        // Phase 1: Inspect without password
        val lockedInfo = manager.inspectFile(zip)
        assertTrue(lockedInfo.isValid)
        assertTrue(lockedInfo.isEncrypted)
        assertFalse(lockedInfo.isUnlocked)
        assertNull(lockedInfo.manifest)

        // Wrong password rejection
        val wrongPassInfo = manager.inspectFile(zip, password = "WrongPassword")
        assertTrue(wrongPassInfo.isValid)
        assertTrue(wrongPassInfo.isEncrypted)
        assertFalse(wrongPassInfo.isUnlocked)
        assertEquals("Incorrect password", wrongPassInfo.errorMessage)

        // Correct password unlock
        val unlockedInfo = manager.inspectFile(zip, password = password)
        assertTrue(unlockedInfo.isValid)
        assertTrue(unlockedInfo.isEncrypted)
        assertTrue(unlockedInfo.isUnlocked)
        assertNotNull(unlockedInfo.manifest)
        assertEquals(2, unlockedInfo.fileCount)
    }

    @Test
    fun testCleanRestoreAtomicReplacement() {
        val (manager, paths) = createTestManager()
        val (dataDir, configFile, tmpDir) = paths

        // 1. Existing local state before restore: contains stale files
        configFile.writeText("original: config\n")
        val staleFolder = File(dataDir, "stale").apply { mkdirs() }
        val staleFile = File(staleFolder, "old.txt").apply { writeText("should be deleted") }

        // 2. Prepare backup archive with new data
        val archiveSource = tempFolder.newFolder("archive_src")
        val archiveData = File(archiveSource, "data").apply { mkdirs() }
        File(archiveData, "new_char.png").writeText("image_bytes")
        val archiveConfig = File(archiveSource, "config.yaml").apply { writeText("restored: true\n") }

        val backupZip = File(tmpDir, "to_restore.zip")
        val prepManager = BackupManager(
            dataDir = archiveData,
            configFile = archiveConfig,
            tmpDir = tmpDir,
        )
        prepManager.exportToFile(backupZip).getOrThrow()

        // 3. Perform Clean Restore
        val restoredCount = manager.importFromFile(backupZip, strategy = BackupManager.Strategy.CLEAN).getOrThrow()
        assertEquals(2, restoredCount) // new_char.png + config.yaml

        // Verify stale file is gone
        assertFalse("Stale file should be purged in clean restore", staleFile.exists())
        assertFalse("Stale folder should be purged in clean restore", staleFolder.exists())

        // Verify new files exist
        val restoredFile = File(dataDir, "new_char.png")
        assertTrue("Restored file must exist", restoredFile.exists())
        assertEquals("image_bytes", restoredFile.readText())

        // Verify config.yaml was updated
        assertEquals("restored: true\n", configFile.readText())
    }

    @Test
    fun testCleanRestoreOnFreshDevice() {
        // dataDir does not exist yet (fresh install)
        val dataDir = File(tempFolder.root, "non_existent_data")
        val configFile = File(tempFolder.root, "non_existent_config.yaml")
        val tmpDir = tempFolder.newFolder("tmp_fresh")

        val manager = BackupManager(dataDir, configFile, tmpDir)

        // Prepare archive
        val srcData = tempFolder.newFolder("src_data")
        File(srcData, "welcome.json").writeText("{}")
        val backupZip = File(tmpDir, "fresh_backup.zip")
        BackupManager(srcData, File(tempFolder.root, "none"), tmpDir).exportToFile(backupZip).getOrThrow()

        val count = manager.importFromFile(backupZip, strategy = BackupManager.Strategy.CLEAN).getOrThrow()
        assertEquals(1, count)
        assertTrue("Data dir should have been created", dataDir.exists())
        assertTrue(File(dataDir, "welcome.json").exists())
    }

    @Test
    fun testMergeRestorePreservesLocalFiles() {
        val (manager, paths) = createTestManager()
        val (dataDir, configFile, tmpDir) = paths

        // Local state has an unmentioned file
        val localOnly = File(dataDir, "my_custom_char.json").apply {
            parentFile?.mkdirs()
            writeText("{\"custom\": true}")
        }
        val conflictingFile = File(dataDir, "shared.txt").apply {
            writeText("local version")
        }

        // Archive contains update to shared.txt and a new file
        val archiveSource = tempFolder.newFolder("merge_src")
        val archiveData = File(archiveSource, "data").apply { mkdirs() }
        File(archiveData, "shared.txt").writeText("archive version")
        File(archiveData, "new_file.txt").writeText("new")

        val backupZip = File(tmpDir, "merge_backup.zip")
        BackupManager(archiveData, File(archiveSource, "config.yaml"), tmpDir).exportToFile(backupZip).getOrThrow()

        manager.importFromFile(backupZip, strategy = BackupManager.Strategy.MERGE).getOrThrow()

        // Unmentioned file must be preserved
        assertTrue("Unmentioned local file must survive merge restore", localOnly.exists())
        assertEquals("{\"custom\": true}", localOnly.readText())

        // Conflicting file must be updated
        assertEquals("archive version", conflictingFile.readText())

        // New file must exist
        assertTrue(File(dataDir, "new_file.txt").exists())
    }

    @Test(expected = SecurityException::class)
    fun testZipSlipVulnerabilityRejection() {
        val (manager, paths) = createTestManager()
        val (_, _, tmpDir) = paths

        // Construct malicious zip with ../ path traversal
        val evilZip = File(tmpDir, "evil.zip")
        ZipFile(evilZip).use { zip ->
            val evilParams = ZipParameters().apply {
                fileNameInZip = "data/../../../../evil.txt"
            }
            val dummy = File(tmpDir, "dummy.txt").apply { writeText("evil payload") }
            zip.addFile(dummy, evilParams)
        }

        // Clean restore must block with SecurityException
        manager.importFromFile(evilZip, strategy = BackupManager.Strategy.CLEAN).getOrThrow()
    }

    @Test
    fun testRestoreLegacyArchiveWithoutManifest() {
        val (manager, paths) = createTestManager()
        val (dataDir, configFile, tmpDir) = paths

        // Manually build ZIP without backup_manifest.json
        val legacyZip = File(tmpDir, "legacy.zip")
        ZipFile(legacyZip).use { zip ->
            val p1 = ZipParameters().apply { fileNameInZip = "data/legacy_char.json" }
            val dummy1 = File(tmpDir, "d1.txt").apply { writeText("{\"name\":\"legacy\"}") }
            zip.addFile(dummy1, p1)

            val p2 = ZipParameters().apply { fileNameInZip = "config.yaml" }
            val dummy2 = File(tmpDir, "d2.txt").apply { writeText("legacyPort: 8080\n") }
            zip.addFile(dummy2, p2)
        }

        val info = manager.inspectFile(legacyZip)
        assertTrue(info.isValid)
        assertNull("Legacy archive should not have manifest", info.manifest)
        assertTrue(info.hasData)
        assertTrue(info.hasConfig)

        val restoredCount = manager.importFromFile(legacyZip, strategy = BackupManager.Strategy.CLEAN).getOrThrow()
        assertEquals(2, restoredCount)
        assertTrue(File(dataDir, "legacy_char.json").exists())
        assertEquals("legacyPort: 8080\n", configFile.readText())
    }

    @Test
    fun testCorruptedZipInspection() {
        val (manager, paths) = createTestManager()
        val (_, _, tmpDir) = paths

        val emptyFile = File(tmpDir, "empty.zip").apply { createNewFile() }
        val emptyInfo = manager.inspectFile(emptyFile)
        assertFalse(emptyInfo.isValid)

        val junkFile = File(tmpDir, "junk.zip").apply { writeText("not a zip file content") }
        val junkInfo = manager.inspectFile(junkFile)
        assertFalse(junkInfo.isValid)
    }

    @Test
    fun testExportEmptyDataDir() {
        val (manager, paths) = createTestManager()
        val (_, _, tmpDir) = paths

        val zip = File(tmpDir, "empty_data.zip")
        val result = manager.exportToFile(zip)
        assertTrue(result.isSuccess)
        val manifest = result.getOrThrow()
        assertEquals(0, manifest.fileCount)

        val info = manager.inspectFile(zip)
        assertTrue(info.isValid)
        assertEquals(0, info.fileCount)
    }

    @Test
    fun testExportAndInspectionWithVersionCodeOnly() {
        val (manager, paths) = createTestManager(appVersion = "1")
        val (dataDir, configFile, tmpDir) = paths

        configFile.writeText("port: 8000\n")
        File(dataDir, "prompt.txt").writeText("system prompt")

        val zip = File(tmpDir, "version_code_backup.zip")
        val result = manager.exportToFile(zip)
        assertTrue(result.isSuccess)
        val manifest = result.getOrThrow()
        assertEquals("1", manifest.appVersion)

        val info = manager.inspectFile(zip)
        assertTrue(info.isValid)
        assertNotNull(info.manifest)
        assertEquals("1", info.manifest?.appVersion)
    }

    @Test
    fun testResolveVersionLogic() {
        // Explicit non-blank versionName takes precedence
        assertEquals("1.2.0", BackupManager.resolveVersion("1.2.0", 42L))

        // null versionName falls back to versionCode string
        assertEquals("42", BackupManager.resolveVersion(null, 42L))

        // empty or blank versionName falls back to versionCode string
        assertEquals("42", BackupManager.resolveVersion("", 42L))
        assertEquals("42", BackupManager.resolveVersion("   ", 42L))
    }

    @Test
    fun testDefaultConstructorAppVersionIsOne() {
        val manager = BackupManager(
            dataDir = tempFolder.newFolder("default_data"),
            configFile = File(tempFolder.newFolder("default_config"), "config.yaml"),
            tmpDir = tempFolder.newFolder("default_tmp"),
        )
        val exportZip = File(tempFolder.root, "default_export.zip")
        val result = manager.exportToFile(exportZip)
        assertTrue(result.isSuccess)
        assertEquals("1", result.getOrThrow().appVersion)
    }
}
