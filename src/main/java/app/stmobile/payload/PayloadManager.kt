package app.stmobile.payload

import android.content.Context
import android.content.SharedPreferences
import app.stmobile.AppPaths
import app.stmobile.logging.AppLog
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.zip.GZIPInputStream

/**
 * Owns the SillyTavern payload lifecycle: pluggable source extraction, versioning,
 * SHA-256 integrity validation, config seeding, and atomic staging/rollback safety.
 */
class PayloadManager(
    private val paths: AppPaths,
    private val prefs: SharedPreferences,
    private val assetOpener: (String) -> InputStream,
    private val isSlim: Boolean = false,
    private val defaultPayloadUrl: String = "",
) {

    constructor(context: Context) : this(
        paths = AppPaths(context),
        prefs = context.getSharedPreferences("payload", Context.MODE_PRIVATE),
        assetOpener = { name -> context.assets.open(name) },
        isSlim = app.stmobile.BuildConfig.FLAVOR == "slim",
        defaultPayloadUrl = app.stmobile.BuildConfig.DEFAULT_PAYLOAD_URL,
    )

    data class Manifest(
        val payloadVersion: String,
        val stVersion: String?,
        val nodeVersion: String?,
        val bundle: String,
        val bundleSha256: String?,
        val tarSha256: String? = null,
        val tarBundle: String? = null,
        val stCommit: String? = null,
        val nodeRuntime: String? = null,
    )

    data class InstalledInfo(
        val isInstalled: Boolean,
        val payloadVersion: String? = null,
        val stVersion: String? = null,
        val stCommit: String? = null,
        val nodeVersion: String? = null,
        val sourceDescription: String? = null,
    )

    data class Layout(
        val launcher: File,
        val stDir: File,
        val stEntry: File,
        val configFile: File,
        val dataDir: File,
        val logsDir: File,
        val nodeTmpDir: File,
        val nativeLibDir: String,
        val payloadUpdated: Boolean,
        val generationHook: File? = null,
    )

    data class ExtractProgress(
        val stage: String,
        val filesProcessed: Int = 0,
        val totalEstimate: Int = 11000,
        val currentFile: String? = null,
        val bytesProcessed: Long = 0L,
        val totalBytes: Long = 0L,
        val speedBytesPerSec: Long = 0L,
        val isCompleted: Boolean = false,
        val isError: Boolean = false,
        val errorMessage: String? = null,
    )

    fun readManifest(): Manifest {
        val text = assetOpener("payload_manifest.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val json = JSONObject(text)
        return Manifest(
            payloadVersion = json.getString("payload_version"),
            stVersion = json.optString("st_version").ifBlank { null },
            nodeVersion = json.optString("node_version").ifBlank { null },
            bundle = json.getString("bundle"),
            bundleSha256 = json.optString("bundle_sha256").ifBlank { null },
            tarSha256 = json.optString("tar_sha256").ifBlank { null },
            tarBundle = json.optString("tar_bundle").ifBlank { null },
            stCommit = json.optString("st_commit").ifBlank { null },
            nodeRuntime = json.optString("node_runtime").ifBlank { null },
        )
    }

    /**
     * Inspects currently installed payload state and metadata.
     */
    fun getInstalledInfo(): InstalledInfo {
        if (!isInstalled()) {
            return InstalledInfo(isInstalled = false)
        }
        val payloadVersion = prefs.getString(KEY_INSTALLED, null)
        var stVersion = prefs.getString(KEY_ST_VERSION, null)
        var stCommit = prefs.getString(KEY_ST_COMMIT, null)
        val sourceDesc = prefs.getString(KEY_SOURCE_DESC, null)

        val manifest = runCatching { readManifest() }.getOrNull()
        if (stVersion == null || stCommit == null) {
            if (payloadVersion == manifest?.payloadVersion) {
                stVersion = stVersion ?: manifest?.stVersion
                stCommit = stCommit ?: manifest?.stCommit
            } else {
                val pkgFile = File(paths.stDir, "package.json")
                if (pkgFile.exists()) {
                    runCatching {
                        stVersion = stVersion ?: JSONObject(pkgFile.readText()).optString("version").ifBlank { null }
                    }
                }
            }
        }

        return InstalledInfo(
            isInstalled = true,
            payloadVersion = payloadVersion,
            stVersion = stVersion ?: manifest?.stVersion ?: "1.19.0",
            stCommit = stCommit ?: manifest?.stCommit,
            nodeVersion = manifest?.nodeVersion ?: "24.20.0 (libnode.so)",
            sourceDescription = sourceDesc,
        )
    }

    /** Returns true if SillyTavern payload has already been extracted and recorded. */
    fun isInstalled(): Boolean = paths.stEntry.exists() && prefs.getString(KEY_INSTALLED, null) != null

    /** Returns true if first-run extraction or payload update is required. */
    fun isExtractionNeeded(): Boolean {
        if (!isInstalled()) return true
        val manifest = runCatching { readManifest() }.getOrNull() ?: return false
        val installed = prefs.getString(KEY_INSTALLED, null)
        return installed != manifest.payloadVersion
    }

    /** Returns true if asset with given name exists and can be opened. */
    fun hasAssetBundle(assetName: String = "st_bundle.tar"): Boolean = runCatching {
        assetOpener(assetName).use { }
        true
    }.getOrDefault(false)

    /** Returns default payload source tailored to the build flavor (remote for slim, bundled asset for full). */
    fun defaultPayloadSource(): PayloadSource {
        val manifest = runCatching { readManifest() }.getOrNull()
        val targetAssetName = manifest?.tarBundle ?: "st_bundle.tar"
        return if (isSlim || !hasAssetBundle(targetAssetName)) {
            val url = defaultPayloadUrl.ifBlank {
                manifest?.let { "https://github.com/OblivCode/ST-Mobile-Android/releases/latest/download/${it.bundle}" }
                    ?: "https://github.com/OblivCode/ST-Mobile-Android/releases/latest/download/st_bundle.tar.gz"
            }
            val version = manifest?.payloadVersion ?: "remote-latest"
            RemotePayloadSource(
                url = url,
                versionStamp = version,
                expectedSha256 = manifest?.bundleSha256,
                sourceDescription = "GitHub Releases ($version)",
            )
        } else {
            AssetPayloadSource(
                assetName = targetAssetName,
                manifestProvider = { readManifest() },
                assetOpener = assetOpener,
            )
        }
    }

    /**
     * Fast-path: returns the runtime layout if already extracted.
     * Ensures all required scratch and storage directories exist.
     */
    fun getExistingLayout(): Layout {
        paths.configDir.mkdirs()
        paths.dataDir.mkdirs()
        paths.logsDir.mkdirs()
        paths.nodeTmpDir.mkdirs()

        if (!isInstalled()) {
            throw IllegalStateException("SillyTavern payload not extracted. Run setup first.")
        }

        if (!paths.configFile.exists()) {
            assetOpener("default_config.yaml").use { input ->
                paths.configFile.outputStream().use { out -> input.copyTo(out) }
            }
        }

        ensureSymlink(paths.stConfigLink, paths.configFile)
        ensureSymlink(paths.stDataLink, paths.dataDir)
        val hookFile = ensureGenerationHook()

        return Layout(
            launcher = paths.launcher,
            stDir = paths.stDir,
            stEntry = paths.stEntry,
            configFile = paths.configFile,
            dataDir = paths.dataDir,
            logsDir = paths.logsDir,
            nodeTmpDir = paths.nodeTmpDir,
            nativeLibDir = paths.nativeLibDir,
            payloadUpdated = false,
            generationHook = hookFile,
        )
    }

    /**
     * Extracts and validates the payload from [source] with granular progress reporting,
     * SHA-256 verification, and atomic swap promotion with rollback safety.
     */
    fun extract(
        source: PayloadSource = defaultPayloadSource(),
        isCancelled: () -> Boolean = { false },
        onProgress: (ExtractProgress) -> Unit,
    ): Layout {
        AppLog.i("PayloadManager", "Starting payload extraction (source=${source.sourceDescription}, versionStamp=${source.versionStamp})")
        onProgress(ExtractProgress(stage = "Preparing extraction…", totalEstimate = 11000))

        extractFromSource(source, isCancelled, onProgress)

        paths.configDir.mkdirs()
        paths.dataDir.mkdirs()
        paths.logsDir.mkdirs()
        paths.nodeTmpDir.mkdirs()

        if (!paths.configFile.exists()) {
            onProgress(ExtractProgress(stage = "Seeding default config…", filesProcessed = 11000))
            assetOpener("default_config.yaml").use { input ->
                paths.configFile.outputStream().use { out -> input.copyTo(out) }
            }
        }

        ensureSymlink(paths.stConfigLink, paths.configFile)
        ensureSymlink(paths.stDataLink, paths.dataDir)

        val manifest = runCatching { readManifest() }.getOrNull()
        val pkgJsonVersion = runCatching {
            val pkgFile = File(paths.stDir, "package.json")
            if (pkgFile.exists()) JSONObject(pkgFile.readText()).optString("version").ifBlank { null } else null
        }.getOrNull()

        val effectiveStVersion = pkgJsonVersion ?: manifest?.stVersion
        val effectiveCommit = if (source is AssetPayloadSource || source.versionStamp == manifest?.payloadVersion) {
            manifest?.stCommit
        } else null

        prefs.edit()
            .putString(KEY_INSTALLED, source.versionStamp)
            .putString(KEY_ST_VERSION, effectiveStVersion)
            .putString(KEY_ST_COMMIT, effectiveCommit)
            .putString(KEY_SOURCE_DESC, source.sourceDescription)
            .apply()

        onProgress(ExtractProgress(stage = "Setup complete", filesProcessed = 11000, isCompleted = true))

        val hookFile = ensureGenerationHook()

        AppLog.i("PayloadManager", "Payload extraction completed successfully (stVersion=$effectiveStVersion, commit=$effectiveCommit)")
        return Layout(
            launcher = paths.launcher,
            stDir = paths.stDir,
            stEntry = paths.stEntry,
            configFile = paths.configFile,
            dataDir = paths.dataDir,
            logsDir = paths.logsDir,
            nodeTmpDir = paths.nodeTmpDir,
            nativeLibDir = paths.nativeLibDir,
            payloadUpdated = true,
            generationHook = hookFile,
        )
    }

    fun ensureGenerationHook(): File? {
        return try {
            paths.scriptsDir.mkdirs()
            assetOpener("generation_hook.cjs").use { input ->
                paths.generationHookFile.outputStream().use { out -> input.copyTo(out) }
            }
            paths.generationHookFile
        } catch (_: Exception) {
            if (paths.generationHookFile.exists()) paths.generationHookFile else null
        }
    }

    private fun extractFromSource(
        source: PayloadSource,
        isCancelled: () -> Boolean,
        onProgress: (ExtractProgress) -> Unit,
    ) {
        val tmpNew = File(paths.tmpDir, "st_new")
        if (tmpNew.exists()) safeDeleteRecursively(tmpNew)
        tmpNew.mkdirs()

        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var lastReportTime = System.currentTimeMillis()
            var currentBytes = 0L
            var totalBytes = -1L
            var currentSpeed = 0L

            val rawStream = source.openStream(isCancelled) { bytesRead, total, speed ->
                currentBytes = bytesRead
                totalBytes = total
                currentSpeed = speed
                val now = System.currentTimeMillis()
                if (now - lastReportTime >= 100 || (total > 0 && bytesRead >= total)) {
                    lastReportTime = now
                    onProgress(
                        ExtractProgress(
                            stage = "Downloading SillyTavern…",
                            bytesProcessed = currentBytes,
                            totalBytes = totalBytes,
                            speedBytesPerSec = currentSpeed,
                        )
                    )
                }
            }

            if (source is RemotePayloadSource && source.expectedSha256.isNullOrBlank()) {
                rawStream.close()
                throw SecurityException("Remote payload source requires a valid non-empty SHA-256 checksum")
            }

            rawStream.use { raw ->
                val digestIn = DigestInputStream(raw, digest)
                val buffered = BufferedInputStream(digestIn, 1 shl 16)

                // Peek up to 8 bytes to detect archive format safely without displacement
                buffered.mark(8)
                val headerBytes = ByteArray(8)
                val headerRead = buffered.read(headerBytes, 0, 8)
                buffered.reset()

                val isGzip = headerRead >= 2 && headerBytes[0] == 0x1F.toByte() && headerBytes[1] == 0x8B.toByte()
                val isZip = headerRead >= 4 && headerBytes[0] == 0x50.toByte() && headerBytes[1] == 0x4B.toByte() &&
                    ((headerBytes[2] == 0x03.toByte() && headerBytes[3] == 0x04.toByte()) ||
                        (headerBytes[2] == 0x05.toByte() && headerBytes[3] == 0x06.toByte()))

                var files = 0

                if (isZip) {
                    ZipArchiveInputStream(NonClosingInputStream(buffered)).use { zip ->
                        while (true) {
                            if (isCancelled()) throw CancellationException("Payload extraction cancelled")
                            val entry = zip.nextEntry as? ZipArchiveEntry ?: break
                            val name = entry.name.replace('\\', '/').removePrefix("st/").removePrefix("./").trimStart('/')
                            if (name.isBlank()) continue
                            val target = safeResolve(tmpNew, name)
                            if (entry.isDirectory) {
                                target.mkdirs()
                            } else {
                                target.parentFile?.mkdirs()
                                FileOutputStream(target).use { out -> zip.copyTo(out) }
                                if ((entry.unixMode and 0b001_000_000) != 0) target.setExecutable(true, true)
                                files++
                                val now = System.currentTimeMillis()
                                if (files % 100 == 0 || now - lastReportTime >= 100) {
                                    lastReportTime = now
                                    onProgress(
                                        ExtractProgress(
                                            stage = "Unpacking SillyTavern…",
                                            filesProcessed = files,
                                            totalEstimate = 11000,
                                            currentFile = name,
                                            bytesProcessed = currentBytes,
                                            totalBytes = totalBytes,
                                            speedBytesPerSec = currentSpeed,
                                        )
                                    )
                                }
                            }
                        }
                    }
                } else {
                    val rawTarStream = NonClosingInputStream(buffered)
                    val decompressed = if (isGzip) GZIPInputStream(rawTarStream, 1 shl 16) else rawTarStream
                    TarArchiveInputStream(decompressed).use { tar ->
                        while (true) {
                            if (isCancelled()) throw CancellationException("Payload extraction cancelled")
                            val entry: TarArchiveEntry = tar.nextEntry ?: break
                            val name = entry.name.replace('\\', '/').removePrefix("st/").removePrefix("./").trimStart('/')
                            if (name.isBlank()) continue
                            val target = safeResolve(tmpNew, name)
                            if (entry.isDirectory) {
                                target.mkdirs()
                            } else {
                                target.parentFile?.mkdirs()
                                FileOutputStream(target).use { out -> tar.copyTo(out) }
                                if ((entry.mode and 0b001_000_000) != 0) target.setExecutable(true, true)
                                files++
                                val now = System.currentTimeMillis()
                                if (files % 100 == 0 || now - lastReportTime >= 100) {
                                    lastReportTime = now
                                    onProgress(
                                        ExtractProgress(
                                            stage = "Unpacking SillyTavern…",
                                            filesProcessed = files,
                                            totalEstimate = 11000,
                                            currentFile = name,
                                            bytesProcessed = currentBytes,
                                            totalBytes = totalBytes,
                                            speedBytesPerSec = currentSpeed,
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                onProgress(
                    ExtractProgress(
                        stage = "Unpack complete",
                        filesProcessed = files,
                        totalEstimate = 11000,
                        bytesProcessed = currentBytes,
                        totalBytes = totalBytes,
                        speedBytesPerSec = currentSpeed,
                    )
                )

                // Explicitly drain any remaining bytes (central directory for ZIP, padding for TAR)
                // to guarantee the SHA-256 digest covers 100% of the raw stream.
                val drainBuffer = ByteArray(8192)
                while (buffered.read(drainBuffer) != -1) {
                    if (isCancelled()) throw CancellationException("Payload extraction cancelled")
                }
            }

            // Verify checksum
            val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
            if (source.expectedSha256 != null && !actualSha.equals(source.expectedSha256, ignoreCase = true)) {
                throw IllegalStateException(
                    "SillyTavern payload checksum mismatch (expected ${source.expectedSha256}, got $actualSha)"
                )
            }
            AppLog.i("PayloadManager", "Payload checksum verified ($actualSha)")

            // Normalize payload structure (promotes single root directory like SillyTavern-1.19.0/ if present)
            normalizePayloadTree(tmpNew)

            // Atomic promotion: swap st/ safely using st_old, restoring if swap fails midway
            promoteStagingTree(tmpNew)
        } catch (t: Throwable) {
            AppLog.e("PayloadManager", "Payload extraction failed", t)
            safeDeleteRecursively(tmpNew)
            throw t
        }
    }

    /**
     * Promotes contents of single root folder if server.js is nested (e.g. SillyTavern-1.19.0/server.js).
     * Asserts server.js exists at the root of stagingDir.
     */
    private fun normalizePayloadTree(stagingDir: File) {
        if (File(stagingDir, "server.js").exists()) return

        val subdirs = stagingDir.listFiles { f -> f.isDirectory } ?: emptyArray()
        val candidate = subdirs.firstOrNull { File(it, "server.js").exists() }
        if (candidate != null) {
            val children = candidate.listFiles() ?: emptyArray()
            for (child in children) {
                val dest = File(stagingDir, child.name)
                if (!child.renameTo(dest)) {
                    copyRecursivelyNoFollow(child, dest)
                    safeDeleteRecursively(child)
                }
            }
            safeDeleteRecursively(candidate)
        }

        if (!File(stagingDir, "server.js").exists()) {
            throw IllegalStateException("Invalid SillyTavern payload: server.js not found in archive")
        }
    }

    /**
     * Atomically promotes [stagingDir] to `paths.stDir`.
     * If an existing `st/` tree exists, moves it to `st_old`.
     * In case promotion fails, rolls back `st_old` to `st/`.
     */
    private fun promoteStagingTree(stagingDir: File) {
        val stDir = paths.stDir
        AppLog.i("PayloadManager", "Promoting staging tree to $stDir")
        val old = File(paths.tmpDir, "st_old")
        if (old.exists()) safeDeleteRecursively(old)

        var movedOld = false
        if (stDir.exists()) {
            if (!stDir.renameTo(old)) {
                throw IOException("Failed to move existing payload to temporary backup")
            }
            movedOld = true
        }

        var promoted = false
        try {
            if (stagingDir.renameTo(stDir)) {
                promoted = true
            } else {
                copyRecursivelyNoFollow(stagingDir, stDir)
                safeDeleteRecursively(stagingDir)
                promoted = true
            }
        } finally {
            if (!promoted && movedOld && old.exists()) {
                // Rollback: restore old stDir
                safeDeleteRecursively(stDir)
                if (!old.renameTo(stDir)) {
                    copyRecursivelyNoFollow(old, stDir)
                    safeDeleteRecursively(old)
                }
            }
        }

        if (old.exists()) safeDeleteRecursively(old)
    }

    private fun safeResolve(root: File, name: String): File {
        val normalized = name.replace('\\', '/').trim()
        if (normalized.isEmpty() || normalized == "." || normalized.split('/').any { it == ".." }) {
            throw IllegalStateException("Unsafe archive entry: $name")
        }
        val target = File(root, normalized).canonicalFile
        val rootPath = root.canonicalPath.trimEnd(File.separatorChar) + File.separator
        if (!target.path.startsWith(rootPath) || target.path == root.canonicalPath) {
            throw IllegalStateException("Unsafe archive entry: $name")
        }
        return target
    }

    private fun ensureSymlink(link: File, target: File) {
        try {
            if (Files.isSymbolicLink(link.toPath())) {
                if (Files.readSymbolicLink(link.toPath()) == target.toPath()) return
                link.delete()
            } else if (link.exists()) {
                if (!target.exists()) link.renameTo(target) else safeDeleteRecursively(link)
            }
            target.parentFile?.mkdirs()
            Files.createSymbolicLink(link.toPath(), target.toPath())
        } catch (_: Exception) {
            // Symlinks unsupported: process flags already point at the real paths.
        }
    }

    /**
     * Resets the extracted SillyTavern payload tree and clears the recorded
     * version stamp, forcing setup extraction on next launch/run.
     * Preserves user data and configuration outside of stDir.
     */
    fun resetPayload(): Boolean {
        prefs.edit()
            .remove(KEY_INSTALLED)
            .remove(KEY_ST_VERSION)
            .remove(KEY_ST_COMMIT)
            .remove(KEY_SOURCE_DESC)
            .commit()
        return safeDeleteRecursively(paths.stDir)
    }

    private class NonClosingInputStream(delegate: InputStream) : java.io.FilterInputStream(delegate) {
        override fun close() {
            // Keep delegate stream open for subsequent EOF draining
        }
    }

    companion object {
        const val KEY_INSTALLED = "installed_payload_version"
        const val KEY_ST_VERSION = "installed_st_version"
        const val KEY_ST_COMMIT = "installed_st_commit"
        const val KEY_SOURCE_DESC = "installed_source_desc"

        /**
         * Deletes a directory tree without traversing into symbolic links, preventing
         * accidental data loss of targets linked under st/data or st/config.
         */
        fun safeDeleteRecursively(root: File): Boolean {
            if (!root.exists() && !Files.isSymbolicLink(root.toPath())) return true
            return try {
                Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        Files.deleteIfExists(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException?): FileVisitResult {
                        Files.deleteIfExists(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        Files.deleteIfExists(dir)
                        return FileVisitResult.CONTINUE
                    }
                })
                true
            } catch (_: Exception) {
                false
            }
        }

        /**
         * Recursively copies [source] to [target] preserving symbolic links without
         * dereferencing or traversing into symlinked directories.
         */
        fun copyRecursivelyNoFollow(source: File, target: File) {
            val sourcePath = source.toPath()
            val targetPath = target.toPath()
            if (!Files.exists(sourcePath, LinkOption.NOFOLLOW_LINKS)) {
                return
            }

            Files.walkFileTree(sourcePath, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val relative = sourcePath.relativize(dir)
                    val dest = targetPath.resolve(relative)
                    if (Files.isSymbolicLink(dest) || (Files.exists(dest, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(dest, LinkOption.NOFOLLOW_LINKS))) {
                        Files.deleteIfExists(dest)
                    }
                    if (!Files.exists(dest, LinkOption.NOFOLLOW_LINKS)) {
                        Files.createDirectories(dest)
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val relative = sourcePath.relativize(file)
                    val dest = targetPath.resolve(relative)
                    dest.parent?.let { parent ->
                        if (!Files.exists(parent)) {
                            Files.createDirectories(parent)
                        }
                    }
                    if (Files.exists(dest, LinkOption.NOFOLLOW_LINKS)) {
                        if (Files.isDirectory(dest, LinkOption.NOFOLLOW_LINKS)) {
                            safeDeleteRecursively(dest.toFile())
                        } else {
                            Files.deleteIfExists(dest)
                        }
                    }
                    if (Files.isSymbolicLink(file)) {
                        val symlinkTarget = Files.readSymbolicLink(file)
                        Files.createSymbolicLink(dest, symlinkTarget)
                    } else {
                        try {
                            Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
                        } catch (_: UnsupportedOperationException) {
                            Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING)
                        }
                    }
                    return FileVisitResult.CONTINUE
                }
            })
        }

        /**
         * Alias for [safeDeleteRecursively], providing consistent non-dereferencing deletion.
         */
        fun deleteRecursivelyNoFollow(file: File): Boolean = safeDeleteRecursively(file)
    }
}
