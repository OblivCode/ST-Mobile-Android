package app.stmobile

import android.content.Context
import java.io.File

/** Single source of truth for every path the app touches (PLAN.md §3.1). */
class AppPaths(
    val filesDir: File,
    val cacheDir: File,
    val nativeLibDir: String,
) {
    constructor(context: Context) : this(
        filesDir = context.filesDir,
        cacheDir = context.cacheDir,
        nativeLibDir = context.applicationInfo.nativeLibraryDir,
    )

    /** Replaceable application tree (extracted from assets). */
    val stDir: File get() = File(filesDir, "st")

    /** User-owned state that survives payload replacement. */
    val configDir: File get() = File(filesDir, "config")
    val configFile: File get() = File(configDir, "config.yaml")
    val dataDir: File get() = File(filesDir, "data")
    val defaultUserDir: File get() = File(dataDir, "default-user")
    val defaultUserSettingsFile: File get() = File(defaultUserDir, "settings.json")
    val defaultContentSettingsFile: File get() = File(stDir, "default/content/settings.json")

    val logsDir: File get() = File(filesDir, "logs")
    val tmpDir: File get() = File(filesDir, "tmp")
    val nodeTmpDir: File get() = File(cacheDir, "node_tmp")
    val scriptsDir: File get() = File(filesDir, "scripts")
    val generationHookFile: File get() = File(scriptsDir, "generation_hook.cjs")

    /** Executable location: read-only, extracted from the APK, exec-safe (W^X). */
    val launcher: File get() = File(nativeLibDir, "libstnode.so")

    val stEntry: File get() = File(stDir, "server.js")
    val stConfigLink: File get() = File(stDir, "config.yaml")
    val stDataLink: File get() = File(stDir, "data")
}