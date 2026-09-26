package app.stmobile

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Launches and supervises the SillyTavern Node process (PLAN.md §3.3).
 * Owned by [NodeService]; the process is a child of this app process.
 */
class NodeController(private val context: Context) {

    @Volatile
    private var process: Process? = null

    fun isRunning(): Boolean = process?.isAlive == true

    fun pid(): Long? {
        val current = process?.takeIf { it.isAlive } ?: return null
        return try {
            // Process.pid() is not guaranteed across Android API levels; reflect.
            val method = current.javaClass.getMethod("pid")
            (method.invoke(current) as? Long)
        } catch (_: Exception) {
            null
        }
    }

    /** Starts the launcher with the ST server and returns once the process is spawned. */
    fun start(layout: PayloadManager.Layout, port: Int): Process {
        val paths = AppPaths(context)
        val stdout = File(layout.logsDir, "node_stdout.log")
        val stderr = File(layout.logsDir, "node_stderr.log")
        rotateIfNeeded(stdout)
        rotateIfNeeded(stderr)

        val settings = AppSettings(context)
        val nodeLib = resolveNodeLib(paths, layout)
        val pb = ProcessBuilder(
            layout.launcher.absolutePath,
            layout.stEntry.absolutePath,
            "--configPath", layout.configFile.absolutePath,
            "--dataRoot", layout.dataDir.absolutePath,
            "--browserLaunchEnabled", "false",
            "--port", port.toString()
        )
        pb.directory(layout.stDir)
        pb.environment().apply {
            put("LD_LIBRARY_PATH", layout.nativeLibDir)
            put("ST_NODE_LIB", nodeLib.absolutePath)
            put("HOME", paths.filesDir.absolutePath)
            put("TMPDIR", layout.nodeTmpDir.absolutePath)
            put("TMP", layout.nodeTmpDir.absolutePath)
            put("TEMP", layout.nodeTmpDir.absolutePath)
            put("NODE_ENV", "production")
            put("SILLYTAVERN_PORT", port.toString())
            put("ST_ANDROID", "1")
            if (settings.basicAuthEnabled) {
                put("SILLYTAVERN_BASICAUTHMODE", "true")
                put("SILLYTAVERN_BASICAUTHUSER_USERNAME", settings.basicAuthUsername)
                put("SILLYTAVERN_BASICAUTHUSER_PASSWORD", settings.basicAuthPassword)
            }
        }
        pb.redirectOutput(stdout)
        pb.redirectError(stderr)

        val started = pb.start()
        process = started
        return started
    }

    fun stop() {
        val current = process ?: return
        try {
            current.destroy()
            if (!current.waitFor(3, TimeUnit.SECONDS)) current.destroyForcibly()
        } catch (_: Exception) {
            current.destroyForcibly()
        } finally {
            if (process === current) process = null
        }
    }

    /**
     * Resolves the Node runtime the launcher should dlopen.
     *
     * Download-first target: use the copy in app storage. Until the downloader
     * exists, this simulates a first-run download by copying the bundled
     * runtime out of nativeLibraryDir, which validates that dlopen()-ing the
     * library from app storage works on-device.
     */
    private fun resolveNodeLib(paths: AppPaths, layout: PayloadManager.Layout): File {
        val inStorage = File(paths.filesDir, "libnode.so")
        if (inStorage.exists()) return inStorage

        val bundled = File(layout.nativeLibDir, "libnode.so")
        if (bundled.exists()) {
            try {
                bundled.copyTo(inStorage, overwrite = false)
            } catch (_: Exception) {
                return bundled
            }
            if (inStorage.exists()) return inStorage
        }
        return bundled
    }

    companion object {
        const val DEFAULT_PORT = 8000
        private const val MAX_LOG_BYTES = 10L * 1024 * 1024

        /** Keeps logs bounded: rotate to "<name>.1" once over the cap. */
        fun rotateIfNeeded(file: File) {
            if (!file.exists() || file.length() <= MAX_LOG_BYTES) return
            val backup = File(file.parentFile, "${file.name}.1")
            if (backup.exists()) backup.delete()
            file.renameTo(backup)
        }
    }
}