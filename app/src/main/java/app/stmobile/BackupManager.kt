package app.stmobile

import android.content.Context
import android.net.Uri
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import java.io.File

/**
 * Export/import of user state (PLAN.md §6.1 "Data"). Archives hold `data/` and
 * `config.yaml`; the replaceable `st/` tree is never included. Stop the server
 * before importing.
 */
class BackupManager(private val context: Context) {

    private val paths = AppPaths(context)

    fun export(uri: Uri, onProgress: (String) -> Unit): Result<Unit> = runCatching {
        onProgress("Writing backup…")
        val out = context.contentResolver.openOutputStream(uri)
            ?: throw IllegalStateException("Cannot open the chosen file for writing")
        out.use { os ->
            ZipArchiveOutputStream(os).use { zip ->
                if (paths.dataDir.exists()) addTree(zip, paths.dataDir, "data")
                if (paths.configFile.exists()) addFile(zip, paths.configFile, "config.yaml")
            }
        }
    }

    fun import(uri: Uri, onProgress: (String) -> Unit): Result<Unit> = runCatching {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Cannot open the chosen file")
        var count = 0
        input.use { ins ->
            ZipArchiveInputStream(ins).use { zip ->
                while (true) {
                    val entry: ZipArchiveEntry = zip.nextEntry ?: break
                    val target = resolve(entry.name) ?: continue
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { out -> zip.copyTo(out) }
                        if (++count % 500 == 0) onProgress("Restoring… $count files")
                    }
                }
            }
        }
    }

    private fun addTree(zip: ZipArchiveOutputStream, root: File, prefix: String) {
        val base = root.canonicalPath
        root.walkTopDown().forEach { file ->
            if (file.isFile) {
                val rel = file.canonicalPath.removePrefix(base).trimStart(File.separatorChar)
                addFile(zip, file, "$prefix/$rel")
            }
        }
    }

    private fun addFile(zip: ZipArchiveOutputStream, file: File, name: String) {
        val entry = ZipArchiveEntry(name)
        entry.time = file.lastModified()
        zip.putArchiveEntry(entry)
        file.inputStream().use { it.copyTo(zip) }
        zip.closeArchiveEntry()
    }

    /** Maps archive names to on-device targets, rejecting anything else. */
    private fun resolve(name: String): File? {
        return when {
            name.startsWith("data/") -> safeChild(paths.dataDir, name.removePrefix("data/"))
            name == "config.yaml" -> paths.configFile
            else -> null
        }
    }

    private fun safeChild(root: File, rel: String): File? {
        val target = File(root, rel)
        val rootPath = root.canonicalPath + File.separator
        return if (target.canonicalPath.startsWith(rootPath)) target else null
    }
}