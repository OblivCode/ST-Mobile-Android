package app.stmobile.sillytavern

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import app.stmobile.AppPaths
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.Instant

/**
 * Single source of truth for SillyTavern user data export and import (PLAN.md §3.1, Phase F).
 *
 * Manages persistent user state (`data/` and `config/config.yaml`), leaving the disposable
 * `st/` web root untouched. Supports unencrypted plain ZIPs and AES-256 password-encrypted
 * archives, pre-flight archive inspection, atomic clean replacement with rollback, and
 * in-place additive merging.
 */
class BackupManager(
    private val dataDir: File,
    private val configFile: File,
    private val tmpDir: File,
    private val stVersionProvider: () -> String? = { null },
    private val appVersion: String = "0.1.0",
    private val contentResolver: ContentResolver? = null,
) {

    /** Standard Android constructor binding AppPaths and PayloadManager. */
    constructor(context: Context) : this(
        dataDir = AppPaths(context).dataDir,
        configFile = AppPaths(context).configFile,
        tmpDir = AppPaths(context).tmpDir,
        stVersionProvider = resolveStVersion(context),
        appVersion = resolveAppVersion(context),
        contentResolver = context.contentResolver,
    )

    /** Metadata embedded in exported archives (`backup_manifest.json`). */
    data class Manifest(
        val backupVersion: Int = 1,
        val appVersion: String,
        val stVersion: String?,
        val createdAt: String,
        val fileCount: Int,
    ) {
        fun toJson(): String {
            return JSONObject().apply {
                put("backup_version", backupVersion)
                put("app_version", appVersion)
                put("st_version", stVersion ?: JSONObject.NULL)
                put("created_at", createdAt)
                put("file_count", fileCount)
            }.toString(2)
        }

        companion object {
            fun fromJson(jsonStr: String): Manifest {
                val obj = JSONObject(jsonStr)
                return Manifest(
                    backupVersion = obj.optInt("backup_version", 1),
                    appVersion = obj.optString("app_version", "0.1.0"),
                    stVersion = obj.optString("st_version").takeIf { it.isNotBlank() && it != "null" },
                    createdAt = obj.optString("created_at", Instant.now().toString()),
                    fileCount = obj.optInt("file_count", 0),
                )
            }
        }
    }

    /** Dual restore strategies for user data. */
    enum class Strategy {
        /** Atomically wipes and replaces data/ and config.yaml, leaving zero stale files. */
        CLEAN,

        /** Copies archive files over existing data without deleting unmentioned local files. */
        MERGE,
    }

    /** Pre-flight archive inspection details. */
    data class ArchiveInfo(
        val isValid: Boolean,
        val isEncrypted: Boolean,
        val isUnlocked: Boolean,
        val manifest: Manifest?,
        val fileCount: Int,
        val uncompressedSize: Long,
        val hasData: Boolean,
        val hasConfig: Boolean,
        val errorMessage: String? = null,
    )

    /**
     * Inspects a local archive file, determining validity, encryption status, and metadata.
     * If encrypted and password is null/incorrect, reports isUnlocked = false.
     */
    fun inspectFile(archiveFile: File, password: String? = null): ArchiveInfo {
        if (!archiveFile.exists() || archiveFile.length() == 0L) {
            return ArchiveInfo(
                isValid = false,
                isEncrypted = false,
                isUnlocked = false,
                manifest = null,
                fileCount = 0,
                uncompressedSize = 0L,
                hasData = false,
                hasConfig = false,
                errorMessage = "File is empty or does not exist",
            )
        }

        return try {
            val charPassword = password?.takeIf { it.isNotBlank() }?.toCharArray()
            val zipFile = ZipFile(archiveFile, charPassword)

            if (!zipFile.isValidZipFile) {
                return ArchiveInfo(
                    isValid = false,
                    isEncrypted = false,
                    isUnlocked = false,
                    manifest = null,
                    fileCount = 0,
                    uncompressedSize = 0L,
                    hasData = false,
                    hasConfig = false,
                    errorMessage = "Not a valid ZIP archive",
                )
            }

            val isEncrypted = zipFile.isEncrypted
            if (isEncrypted && charPassword == null) {
                // Encrypted archive awaiting password
                return ArchiveInfo(
                    isValid = true,
                    isEncrypted = true,
                    isUnlocked = false,
                    manifest = null,
                    fileCount = zipFile.fileHeaders.count { !it.isDirectory && it.fileName.replace('\\', '/').trimStart('/') != MANIFEST_NAME },
                    uncompressedSize = zipFile.fileHeaders.sumOf { it.uncompressedSize },
                    hasData = zipFile.fileHeaders.any { isDataEntry(it.fileName) },
                    hasConfig = zipFile.fileHeaders.any { isConfigEntry(it.fileName) },
                )
            }

            // Test reading encrypted header if password was provided
            if (isEncrypted) {
                val firstEncrypted = zipFile.fileHeaders.firstOrNull { it.isEncrypted && !it.isDirectory }
                if (firstEncrypted != null) {
                    try {
                        zipFile.getInputStream(firstEncrypted).use { stream ->
                            val buf = ByteArray(16)
                            stream.read(buf)
                        }
                    } catch (ze: ZipException) {
                        return ArchiveInfo(
                            isValid = true,
                            isEncrypted = true,
                            isUnlocked = false,
                            manifest = null,
                            fileCount = zipFile.fileHeaders.count { !it.isDirectory && it.fileName.replace('\\', '/').trimStart('/') != MANIFEST_NAME },
                            uncompressedSize = zipFile.fileHeaders.sumOf { it.uncompressedSize },
                            hasData = false,
                            hasConfig = false,
                            errorMessage = "Incorrect password",
                        )
                    }
                }
            }

            // Extract embedded manifest if present
            var manifest: Manifest? = null
            val manifestHeader = zipFile.fileHeaders.firstOrNull {
                it.fileName.replace('\\', '/').trimStart('/') == MANIFEST_NAME
            }
            if (manifestHeader != null) {
                try {
                    val text = zipFile.getInputStream(manifestHeader).bufferedReader(Charsets.UTF_8).use { it.readText() }
                    manifest = Manifest.fromJson(text)
                } catch (_: Exception) {
                    // Ignore manifest parse errors for legacy compatibility
                }
            }

            val headers = zipFile.fileHeaders
            ArchiveInfo(
                isValid = true,
                isEncrypted = isEncrypted,
                isUnlocked = true,
                manifest = manifest,
                fileCount = headers.count { !it.isDirectory && it.fileName.replace('\\', '/').trimStart('/') != MANIFEST_NAME },
                uncompressedSize = headers.sumOf { it.uncompressedSize },
                hasData = headers.any { isDataEntry(it.fileName) },
                hasConfig = headers.any { isConfigEntry(it.fileName) },
            )
        } catch (t: Throwable) {
            ArchiveInfo(
                isValid = false,
                isEncrypted = false,
                isUnlocked = false,
                manifest = null,
                fileCount = 0,
                uncompressedSize = 0L,
                hasData = false,
                hasConfig = false,
                errorMessage = t.message ?: "Failed to inspect archive",
            )
        }
    }

    /** Stages SAF Uri to a temporary file, inspects it, and purges the temp file in finally. */
    fun inspect(uri: Uri, password: String? = null): ArchiveInfo {
        val cr = contentResolver ?: throw IllegalStateException("ContentResolver is required for Uri inspection")
        tmpDir.mkdirs()
        val staged = File(tmpDir, "inspect_staged_${System.currentTimeMillis()}.tmp")
        return try {
            cr.openInputStream(uri)?.use { input ->
                staged.outputStream().use { out -> input.copyTo(out) }
            } ?: return ArchiveInfo(
                isValid = false,
                isEncrypted = false,
                isUnlocked = false,
                manifest = null,
                fileCount = 0,
                uncompressedSize = 0L,
                hasData = false,
                hasConfig = false,
                errorMessage = "Cannot open source file",
            )
            inspectFile(staged, password)
        } finally {
            if (staged.exists()) staged.delete()
        }
    }

    /**
     * Exports user state (`data/` and `config.yaml`) directly to a target File.
     * Uses AES-256 encryption if a non-blank password is provided.
     */
    fun exportToFile(
        targetZipFile: File,
        password: String? = null,
        onProgress: (String) -> Unit = {},
    ): Result<Manifest> = runCatching {
        onProgress("Preparing archive…")
        targetZipFile.parentFile?.mkdirs()

        // Gather all files to include in data/
        val filesToArchive = mutableListOf<Pair<File, String>>()
        if (dataDir.exists()) {
            val base = dataDir.canonicalPath
            dataDir.walkTopDown().forEach { file ->
                if (file.isFile) {
                    val rel = file.canonicalPath.removePrefix(base)
                        .trimStart(File.separatorChar)
                        .replace(File.separatorChar, '/')
                    filesToArchive.add(file to "data/$rel")
                }
            }
        }
        if (configFile.exists()) {
            filesToArchive.add(configFile to "config.yaml")
        }

        val manifest = Manifest(
            backupVersion = 1,
            appVersion = appVersion,
            stVersion = stVersionProvider(),
            createdAt = Instant.now().toString(),
            fileCount = filesToArchive.size,
        )

        val charPassword = password?.takeIf { it.isNotBlank() }?.toCharArray()
        ZipOutputStream(FileOutputStream(targetZipFile), charPassword).use { zos ->
            // 1. Write backup_manifest.json (unencrypted metadata entry)
            val manifestParams = ZipParameters().apply {
                fileNameInZip = MANIFEST_NAME
                compressionMethod = CompressionMethod.DEFLATE
            }
            zos.putNextEntry(manifestParams)
            zos.write(manifest.toJson().toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // 2. Stream user files with optional AES-256 encryption
            var count = 0
            val total = filesToArchive.size
            for ((file, entryName) in filesToArchive) {
                val params = ZipParameters().apply {
                    fileNameInZip = entryName
                    compressionMethod = CompressionMethod.DEFLATE
                    if (charPassword != null) {
                        isEncryptFiles = true
                        encryptionMethod = EncryptionMethod.AES
                        aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                    }
                }
                zos.putNextEntry(params)
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
                count++
                if (count % 25 == 0 || count == total) {
                    onProgress("Archiving: $count / $total files…")
                }
            }
        }

        onProgress("Backup complete")
        manifest
    }

    /**
     * Exports user state to a destination SAF Uri via staged temporary archive.
     */
    fun export(
        uri: Uri,
        password: String? = null,
        onProgress: (String) -> Unit = {},
    ): Result<Unit> = runCatching {
        val cr = contentResolver ?: throw IllegalStateException("ContentResolver required for Uri export")
        tmpDir.mkdirs()
        val staged = File(tmpDir, "staged_export_${System.currentTimeMillis()}.zip")
        try {
            exportToFile(staged, password, onProgress).getOrThrow()
            onProgress("Writing backup to destination…")
            val out = cr.openOutputStream(uri)
                ?: throw IllegalStateException("Cannot open destination for writing")
            out.use { os ->
                FileInputStream(staged).use { fis -> fis.copyTo(os) }
            }
        } finally {
            if (staged.exists()) staged.delete()
        }
    }

    /**
     * Restores user state from a local archive file according to the chosen strategy.
     */
    fun importFromFile(
        archiveFile: File,
        password: String? = null,
        strategy: Strategy = Strategy.CLEAN,
        onProgress: (String) -> Unit = {},
    ): Result<Int> = runCatching {
        val inspection = inspectFile(archiveFile, password)
        if (!inspection.isValid) throw IllegalArgumentException(inspection.errorMessage ?: "Invalid archive")
        if (inspection.isEncrypted && !inspection.isUnlocked) {
            throw IllegalArgumentException(inspection.errorMessage ?: "Password required to unlock archive")
        }

        val charPassword = password?.takeIf { it.isNotBlank() }?.toCharArray()
        val zipFile = ZipFile(archiveFile, charPassword)

        when (strategy) {
            Strategy.CLEAN -> performCleanRestore(zipFile, onProgress)
            Strategy.MERGE -> performMergeRestore(zipFile, onProgress)
        }
    }

    /**
     * Restores user state from an incoming SAF Uri via staged temporary archive.
     */
    fun import(
        uri: Uri,
        password: String? = null,
        strategy: Strategy = Strategy.CLEAN,
        onProgress: (String) -> Unit = {},
    ): Result<Int> = runCatching {
        val cr = contentResolver ?: throw IllegalStateException("ContentResolver required for Uri import")
        tmpDir.mkdirs()
        val staged = File(tmpDir, "staged_import_${System.currentTimeMillis()}.zip")
        try {
            onProgress("Reading backup archive…")
            cr.openInputStream(uri)?.use { input ->
                staged.outputStream().use { out -> input.copyTo(out) }
            } ?: throw IllegalStateException("Cannot open chosen backup file")
            importFromFile(staged, password, strategy, onProgress).getOrThrow()
        } finally {
            if (staged.exists()) staged.delete()
        }
    }

    /**
     * Atomically swaps existing data/ and config.yaml with archive contents.
     * Employs unified rollback covering both targets if extraction or swap fails midway.
     */
    private fun performCleanRestore(zipFile: ZipFile, onProgress: (String) -> Unit): Int {
        val stagingRoot = File(tmpDir, "restore_staging_${System.currentTimeMillis()}")
        val stagingData = File(stagingRoot, "data")
        val stagingConfig = File(stagingRoot, "config.yaml")

        val rollbackData = File(tmpDir, "data_rollback_${System.currentTimeMillis()}")
        val rollbackConfig = File(tmpDir, "config_rollback_${System.currentTimeMillis()}.yaml")

        var restoredFilesCount = 0

        try {
            stagingRoot.mkdirs()
            onProgress("Extracting archive to staging…")

            // 1. Extract all target files into temporary staging folder
            val headers = zipFile.fileHeaders.filter { !it.isDirectory }
            for (header in headers) {
                val normalized = header.fileName.replace('\\', '/').trimStart('/')
                when {
                    normalized.startsWith("data/") -> {
                        val rel = normalized.removePrefix("data/").trimStart('/')
                        if (rel.isNotEmpty()) {
                            val target = validateSafeChild(stagingData, rel)
                            target.parentFile?.mkdirs()
                            zipFile.getInputStream(header).use { input ->
                                target.outputStream().use { out -> input.copyTo(out) }
                            }
                            restoredFilesCount++
                            if (restoredFilesCount % 100 == 0) {
                                onProgress("Staging… $restoredFilesCount files")
                            }
                        }
                    }
                    normalized == "config.yaml" || normalized == "config/config.yaml" -> {
                        stagingConfig.parentFile?.mkdirs()
                        zipFile.getInputStream(header).use { input ->
                            stagingConfig.outputStream().use { out -> input.copyTo(out) }
                        }
                        restoredFilesCount++
                    }
                }
            }

            // 2. Prepare unified rollback: preserve existing dataDir and configFile
            if (dataDir.exists()) {
                if (!dataDir.renameTo(rollbackData)) {
                    dataDir.copyRecursively(rollbackData, overwrite = true)
                    dataDir.deleteRecursively()
                }
            }
            if (configFile.exists()) {
                configFile.copyTo(rollbackConfig, overwrite = true)
            }

            // 3. Promote staged data into real locations
            onProgress("Applying restored data…")
            if (stagingData.exists()) {
                dataDir.parentFile?.mkdirs()
                if (!stagingData.renameTo(dataDir)) {
                    dataDir.mkdirs()
                    stagingData.copyRecursively(dataDir, overwrite = true)
                }
            } else {
                dataDir.mkdirs()
            }

            if (stagingConfig.exists()) {
                configFile.parentFile?.mkdirs()
                stagingConfig.copyTo(configFile, overwrite = true)
            }

            // 4. Success: cleanup rollback and staging
            if (rollbackData.exists()) rollbackData.deleteRecursively()
            if (rollbackConfig.exists()) rollbackConfig.delete()
            stagingRoot.deleteRecursively()

            onProgress("Restore complete ($restoredFilesCount files)")
            return restoredFilesCount
        } catch (t: Throwable) {
            // Unified Rollback on any failure
            if (rollbackData.exists()) {
                dataDir.deleteRecursively()
                dataDir.parentFile?.mkdirs()
                if (!rollbackData.renameTo(dataDir)) {
                    dataDir.mkdirs()
                    rollbackData.copyRecursively(dataDir, overwrite = true)
                    rollbackData.deleteRecursively()
                }
            }
            if (rollbackConfig.exists()) {
                rollbackConfig.copyTo(configFile, overwrite = true)
                rollbackConfig.delete()
            }
            stagingRoot.deleteRecursively()
            throw t
        }
    }

    /**
     * Extracts archive entries directly over local files without deleting unmentioned files.
     */
    private fun performMergeRestore(zipFile: ZipFile, onProgress: (String) -> Unit): Int {
        var count = 0
        val headers = zipFile.fileHeaders.filter { !it.isDirectory }
        for (header in headers) {
            val normalized = header.fileName.replace('\\', '/').trimStart('/')
            val target: File? = when {
                normalized.startsWith("data/") -> {
                    val rel = normalized.removePrefix("data/").trimStart('/')
                    if (rel.isNotEmpty()) validateSafeChild(dataDir, rel) else null
                }
                normalized == "config.yaml" || normalized == "config/config.yaml" -> configFile
                else -> null
            }

            if (target != null) {
                target.parentFile?.mkdirs()
                zipFile.getInputStream(header).use { input ->
                    target.outputStream().use { out -> input.copyTo(out) }
                }
                count++
                if (count % 100 == 0) {
                    onProgress("Merging… $count files")
                }
            }
        }
        onProgress("Merge complete ($count files)")
        return count
    }

    /** Hardened Zip Slip path traversal defense enforcing trailing separator bounds. */
    fun validateSafeChild(baseDir: File, entryName: String): File {
        val normalized = entryName.replace('\\', '/')
        val baseCanonical = baseDir.canonicalPath + File.separator
        val target = File(baseDir, normalized).canonicalFile
        if (!target.path.startsWith(baseCanonical) && target.path != baseDir.canonicalPath) {
            throw SecurityException("Zip Slip path traversal blocked: $entryName")
        }
        return target
    }

    private fun isDataEntry(name: String): Boolean {
        val normalized = name.replace('\\', '/').trimStart('/')
        return normalized.startsWith("data/")
    }

    private fun isConfigEntry(name: String): Boolean {
        val normalized = name.replace('\\', '/').trimStart('/')
        return normalized == "config.yaml" || normalized == "config/config.yaml"
    }

    companion object {
        const val MANIFEST_NAME = "backup_manifest.json"

        private fun resolveStVersion(context: Context): () -> String? = {
            runCatching { PayloadManager(context).readManifest().stVersion }.getOrNull()
        }

        private fun resolveAppVersion(context: Context): String = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.1.0"
        }.getOrDefault("0.1.0")
    }
}
