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
    val timezone: String = defaultTimezone(),
    val nodeEnv: String = DEFAULT_NODE_ENV,
    val extraNodeOptions: List<String> = emptyList(),
    val extraEnv: Map<String, String> = emptyMap(),
) {
    val clampedMaxOldSpaceSizeMb: Int
        get() = maxOldSpaceSizeMb.coerceIn(MIN_OLD_SPACE_SIZE_MB, MAX_OLD_SPACE_SIZE_MB)

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

        private const val KEY_MAX_OLD_SPACE = "max_old_space_size_mb"
        private const val KEY_TIMEZONE = "timezone"
        private const val KEY_NODE_ENV = "node_env"
        private const val KEY_EXTRA_NODE_OPTIONS = "extra_node_options"
        private const val KEY_EXTRA_ENV = "extra_env"

        fun defaultTimezone(): String {
            return runCatching { TimeZone.getDefault().id.takeIf { it.isNotBlank() } }.getOrNull() ?: "UTC"
        }

        fun load(prefs: SharedPreferences): NodeConfig {
            val maxOldSpace = prefs.getInt(KEY_MAX_OLD_SPACE, DEFAULT_MAX_OLD_SPACE_SIZE_MB)
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
