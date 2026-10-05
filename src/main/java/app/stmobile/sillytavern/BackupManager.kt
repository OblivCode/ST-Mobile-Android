package app.stmobile.sillytavern

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import app.stmobile.AppPaths
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.FileHeader
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
    private val appVersion: String = "1",
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

    /** Precedence tiers for resolving entry collisions between modern and legacy formats. */
    enum class EntryTier {
        LEGACY_FALLBACK, // Tier 1: root secrets.json, public/settings.json, characters/...
        CANONICAL_DATA,  // Tier 2: data/secrets.json, data/..., config.yaml
        MODERN_DEFAULT,  // Tier 3: default-user/..., data/default-user/...
    }

    /** Destination classification for an entry in an incoming archive. */
    sealed class RestoreTarget {
        data class Data(val relativePath: String, val tier: EntryTier) : RestoreTarget()
        data class Config(val tier: EntryTier) : RestoreTarget()
        data object Ignore : RestoreTarget()
    }

    /** Pre-calculated selective extraction plan derived from in-memory ZIP headers. */
    data class ResolvedRestorePlan(
        val dataEntries: Map<String, FileHeader>,
        val configEntry: FileHeader?,
        val totalFileCount: Int,
        val uncompressedSize: Long,
    )

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
     * Uses in-memory central directory headers to selectively detect user data and config
     * without decompressing any files.
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
            ZipFile(archiveFile, charPassword).use { zipFile ->
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

                val plan = resolveRestorePlan(zipFile)
                val isEncrypted = zipFile.isEncrypted
                if (isEncrypted && charPassword == null) {
                    // Encrypted archive awaiting password
                    return ArchiveInfo(
                        isValid = true,
                        isEncrypted = true,
                        isUnlocked = false,
                        manifest = null,
                        fileCount = plan.totalFileCount,
                        uncompressedSize = plan.uncompressedSize,
                        hasData = plan.dataEntries.isNotEmpty(),
                        hasConfig = plan.configEntry != null,
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
                                fileCount = plan.totalFileCount,
                                uncompressedSize = plan.uncompressedSize,
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

                ArchiveInfo(
                    isValid = true,
                    isEncrypted = isEncrypted,
                    isUnlocked = true,
                    manifest = manifest,
                    fileCount = plan.totalFileCount,
                    uncompressedSize = plan.uncompressedSize,
                    hasData = plan.dataEntries.isNotEmpty(),
                    hasConfig = plan.configEntry != null,
                )
            }
        } catch (se: SecurityException) {
            throw se
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
        ZipFile(archiveFile, charPassword).use { zipFile ->
            when (strategy) {
                Strategy.CLEAN -> performCleanRestore(zipFile, onProgress)
                Strategy.MERGE -> performMergeRestore(zipFile, onProgress)
            }
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
        val targetParent = dataDir.parentFile ?: tmpDir
        targetParent.mkdirs()
        val stagingRoot = File(targetParent, ".restore_staging_${System.currentTimeMillis()}")
        val stagingData = File(stagingRoot, "data")
        val stagingConfig = File(stagingRoot, "config.yaml")

        val rollbackData = File(targetParent, ".data_rollback_${System.currentTimeMillis()}")
        val rollbackConfig = File(targetParent, ".config_rollback_${System.currentTimeMillis()}.yaml")

        var restoredFilesCount = 0

        try {
            stagingRoot.mkdirs()
            val plan = resolveRestorePlan(zipFile)
            onProgress("Extracting archive to staging…")

            // 1. Extract data files into temporary staging folder
            for ((relPath, header) in plan.dataEntries) {
                val target = validateSafeChild(stagingData, relPath)
                target.parentFile?.mkdirs()
                zipFile.getInputStream(header).use { input ->
                    target.outputStream().use { out -> input.copyTo(out, bufferSize = 65536) }
                }
                restoredFilesCount++
                if (restoredFilesCount % 100 == 0 || restoredFilesCount == plan.totalFileCount) {
                    onProgress("Staging… $restoredFilesCount files")
                }
            }

            // 2. Extract and sanitize config if present
            if (plan.configEntry != null) {
                stagingConfig.parentFile?.mkdirs()
                val rawConfig = zipFile.getInputStream(plan.configEntry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                stagingConfig.writeText(sanitizeConfigYaml(rawConfig), Charsets.UTF_8)
                restoredFilesCount++
            }

            // 3. Prepare unified rollback: preserve existing dataDir and configFile
            if (dataDir.exists()) {
                if (!dataDir.renameTo(rollbackData)) {
                    dataDir.copyRecursively(rollbackData, overwrite = true)
                    dataDir.deleteRecursively()
                }
            }
            if (configFile.exists()) {
                configFile.copyTo(rollbackConfig, overwrite = true)
            }

            // 4. Promote staged data into real locations
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

            // 5. Success: cleanup rollback and staging
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
        val plan = resolveRestorePlan(zipFile)
        var count = 0
        for ((relPath, header) in plan.dataEntries) {
            val target = validateSafeChild(dataDir, relPath)
            target.parentFile?.mkdirs()
            zipFile.getInputStream(header).use { input ->
                target.outputStream().use { out -> input.copyTo(out, bufferSize = 65536) }
            }
            count++
            if (count % 100 == 0 || count == plan.totalFileCount) {
                onProgress("Merging… $count files")
            }
        }

        if (plan.configEntry != null) {
            configFile.parentFile?.mkdirs()
            val rawConfig = zipFile.getInputStream(plan.configEntry).bufferedReader(Charsets.UTF_8).use { it.readText() }
            configFile.writeText(sanitizeConfigYaml(rawConfig), Charsets.UTF_8)
            count++
        }
        onProgress("Merge complete ($count files)")
        return count
    }

    /** Hardened Zip Slip path traversal defense enforcing trailing separator bounds. */
    fun validateSafeChild(baseDir: File, entryName: String): File {
        val normalized = entryName.replace('\\', '/')
        if (normalized.split('/').any { it == ".." }) {
            throw SecurityException("Zip Slip path traversal blocked: $entryName")
        }
        val baseCanonical = baseDir.canonicalPath + File.separator
        val target = File(baseDir, normalized).canonicalFile
        if (!target.path.startsWith(baseCanonical) && target.path != baseDir.canonicalPath) {
            throw SecurityException("Zip Slip path traversal blocked: $entryName")
        }
        return target
    }

    internal fun resolveRestorePlan(zipFile: ZipFile): ResolvedRestorePlan {
        val headers = zipFile.fileHeaders.filter { !it.isDirectory }
        val commonPrefix = detectCommonWrapperPrefix(headers)
        val hasDefaultUser = headers.any { header ->
            val clean = header.fileName.replace('\\', '/').trimStart('/')
            val unPrefixed = if (commonPrefix.isNotEmpty() && clean.startsWith(commonPrefix)) {
                clean.removePrefix(commonPrefix).trimStart('/')
            } else clean
            unPrefixed.startsWith("default-user/", ignoreCase = true) ||
                unPrefixed.startsWith("data/default-user/", ignoreCase = true)
        }

        val dataCandidates = mutableMapOf<String, Pair<FileHeader, EntryTier>>()
        var configCandidate: Pair<FileHeader, EntryTier>? = null

        for (header in headers) {
            val target = normalizeEntry(header.fileName, commonPrefix, hasDefaultUser)
            when (target) {
                is RestoreTarget.Data -> {
                    val existing = dataCandidates[target.relativePath]
                    if (existing == null || target.tier.ordinal > existing.second.ordinal) {
                        dataCandidates[target.relativePath] = header to target.tier
                    }
                }
                is RestoreTarget.Config -> {
                    val existing = configCandidate
                    if (existing == null || target.tier.ordinal > existing.second.ordinal) {
                        configCandidate = header to target.tier
                    }
                }
                is RestoreTarget.Ignore -> { /* skip */ }
            }
        }

        val finalData = dataCandidates.mapValues { it.value.first }
        val finalConfig = configCandidate?.first
        val totalCount = finalData.size + (if (finalConfig != null) 1 else 0)
        val totalSize = finalData.values.sumOf { it.uncompressedSize } + (finalConfig?.uncompressedSize ?: 0L)

        return ResolvedRestorePlan(
            dataEntries = finalData,
            configEntry = finalConfig,
            totalFileCount = totalCount,
            uncompressedSize = totalSize,
        )
    }

    internal fun detectCommonWrapperPrefix(headers: List<FileHeader>): String {
        val nonDirHeaders = headers.filter { !it.isDirectory }
        if (nonDirHeaders.isEmpty()) return ""

        val validPaths = nonDirHeaders.mapNotNull { header ->
            val clean = header.fileName.replace('\\', '/').trimStart('/')
            if (isIgnoredMetadataOrJunk(clean)) null else clean
        }
        if (validPaths.isEmpty()) return ""

        val firstSlash = validPaths.first().indexOf('/')
        if (firstSlash <= 0) return ""
        val candidate = validPaths.first().substring(0, firstSlash)

        val candidateLower = candidate.lowercase()
        // Never strip known SillyTavern root folders
        if (candidateLower in PROTECTED_ROOT_FOLDERS || candidateLower in CANONICAL_USER_FOLDERS.keys) {
            return ""
        }

        val prefixWithSlash = "$candidate/"
        val allMatch = validPaths.all { it.startsWith(prefixWithSlash, ignoreCase = false) }
        return if (allMatch) prefixWithSlash else ""
    }

    internal fun isIgnoredMetadataOrJunk(path: String): Boolean {
        val lower = path.lowercase()
        if (lower == MANIFEST_NAME.lowercase() || lower.endsWith("/${MANIFEST_NAME.lowercase()}")) return true
        if (lower.startsWith("__macosx/") || lower.contains("/__macosx/")) return true
        val baseName = lower.substringAfterLast('/')
        return baseName in JUNK_FILENAMES
    }

    internal fun normalizeEntry(
        rawName: String,
        commonPrefix: String = "",
        hasDefaultUser: Boolean = false,
    ): RestoreTarget {
        val clean = rawName.replace('\\', '/').trimStart('/')
        if (clean.split('/').any { it == ".." }) {
            throw SecurityException("Zip Slip path traversal blocked: $rawName")
        }
        if (isIgnoredMetadataOrJunk(clean)) {
            return RestoreTarget.Ignore
        }

        val normalized = if (commonPrefix.isNotEmpty() && clean.startsWith(commonPrefix)) {
            clean.removePrefix(commonPrefix).trimStart('/')
        } else clean

        val firstSegmentLower = normalized.substringBefore('/').lowercase()
        val normalizedLower = normalized.lowercase()

        // Ignore engine and runtime bloat
        if (firstSegmentLower in IGNORED_ROOT_DIRECTORIES || normalizedLower in IGNORED_ROOT_FILES) {
            return RestoreTarget.Ignore
        }

        // Config file mapping
        if (normalized.equals("config.yaml", ignoreCase = true)) {
            return RestoreTarget.Config(EntryTier.CANONICAL_DATA)
        }
        if (normalized.equals("config/config.yaml", ignoreCase = true)) {
            return RestoreTarget.Config(EntryTier.LEGACY_FALLBACK)
        }

        // Secrets mapping
        if (normalized.equals("data/default-user/secrets.json", ignoreCase = true) ||
            normalized.equals("default-user/secrets.json", ignoreCase = true)) {
            return RestoreTarget.Data("default-user/secrets.json", EntryTier.MODERN_DEFAULT)
        }
        if (normalized.equals("data/secrets.json", ignoreCase = true)) {
            return RestoreTarget.Data("default-user/secrets.json", EntryTier.CANONICAL_DATA)
        }
        if (normalized.equals("secrets.json", ignoreCase = true) ||
            normalized.equals("config/secrets.json", ignoreCase = true)) {
            return RestoreTarget.Data("default-user/secrets.json", EntryTier.LEGACY_FALLBACK)
        }

        // Settings mapping
        if (normalized.equals("data/default-user/settings.json", ignoreCase = true) ||
            normalized.equals("default-user/settings.json", ignoreCase = true)) {
            return RestoreTarget.Data("default-user/settings.json", EntryTier.MODERN_DEFAULT)
        }
        if (normalized.equals("data/settings.json", ignoreCase = true)) {
            return RestoreTarget.Data("default-user/settings.json", EntryTier.CANONICAL_DATA)
        }
        if (normalized.equals("settings.json", ignoreCase = true) ||
            normalized.equals("public/settings.json", ignoreCase = true) ||
            normalized.equals("config/settings.json", ignoreCase = true)) {
            return RestoreTarget.Data("default-user/settings.json", EntryTier.LEGACY_FALLBACK)
        }

        // Stats file mapping (if present in public/stats.json)
        if (normalized.equals("public/stats.json", ignoreCase = true)) {
            return RestoreTarget.Data("default-user/stats.json", EntryTier.LEGACY_FALLBACK)
        }

        // Modern default-user entries
        val defaultUserPrefix = when {
            normalized.startsWith("data/default-user/", ignoreCase = true) -> "data/default-user/"
            normalized.startsWith("default-user/", ignoreCase = true) -> "default-user/"
            else -> null
        }
        if (defaultUserPrefix != null) {
            val subPath = normalized.substring(defaultUserPrefix.length).trimStart('/')
            if (subPath.isEmpty()) return RestoreTarget.Ignore
            val folderKey = subPath.substringBefore('/').lowercase()
            val canonical = CANONICAL_USER_FOLDERS[folderKey]
            val finalRel = if (canonical != null) {
                val sub = subPath.substringAfter('/', "")
                if (sub.isNotEmpty()) "default-user/$canonical/$sub" else "default-user/$canonical"
            } else {
                "default-user/$subPath"
            }
            return RestoreTarget.Data(finalRel, EntryTier.MODERN_DEFAULT)
        }

        // Entries starting with data/
        if (normalized.startsWith("data/", ignoreCase = true)) {
            val rel = normalized.substring("data/".length).trimStart('/')
            if (rel.isEmpty()) return RestoreTarget.Ignore
            val folderKey = rel.substringBefore('/').lowercase()
            val canonical = CANONICAL_USER_FOLDERS[folderKey]
            if (canonical != null && !hasDefaultUser) {
                val sub = rel.substringAfter('/', "")
                val targetPath = if (sub.isNotEmpty()) "default-user/$canonical/$sub" else "default-user/$canonical"
                return RestoreTarget.Data(targetPath, EntryTier.CANONICAL_DATA)
            }
            return RestoreTarget.Data(rel, EntryTier.CANONICAL_DATA)
        }

        // Root user folders (e.g. characters/..., chats/..., etc.)
        val folderKey = normalized.substringBefore('/').lowercase()
        val canonical = CANONICAL_USER_FOLDERS[folderKey]
        if (canonical != null) {
            val sub = normalized.substringAfter('/', "")
            val targetPath = if (sub.isNotEmpty()) "default-user/$canonical/$sub" else "default-user/$canonical"
            return RestoreTarget.Data(targetPath, EntryTier.LEGACY_FALLBACK)
        }

        // Legacy public/ folder entries (e.g. public/characters/...)
        if (normalized.startsWith("public/", ignoreCase = true)) {
            val rel = normalized.substring("public/".length).trimStart('/')
            val publicFolderKey = rel.substringBefore('/').lowercase()
            val publicCanonical = CANONICAL_USER_FOLDERS[publicFolderKey]
            if (publicCanonical != null) {
                val sub = rel.substringAfter('/', "")
                val targetPath = if (sub.isNotEmpty()) "default-user/$publicCanonical/$sub" else "default-user/$publicCanonical"
                return RestoreTarget.Data(targetPath, EntryTier.LEGACY_FALLBACK)
            }
            return RestoreTarget.Ignore
        }

        return RestoreTarget.Ignore
    }

    internal fun sanitizeConfigYaml(content: String): String {
        var updated = content.replace(Regex("""(?m)^(\s*backend\s*:\s*)["']?simple-git["']?"""), "$1builtin")
        updated = updated.replace(Regex("""(?m)^(\s*enableServerPlugins\s*:\s*)true\b"""), "$1false")
        return updated
    }

    private fun isDataEntry(name: String): Boolean {
        val target = normalizeEntry(name, "", true)
        return target is RestoreTarget.Data
    }

    private fun isConfigEntry(name: String): Boolean {
        val target = normalizeEntry(name, "", true)
        return target is RestoreTarget.Config
    }

    companion object {
        const val MANIFEST_NAME = "backup_manifest.json"

        val CANONICAL_USER_FOLDERS = mapOf(
            "characters" to "characters",
            "chats" to "chats",
            "worlds" to "worlds",
            "groups" to "groups",
            "group chats" to "group chats",
            "backgrounds" to "backgrounds",
            "user avatars" to "User Avatars",
            "user" to "user",
            "themes" to "themes",
            "movingui" to "movingUI",
            "instruct" to "instruct",
            "context" to "context",
            "quickreplies" to "QuickReplies",
            "assets" to "assets",
            "thumbnails" to "thumbnails",
            "vectors" to "vectors",
            "backups" to "backups",
            "sysprompt" to "sysprompt",
            "reasoning" to "reasoning",
            "novelai settings" to "NovelAI Settings",
            "koboldai settings" to "KoboldAI Settings",
            "openai settings" to "OpenAI Settings",
            "textgen settings" to "TextGen Settings",
        )

        val PROTECTED_ROOT_FOLDERS = setOf("data", "default-user", "config", "public")

        val IGNORED_ROOT_DIRECTORIES = setOf(
            "node_modules", "src", "scripts", "docker", ".git", ".github", ".cxx", ".gradle", "build", "dist", "docs"
        )

        val IGNORED_ROOT_FILES = setOf(
            "server.js", "package.json", "package-lock.json", "yarn.lock", "pnpm-lock.yaml",
            "start.bat", "start.sh", "update.bat", "update.sh", "dockerfile", "license", "readme.md"
        )

        val JUNK_FILENAMES = setOf(".ds_store", "thumbs.db", "desktop.ini")

        private fun resolveStVersion(context: Context): () -> String? = {
            runCatching { PayloadManager(context).readManifest().stVersion }.getOrNull()
        }

        internal fun resolveVersion(versionName: String?, versionCode: Long): String =
            versionName?.takeIf { it.isNotBlank() } ?: versionCode.toString()

        private fun resolveAppVersion(context: Context): String = runCatching {
            val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(pkg)
            resolveVersion(pkg.versionName, code)
        }.getOrDefault("1")
    }
}
