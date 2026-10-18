package app.stmobile.models

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone

/**
 * Settings governing the Node.js runtime environment and V8 engine limits.
 */
data class NodeConfig(
    val maxOldSpaceSizeMb: Int = DEFAULT_MAX_OLD_SPACE_SIZE_MB,
    val optimizeForSize: Boolean = DEFAULT_OPTIMIZE_FOR_SIZE,
    val maxSemiSpaceSizeMb: Int = DEFAULT_MAX_SEMI_SPACE_SIZE_MB,
    val uvThreadPoolSize: Int = DEFAULT_UV_THREADPOOL_SIZE,
    val timezone: String = defaultTimezone(),
    val nodeEnv: String = DEFAULT_NODE_ENV,
    val extraNodeOptions: List<String> = emptyList(),
    val extraEnv: Map<String, String> = emptyMap(),
) {
    val clampedMaxOldSpaceSizeMb: Int
        get() = maxOldSpaceSizeMb.coerceIn(MIN_OLD_SPACE_SIZE_MB, MAX_OLD_SPACE_SIZE_MB)

    val clampedMaxSemiSpaceSizeMb: Int
        get() = maxSemiSpaceSizeMb.coerceIn(MIN_SEMI_SPACE_SIZE_MB, MAX_SEMI_SPACE_SIZE_MB)

    val clampedUvThreadPoolSize: Int
        get() = uvThreadPoolSize.coerceIn(1, 16)

    /**
     * Resolves the effective V8 max old space size, dynamically bounded by the device's
     * physical RAM to prevent Out-Of-Memory (LMK) termination on budget hardware.
     */
    fun resolveEffectiveMaxOldSpace(context: Context): Int {
        val safeCeiling = calculateSafeMaxHeapMb(context)
        return minOf(clampedMaxOldSpaceSizeMb, safeCeiling)
    }

    /**
     * Formats additional node options for NODE_OPTIONS.
     * Note: Core memory limits are passed directly via argv CLI to guarantee acceptance.
     */
    fun toNodeOptions(): String {
        return extraNodeOptions.joinToString(" ").trim()
    }

    companion object {
        const val PREFS_NAME = "node_config"

        const val DEFAULT_MAX_OLD_SPACE_SIZE_MB = 1024
        const val MIN_OLD_SPACE_SIZE_MB = 256
        const val MAX_OLD_SPACE_SIZE_MB = 4096
        const val DEFAULT_NODE_ENV = "production"

        fun calculateSafeMaxHeapMb(context: Context): Int {
            return runCatching {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                val memInfo = android.app.ActivityManager.MemoryInfo()
                am?.getMemoryInfo(memInfo)
                val totalMb = (memInfo.totalMem / (1024L * 1024L)).toInt()
                (totalMb * 0.5f).toInt().coerceIn(512, MAX_OLD_SPACE_SIZE_MB)
            }.getOrDefault(MAX_OLD_SPACE_SIZE_MB)
        }

        const val DEFAULT_OPTIMIZE_FOR_SIZE = false
        const val DEFAULT_MAX_SEMI_SPACE_SIZE_MB = 16
        const val MIN_SEMI_SPACE_SIZE_MB = 8
        const val MAX_SEMI_SPACE_SIZE_MB = 64
        const val DEFAULT_UV_THREADPOOL_SIZE = 2

        private const val KEY_MAX_OLD_SPACE = "max_old_space_size_mb"
        private const val KEY_OPTIMIZE_FOR_SIZE = "optimize_for_size"
        private const val KEY_MAX_SEMI_SPACE = "max_semi_space_size_mb"
        private const val KEY_UV_THREADPOOL_SIZE = "uv_threadpool_size"
        private const val KEY_TIMEZONE = "timezone"
        private const val KEY_NODE_ENV = "node_env"
        private const val KEY_EXTRA_NODE_OPTIONS = "extra_node_options"
        private const val KEY_EXTRA_ENV = "extra_env"

        fun defaultTimezone(): String {
            return runCatching { TimeZone.getDefault().id.takeIf { it.isNotBlank() } }.getOrNull() ?: "UTC"
        }

        fun load(prefs: SharedPreferences): NodeConfig {
            val maxOldSpace = prefs.getInt(KEY_MAX_OLD_SPACE, DEFAULT_MAX_OLD_SPACE_SIZE_MB)
            val optimizeForSize = prefs.getBoolean(KEY_OPTIMIZE_FOR_SIZE, DEFAULT_OPTIMIZE_FOR_SIZE)
            val maxSemiSpace = prefs.getInt(KEY_MAX_SEMI_SPACE, DEFAULT_MAX_SEMI_SPACE_SIZE_MB)
            val uvThreadPool = prefs.getInt(KEY_UV_THREADPOOL_SIZE, DEFAULT_UV_THREADPOOL_SIZE)
            val tz = prefs.getString(KEY_TIMEZONE, null) ?: defaultTimezone()
            val env = prefs.getString(KEY_NODE_ENV, DEFAULT_NODE_ENV) ?: DEFAULT_NODE_ENV

            val extraOptionsJson = prefs.getString(KEY_EXTRA_NODE_OPTIONS, null)
            val extraOptions = if (!extraOptionsJson.isNullOrBlank()) {
                val array = JSONArray(extraOptionsJson)
                (0 until array.length()).map { array.getString(it) }
            } else {
                emptyList()
            }

            val extraEnvJson = prefs.getString(KEY_EXTRA_ENV, null)
            val extraEnv = if (!extraEnvJson.isNullOrBlank()) {
                val obj = JSONObject(extraEnvJson)
                val map = mutableMapOf<String, String>()
                for (key in obj.keys()) {
                    map[key] = obj.getString(key)
                }
                map
            } else {
                emptyMap()
            }

            return NodeConfig(
                maxOldSpaceSizeMb = maxOldSpace,
                optimizeForSize = optimizeForSize,
                maxSemiSpaceSizeMb = maxSemiSpace,
                uvThreadPoolSize = uvThreadPool,
                timezone = tz,
                nodeEnv = env,
                extraNodeOptions = extraOptions,
                extraEnv = extraEnv,
            )
        }

        fun save(prefs: SharedPreferences, config: NodeConfig) {
            val optionsJson = JSONArray(config.extraNodeOptions).toString()
            val envJson = JSONObject(config.extraEnv).toString()

            prefs.edit()
                .putInt(KEY_MAX_OLD_SPACE, config.clampedMaxOldSpaceSizeMb)
                .putBoolean(KEY_OPTIMIZE_FOR_SIZE, config.optimizeForSize)
                .putInt(KEY_MAX_SEMI_SPACE, config.clampedMaxSemiSpaceSizeMb)
                .putInt(KEY_UV_THREADPOOL_SIZE, config.clampedUvThreadPoolSize)
                .putString(KEY_TIMEZONE, config.timezone.ifBlank { "UTC" })
                .putString(KEY_NODE_ENV, config.nodeEnv)
                .putString(KEY_EXTRA_NODE_OPTIONS, optionsJson)
                .putString(KEY_EXTRA_ENV, envJson)
                .apply()
        }

        fun load(context: Context): NodeConfig =
            load(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

        fun save(context: Context, config: NodeConfig) =
            save(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE), config)
    }
}
