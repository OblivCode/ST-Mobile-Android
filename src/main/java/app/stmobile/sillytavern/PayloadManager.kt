package app.stmobile.sillytavern

import android.content.Context
import app.stmobile.AppPaths
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Owns the bundled SillyTavern payload: first-run extraction, payload
 * versioning, config seeding, and the config/data symlinks.
 */
class PayloadManager(private val context: Context) {

    data class Manifest(
        val payloadVersion: String,
        val stVersion: String?,
        val nodeVersion: String?,
        val bundle: String,
        val bundleSha256: String?
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
        val payloadUpdated: Boolean
    )

    data class ExtractProgress(
        val stage: String,
        val filesProcessed: Int = 0,
        val totalEstimate: Int = 11000,
        val currentFile: String? = null,
        val isCompleted: Boolean = false,
        val isError: Boolean = false,
        val errorMessage: String? = null
    )

    private val prefs = context.getSharedPreferences("payload", Context.MODE_PRIVATE)
    private val paths = AppPaths(context)

    fun readManifest(): Manifest {
        val text = context.assets.open("payload_manifest.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val json = JSONObject(text)
        return Manifest(
            payloadVersion = json.getString("payload_version"),
            stVersion = json.optString("st_version").ifBlank { null },
            nodeVersion = json.optString("node_version").ifBlank { null },
            bundle = json.getString("bundle"),
            bundleSha256 = json.optString("bundle_sha256").ifBlank { null }
        )
    }

    /** Returns true if first-run extraction or payload update is required. */
    fun isExtractionNeeded(): Boolean {
        val manifest = readManifest()
        val installed = prefs.getString(KEY_INSTALLED, null)
        return installed != manifest.payloadVersion || !paths.stEntry.exists()
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

        if (isExtractionNeeded()) {
            throw IllegalStateException("SillyTavern payload not extracted. Run setup first.")
        }

        if (!paths.configFile.exists()) {
            context.assets.open("default_config.yaml").use { input ->
                paths.configFile.outputStream().use { out -> input.copyTo(out) }
            }
        }

        ensureSymlink(paths.stConfigLink, paths.configFile)
        ensureSymlink(paths.stDataLink, paths.dataDir)

        return Layout(
            launcher = paths.launcher,
            stDir = paths.stDir,
            stEntry = paths.stEntry,
            configFile = paths.configFile,
            dataDir = paths.dataDir,
            logsDir = paths.logsDir,
            nodeTmpDir = paths.nodeTmpDir,
            nativeLibDir = paths.nativeLibDir,
            payloadUpdated = false
        )
    }

    /**
     * Extracts and validates the bundled payload with throttled progress reporting.
     */
    fun extract(onProgress: (ExtractProgress) -> Unit): Layout {
        val manifest = readManifest()
        onProgress(ExtractProgress(stage = "Preparing extraction…", totalEstimate = 11000))

        extractBundle(manifest.bundle, manifest.bundleSha256) { files, file ->
            onProgress(
                ExtractProgress(
                    stage = "Unpacking SillyTavern…",
                    filesProcessed = files,
                    totalEstimate = 11000,
                    currentFile = file
                )
            )
        }

        paths.configDir.mkdirs()
        paths.dataDir.mkdirs()
        paths.logsDir.mkdirs()
        paths.nodeTmpDir.mkdirs()

        if (!paths.configFile.exists()) {
            onProgress(ExtractProgress(stage = "Seeding default config…", filesProcessed = 11000))
            context.assets.open("default_config.yaml").use { input ->
                paths.configFile.outputStream().use { out -> input.copyTo(out) }
            }
        }

        ensureSymlink(paths.stConfigLink, paths.configFile)
        ensureSymlink(paths.stDataLink, paths.dataDir)

        prefs.edit().putString(KEY_INSTALLED, manifest.payloadVersion).apply()
        onProgress(ExtractProgress(stage = "Setup complete", filesProcessed = 11000, isCompleted = true))

        return Layout(
            launcher = paths.launcher,
            stDir = paths.stDir,
            stEntry = paths.stEntry,
            configFile = paths.configFile,
            dataDir = paths.dataDir,
            logsDir = paths.logsDir,
            nodeTmpDir = paths.nodeTmpDir,
            nativeLibDir = paths.nativeLibDir,
            payloadUpdated = true
        )
    }

    private fun extractBundle(
        assetName: String,
        expectedSha256: String?,
        onFileProgress: (Int, String) -> Unit
    ) {
        val tmpNew = File(paths.tmpDir, "st_new")
        if (tmpNew.exists()) tmpNew.deleteRecursively()
        tmpNew.mkdirs()

        // Digest raw asset bytes to verify SHA-256 recorded in manifest
        val digest = MessageDigest.getInstance("SHA-256")
        context.assets.open(assetName).use { raw ->
            val digestIn = DigestInputStream(raw, digest)
            val buffered = BufferedInputStream(digestIn, 1 shl 16)
            val source = if (assetName.endsWith(".gz")) GZIPInputStream(buffered, 1 shl 16) else buffered
            source.use { input ->
                TarArchiveInputStream(input).use { tar ->
                    var files = 0
                    var lastReportTime = System.currentTimeMillis()
                    while (true) {
                        val entry: TarArchiveEntry = tar.nextEntry ?: break
                        val name = entry.name.removePrefix("st/")
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
                            // Throttle progress to at most once per 100 files or every 100ms
                            if (files % 100 == 0 || now - lastReportTime >= 100) {
                                lastReportTime = now
                                onFileProgress(files, name)
                            }
                        }
                    }
                    onFileProgress(files, "Unpack complete")
                }
            }
        }

        val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
        if (expectedSha256 != null && !actualSha.equals(expectedSha256, ignoreCase = true)) {
            tmpNew.deleteRecursively()
            throw IllegalStateException(
                "SillyTavern payload checksum mismatch (expected $expectedSha256, got $actualSha)"
            )
        }

        // Replace st/ via a temp swap so a failure never leaves a half tree.
        val stDir = paths.stDir
        val old = File(paths.tmpDir, "st_old")
        if (old.exists()) old.deleteRecursively()
        if (stDir.exists() && !stDir.renameTo(old)) stDir.deleteRecursively()
        if (!tmpNew.renameTo(stDir)) {
            tmpNew.copyRecursively(stDir, overwrite = true)
            tmpNew.deleteRecursively()
        }
        if (old.exists()) old.deleteRecursively()
    }

    private fun safeResolve(root: File, name: String): File {
        val target = File(root, name)
        val rootPath = root.canonicalPath + File.separator
        if (!target.canonicalPath.startsWith(rootPath)) {
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
                if (!target.exists()) link.renameTo(target) else link.deleteRecursively()
            }
            target.parentFile?.mkdirs()
            Files.createSymbolicLink(link.toPath(), target.toPath())
        } catch (_: Exception) {
            // Symlinks unsupported: the process flags already point at the real paths.
        }
    }

    /**
     * Resets the extracted SillyTavern payload tree and clears the recorded
     * version stamp, forcing setup extraction on next launch/run.
     * Preserves user data and configuration outside of stDir.
     */
    fun resetPayload(): Boolean {
        prefs.edit().remove(KEY_INSTALLED).commit()
        return paths.stDir.deleteRecursively()
    }

    companion object {
        private const val KEY_INSTALLED = "installed_payload_version"
    }
}
