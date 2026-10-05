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

    @Test
    fun testAtomicDirectoryPromotionWhenParentMissing() {
        val rootDir = tempFolder.newFolder("fresh_root")
        val nestedParent = File(rootDir, "deep/nested/app/files")
        val dataDir = File(nestedParent, "data")
        val configFile = File(nestedParent, "config/config.yaml")
        val tmpDir = File(rootDir, "temp")

        // Neither nestedParent nor dataDir exists
        assertFalse(nestedParent.exists())
        assertFalse(dataDir.exists())

        val manager = BackupManager(dataDir, configFile, tmpDir)

        // Create a backup archive to restore
        val srcData = tempFolder.newFolder("src_promo")
        File(srcData, "character.png").writeText("sample_png_bytes")
        val backupZip = File(tmpDir, "promo.zip").apply { parentFile?.mkdirs() }
        BackupManager(srcData, File(tempFolder.root, "none.yaml"), tmpDir).exportToFile(backupZip).getOrThrow()

        // Clean restore should create parent directories and promote dataDir atomically
        val count = manager.importFromFile(backupZip, strategy = BackupManager.Strategy.CLEAN).getOrThrow()
        assertEquals(1, count)
        assertTrue("Nested parent directory must be created", nestedParent.exists())
        assertTrue("Promoted dataDir must exist", dataDir.exists())
        assertEquals("sample_png_bytes", File(dataDir, "character.png").readText())
    }

    @Test
    fun testStandardPcArchive_extractsOnlyNeededFiles_ignoresEngineBloat() {
        val (manager, paths) = createTestManager()
        val (dataDir, configFile, tmpDir) = paths

        val pcZip = File(tmpDir, "pc_archive.zip")
        ZipFile(pcZip).use { zip ->
            val dummy = File(tmpDir, "dummy.txt").apply { writeText("ignored") }
            // Engine / runtime bloat entries
            zip.addFile(dummy, ZipParameters().apply { fileNameInZip = "node_modules/express/index.js" })
            zip.addFile(dummy, ZipParameters().apply { fileNameInZip = "server.js" })
            zip.addFile(dummy, ZipParameters().apply { fileNameInZip = "src/server.js" })
            zip.addFile(dummy, ZipParameters().apply { fileNameInZip = "package.json" })
            zip.addFile(dummy, ZipParameters().apply { fileNameInZip = "public/scripts/main.js" })
            zip.addFile(dummy, ZipParameters().apply { fileNameInZip = "public/lib/jquery.js" })

            // User data entries
            val secFile = File(tmpDir, "sec.json").apply { writeText("{\"key\":\"my_api_key\"}") }
            zip.addFile(secFile, ZipParameters().apply { fileNameInZip = "secrets.json" })

            val setFile = File(tmpDir, "set.json").apply { writeText("{\"theme\":\"dark\"}") }
            zip.addFile(setFile, ZipParameters().apply { fileNameInZip = "public/settings.json" })

            val charFile = File(tmpDir, "avatar.png").apply { writeText("avatar_bytes") }
            zip.addFile(charFile, ZipParameters().apply { fileNameInZip = "characters/bot.png" })

            val cfgFile = File(tmpDir, "cfg.yaml").apply { writeText("port: 8000\n") }
            zip.addFile(cfgFile, ZipParameters().apply { fileNameInZip = "config.yaml" })
        }

        val info = manager.inspectFile(pcZip)
        assertTrue(info.isValid)
        assertEquals(4, info.fileCount) // secrets, settings, bot.png, config.yaml
        assertTrue(info.hasData)
        assertTrue(info.hasConfig)

        val restored = manager.importFromFile(pcZip, strategy = BackupManager.Strategy.CLEAN).getOrThrow()
        assertEquals(4, restored)

        assertEquals("{\"key\":\"my_api_key\"}", File(dataDir, "default-user/secrets.json").readText())
        assertEquals("{\"theme\":\"dark\"}", File(dataDir, "default-user/settings.json").readText())
        assertEquals("avatar_bytes", File(dataDir, "default-user/characters/bot.png").readText())
        assertTrue(configFile.readText().contains("port: 8000"))

        assertFalse(File(dataDir, "server.js").exists())
        assertFalse(File(dataDir, "node_modules").exists())
        assertFalse(File(dataDir, "public").exists())
    }

    @Test
    fun testWindowsBackslashPathsNormalizedAndExtracted() {
        val (manager, paths) = createTestManager()
        val (dataDir, configFile, tmpDir) = paths

        val winZip = File(tmpDir, "win_archive.zip")
        ZipFile(winZip).use { zip ->
            val f1 = File(tmpDir, "f1.png").apply { writeText("hero_png") }
            zip.addFile(f1, ZipParameters().apply { fileNameInZip = "SillyTavern\\data\\default-user\\characters\\hero.png" })

            val f2 = File(tmpDir, "f2.yaml").apply { writeText("port: 8008\n") }
            zip.addFile(f2, ZipParameters().apply { fileNameInZip = "SillyTavern\\config.yaml" })

            val f3 = File(tmpDir, "f3.json").apply { writeText("{\"secret\":\"win\"}") }
            zip.addFile(f3, ZipParameters().apply { fileNameInZip = "SillyTavern\\data\\default-user\\secrets.json" })
        }

        val info = manager.inspectFile(winZip)
        assertTrue(info.isValid)
        assertEquals(3, info.fileCount)

        val count = manager.importFromFile(winZip, strategy = BackupManager.Strategy.CLEAN).getOrThrow()
        assertEquals(3, count)

        assertEquals("hero_png", File(dataDir, "default-user/characters/hero.png").readText())
        assertEquals("{\"secret\":\"win\"}", File(dataDir, "default-user/secrets.json").readText())
        assertTrue(configFile.readText().contains("port: 8008"))
    }

    @Test
    fun testMacOsMetadataAndDsStoreIgnoredWithoutBreakingWrapperDetection() {
        val (manager, paths) = createTestManager()
        val (dataDir, configFile, tmpDir) = paths

        val macZip = File(tmpDir, "mac_archive.zip")
        ZipFile(macZip).use { zip ->
            val dummy = File(tmpDir, "meta").apply { writeText("meta") }
            zip.addFile(dummy, ZipParameters().apply { fileNameInZip = "__MACOSX/SillyTavern/._bot.png" })
            zip.addFile(dummy, ZipParameters().apply { fileNameInZip = ".DS_Store" })
            zip.addFile(dummy, ZipParameters().apply { fileNameInZip = "SillyTavern/.DS_Store" })

            val bot = File(tmpDir, "mac_bot.png").apply { writeText("mac_bytes") }
            zip.addFile(bot, ZipParameters().apply { fileNameInZip = "SillyTavern/characters/bot.png" })

            val cfg = File(tmpDir, "mac_cfg.yaml").apply { writeText("port: 9000\n") }
            zip.addFile(cfg, ZipParameters().apply { fileNameInZip = "SillyTavern/config.yaml" })
        }

        val info = manager.inspectFile(macZip)
        assertTrue(info.isValid)
        assertEquals(2, info.fileCount) // bot.png and config.yaml

        val count = manager.importFromFile(macZip, strategy = BackupManager.Strategy.CLEAN).getOrThrow()
        assertEquals(2, count)

        assertEquals("mac_bytes", File(dataDir, "default-user/characters/bot.png").readText())
        assertTrue(configFile.readText().contains("port: 9000"))
    }

    @Test
    fun testCollisionPrecedence_ModernOverwritesLegacy() {
        val (manager, paths) = createTestManager()
        val (dataDir, _, tmpDir) = paths

        val zipFile = File(tmpDir, "collision.zip")
        ZipFile(zipFile).use { zip ->
            // Legacy files added FIRST
            val legSec = File(tmpDir, "leg_sec.json").apply { writeText("{\"version\":\"legacy\"}") }
            zip.addFile(legSec, ZipParameters().apply { fileNameInZip = "secrets.json" })

            val legSet = File(tmpDir, "leg_set.json").apply { writeText("{\"version\":\"legacy\"}") }
            zip.addFile(legSet, ZipParameters().apply { fileNameInZip = "public/settings.json" })

            // Modern files added SECOND
            val modSec = File(tmpDir, "mod_sec.json").apply { writeText("{\"version\":\"modern\"}") }
            zip.addFile(modSec, ZipParameters().apply { fileNameInZip = "data/default-user/secrets.json" })

            val modSet = File(tmpDir, "mod_set.json").apply { writeText("{\"version\":\"modern\"}") }
            zip.addFile(modSet, ZipParameters().apply { fileNameInZip = "data/default-user/settings.json" })
        }

        val count = manager.importFromFile(zipFile, strategy = BackupManager.Strategy.CLEAN).getOrThrow()
        assertEquals(2, count) // Deduplicated to 2 files

        assertEquals("{\"version\":\"modern\"}", File(dataDir, "default-user/secrets.json").readText())
        assertEquals("{\"version\":\"modern\"}", File(dataDir, "default-user/settings.json").readText())
    }

    @Test
    fun testWrapperDetectionPreservesKnownUserFolders() {
        val (manager, paths) = createTestManager()
        val (dataDir, _, tmpDir) = paths

        // All entries start with characters/ - must NOT be treated as a wrapper!
        val charZip = File(tmpDir, "chars_only.zip")
        ZipFile(charZip).use { zip ->
            val a = File(tmpDir, "a.png").apply { writeText("alice") }
            val b = File(tmpDir, "b.png").apply { writeText("bob") }
            zip.addFile(a, ZipParameters().apply { fileNameInZip = "characters/alice.png" })
            zip.addFile(b, ZipParameters().apply { fileNameInZip = "characters/bob.png" })
        }

        val info = manager.inspectFile(charZip)
        assertEquals(2, info.fileCount)

        val count = manager.importFromFile(charZip, strategy = BackupManager.Strategy.CLEAN).getOrThrow()
        assertEquals(2, count)

        assertEquals("alice", File(dataDir, "default-user/characters/alice.png").readText())
        assertEquals("bob", File(dataDir, "default-user/characters/bob.png").readText())
    }

    @Test
    fun testCaseInsensitiveFolderCanonicalization() {
        val (manager, paths) = createTestManager()
        val (dataDir, _, tmpDir) = paths

        val caseZip = File(tmpDir, "case.zip")
        ZipFile(caseZip).use { zip ->
            val f1 = File(tmpDir, "avatar.png").apply { writeText("avatar") }
            zip.addFile(f1, ZipParameters().apply { fileNameInZip = "user avatars/avatar.png" })

            val f2 = File(tmpDir, "qr.json").apply { writeText("{\"qr\":true}") }
            zip.addFile(f2, ZipParameters().apply { fileNameInZip = "quickreplies/reply.json" })

            val f3 = File(tmpDir, "nai.json").apply { writeText("{\"preset\":1}") }
            zip.addFile(f3, ZipParameters().apply { fileNameInZip = "novelai settings/preset.json" })
        }

        val count = manager.importFromFile(caseZip, strategy = BackupManager.Strategy.CLEAN).getOrThrow()
        assertEquals(3, count)

        // Verifies canonical on-disk casing required by SillyTavern on Linux/Android
        assertEquals("avatar", File(dataDir, "default-user/User Avatars/avatar.png").readText())
        assertEquals("{\"qr\":true}", File(dataDir, "default-user/QuickReplies/reply.json").readText())
        assertEquals("{\"preset\":1}", File(dataDir, "default-user/NovelAI Settings/preset.json").readText())
    }

    @Test
    fun testSelectivePublicFolderExtraction() {
        val (manager, paths) = createTestManager()
        val (dataDir, _, tmpDir) = paths

        val pubZip = File(tmpDir, "public_legacy.zip")
        ZipFile(pubZip).use { zip ->
            val c = File(tmpDir, "c.png").apply { writeText("card") }
            zip.addFile(c, ZipParameters().apply { fileNameInZip = "public/characters/npc.png" })

            val s = File(tmpDir, "s.json").apply { writeText("{\"s\":1}") }
            zip.addFile(s, ZipParameters().apply { fileNameInZip = "public/settings.json" })

            val script = File(tmpDir, "script.js").apply { writeText("console.log()") }
            zip.addFile(script, ZipParameters().apply { fileNameInZip = "public/scripts/main.js" })
            zip.addFile(script, ZipParameters().apply { fileNameInZip = "public/index.html" })
        }

        val info = manager.inspectFile(pubZip)
        assertEquals(2, info.fileCount) // Only npc.png and settings.json

        val count = manager.importFromFile(pubZip, strategy = BackupManager.Strategy.CLEAN).getOrThrow()
        assertEquals(2, count)

        assertEquals("card", File(dataDir, "default-user/characters/npc.png").readText())
        assertEquals("{\"s\":1}", File(dataDir, "default-user/settings.json").readText())
        assertFalse(File(dataDir, "default-user/scripts").exists())
    }

    @Test(expected = SecurityException::class)
    fun testMaliciousTraversalWithinNormalizedPathBlocked() {
        val (manager, paths) = createTestManager()
        val (_, _, tmpDir) = paths

        val evilZip = File(tmpDir, "evil_normalized.zip")
        ZipFile(evilZip).use { zip ->
            val dummy = File(tmpDir, "dummy.txt").apply { writeText("evil payload") }
            zip.addFile(dummy, ZipParameters().apply { fileNameInZip = "characters/../../evil.txt" })
        }

        manager.importFromFile(evilZip, strategy = BackupManager.Strategy.CLEAN).getOrThrow()
    }

    @Test
    fun testInspectionCountsOnlyExtractableFiles() {
        val (manager, paths) = createTestManager()
        val (_, _, tmpDir) = paths

        val bloatZip = File(tmpDir, "bloat.zip")
        ZipFile(bloatZip).use { zip ->
            val dummy = File(tmpDir, "dummy.txt").apply { writeText("engine file") }
            zip.addFile(dummy, ZipParameters().apply { fileNameInZip = "node_modules/express/index.js" })
            zip.addFile(dummy, ZipParameters().apply { fileNameInZip = "server.js" })
        }

        val info = manager.inspectFile(bloatZip)
        assertTrue(info.isValid)
        assertEquals(0, info.fileCount)
        assertFalse(info.hasData)
        assertFalse(info.hasConfig)
    }

    @Test
    fun testConfigSanitization_enforcesBuiltinGitAndDisablesPlugins() {
        val (manager, paths) = createTestManager()
        val (_, configFile, tmpDir) = paths

        val zip = File(tmpDir, "pc_config.zip")
        ZipFile(zip).use { z ->
            val cfg = File(tmpDir, "pc_config.yaml").apply {
                writeText("git:\n  backend: simple-git\nenableServerPlugins: true\nport: 8080\n")
            }
            z.addFile(cfg, ZipParameters().apply { fileNameInZip = "config.yaml" })
        }

        manager.importFromFile(zip, strategy = BackupManager.Strategy.CLEAN).getOrThrow()
        val sanitized = configFile.readText()

        assertTrue("simple-git must be converted to builtin", sanitized.contains("backend: builtin"))
        assertFalse("simple-git must be removed", sanitized.contains("simple-git"))
        assertTrue("enableServerPlugins must be false", sanitized.contains("enableServerPlugins: false"))
        assertTrue("other settings preserved", sanitized.contains("port: 8080"))
    }

    @Test
    fun testZippedInsideData_defaultUserFolder() {
        val (manager, paths) = createTestManager()
        val (dataDir, _, tmpDir) = paths

        val zip = File(tmpDir, "inside_data.zip")
        ZipFile(zip).use { z ->
            val a = File(tmpDir, "char.png").apply { writeText("alice_bytes") }
            z.addFile(a, ZipParameters().apply { fileNameInZip = "default-user/characters/alice.png" })

            val s = File(tmpDir, "set.json").apply { writeText("{\"user\":\"test\"}") }
            z.addFile(s, ZipParameters().apply { fileNameInZip = "default-user/settings.json" })
        }

        val info = manager.inspectFile(zip)
        assertEquals(2, info.fileCount)

        val count = manager.importFromFile(zip, strategy = BackupManager.Strategy.CLEAN).getOrThrow()
        assertEquals(2, count)

        assertEquals("alice_bytes", File(dataDir, "default-user/characters/alice.png").readText())
        assertEquals("{\"user\":\"test\"}", File(dataDir, "default-user/settings.json").readText())
    }

    @Test
    fun testCanonicalUserFolderCasingUnderDefaultUser() {
        val (manager, paths) = createTestManager()
        val (dataDir, _, tmpDir) = paths

        val zip = File(tmpDir, "casing_default_user.zip")
        ZipFile(zip).use { z ->
            val avatar = File(tmpDir, "temp_avatar.png").apply { writeText("avatar_bytes") }
            z.addFile(avatar, ZipParameters().apply { fileNameInZip = "default-user/user avatars/avatar.png" })

            val reply = File(tmpDir, "temp_reply.json").apply { writeText("{\"reply\":\"hello\"}") }
            z.addFile(reply, ZipParameters().apply { fileNameInZip = "data/default-user/quickreplies/reply.json" })
        }

        val count = manager.importFromFile(zip, strategy = BackupManager.Strategy.CLEAN).getOrThrow()
        assertEquals(2, count)

        // Must be canonicalized to User Avatars and QuickReplies
        assertTrue("User Avatars must be canonicalized", File(dataDir, "default-user/User Avatars/avatar.png").exists())
        assertEquals("avatar_bytes", File(dataDir, "default-user/User Avatars/avatar.png").readText())
        assertTrue("QuickReplies must be canonicalized", File(dataDir, "default-user/QuickReplies/reply.json").exists())
        assertEquals("{\"reply\":\"hello\"}", File(dataDir, "default-user/QuickReplies/reply.json").readText())
    }

    @Test
    fun testCleanRestoreRollbackOnFailureRestoresOriginalState() {
        val (manager, paths) = createTestManager()
        val (dataDir, configFile, tmpDir) = paths

        // 1. Setup pre-existing state
        val originalFile = File(dataDir, "default-user/characters/original.json").apply {
            parentFile?.mkdirs()
            writeText("original_content")
        }
        configFile.parentFile?.mkdirs()
        configFile.writeText("port: 5000\noriginal: true")

        // 2. Create archive to import
        val zip = File(tmpDir, "new_import.zip")
        ZipFile(zip).use { z ->
            val newFile = File(tmpDir, "new.json").apply { writeText("new_content") }
            z.addFile(newFile, ZipParameters().apply { fileNameInZip = "default-user/characters/new.json" })
        }

        // 3. Attempt clean import but throw simulated crash during promotion
        val result = manager.importFromFile(
            archiveFile = zip,
            strategy = BackupManager.Strategy.CLEAN,
            onProgress = { msg ->
                if (msg == "Applying restored data…") {
                    throw RuntimeException("Simulated I/O failure during promotion")
                }
            }
        )

        // 4. Verification: import must have failed
        assertTrue("Restore must fail on simulated error", result.isFailure)
        assertEquals("Simulated I/O failure during promotion", result.exceptionOrNull()?.message)

        // 5. Verification: original state must be fully restored by rollback
        assertTrue("Original data file must exist", originalFile.exists())
        assertEquals("original_content", originalFile.readText())
        assertFalse("New file must not exist in dataDir", File(dataDir, "default-user/characters/new.json").exists())

        assertTrue("Original config must exist", configFile.exists())
        assertTrue("Original config content preserved", configFile.readText().contains("port: 5000"))

        // 6. Verification: rollback and staging temp dirs are cleaned up
        val parentFiles = dataDir.parentFile?.listFiles().orEmpty()
        assertTrue("No rollback directories left behind", parentFiles.none { it.name.startsWith(".data_rollback_") })
        assertTrue("No rollback config files left behind", parentFiles.none { it.name.startsWith(".config_rollback_") })
        assertTrue("No staging roots left behind", parentFiles.none { it.name.startsWith(".restore_staging_") })
    }
}
