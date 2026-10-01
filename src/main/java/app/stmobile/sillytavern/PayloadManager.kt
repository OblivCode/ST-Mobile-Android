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

    /** Ensures the ST tree is present and up to date; returns the runtime layout. */
    fun ensureExtracted(onProgress: (String) -> Unit): Layout {
        val manifest = readManifest()
        val installed = prefs.getString(KEY_INSTALLED, null)
        var updated = false

        if (installed != manifest.payloadVersion || !paths.stEntry.exists()) {
            onProgress("Unpacking SillyTavern (first run)…")
            extractBundle(manifest.bundle, manifest.bundleSha256, onProgress)
            prefs.edit().putString(KEY_INSTALLED, manifest.payloadVersion).apply()
            updated = true
        }

        paths.configDir.mkdirs()
        paths.dataDir.mkdirs()
        paths.logsDir.mkdirs()
        paths.nodeTmpDir.mkdirs()

        if (!paths.configFile.exists()) {
            onProgress("Seeding default config…")
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
            payloadUpdated = updated
        )
    }

    private fun extractBundle(assetName: String, expectedSha256: String?, onProgress: (String) -> Unit) {
        val tmpNew = File(paths.tmpDir, "st_new")
        if (tmpNew.exists()) tmpNew.deleteRecursively()
        tmpNew.mkdirs()

        // Digest the raw asset bytes so the bundle can be verified against the
        // SHA-256 recorded in payload_manifest.json.
        val digest = MessageDigest.getInstance("SHA-256")
        context.assets.open(assetName).use { raw ->
            val digestIn = DigestInputStream(raw, digest)
            val buffered = BufferedInputStream(digestIn, 1 shl 16)
            val source = if (assetName.endsWith(".gz")) GZIPInputStream(buffered, 1 shl 16) else buffered
            source.use { input ->
                TarArchiveInputStream(input).use { tar ->
                    var files = 0
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
                            if (++files % 2000 == 0) onProgress("Unpacking… $files files")
                        }
                    }
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

    companion object {
        private const val KEY_INSTALLED = "installed_payload_version"
    }
}
