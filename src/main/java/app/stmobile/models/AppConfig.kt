package app.stmobile.models

import android.content.Context
import android.content.SharedPreferences

/**
 * Android shell-level settings persisted in SharedPreferences.
 * Distinct from SillyTavern's own server config (which lives in config.yaml).
 */
class AppConfig(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        migrateLegacySettings(context)
    }

    var autoStartOnAppOpen: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, DEFAULT_AUTO_START)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START, value).apply()

    var autoLaunchWebViewOnStart: Boolean
        get() = prefs.getBoolean(KEY_AUTO_LAUNCH_WEBVIEW, DEFAULT_AUTO_LAUNCH_WEBVIEW)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_LAUNCH_WEBVIEW, value).apply()

    var backgroundTimeoutMinutes: Int
        get() = prefs.getInt(KEY_BG_TIMEOUT, DEFAULT_BG_TIMEOUT)
        set(value) = prefs.edit().putInt(KEY_BG_TIMEOUT, value).apply()

    var autoPortFallback: Boolean
        get() = prefs.getBoolean(KEY_AUTO_PORT_FALLBACK, DEFAULT_AUTO_PORT_FALLBACK)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_PORT_FALLBACK, value).apply()

    var batteryPrompted: Boolean
        get() = prefs.getBoolean(KEY_BATTERY_PROMPTED, false)
        set(value) = prefs.edit().putBoolean(KEY_BATTERY_PROMPTED, value).apply()

    var basicAuthEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTH_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTH_ENABLED, value).apply()

    var basicAuthUsername: String
        get() = prefs.getString(KEY_AUTH_USER, DEFAULT_AUTH_USER) ?: DEFAULT_AUTH_USER
        set(value) = prefs.edit().putString(KEY_AUTH_USER, value).apply()

    var basicAuthPassword: String
        get() = prefs.getString(KEY_AUTH_PASS, DEFAULT_AUTH_PASS) ?: DEFAULT_AUTH_PASS
        set(value) = prefs.edit().putString(KEY_AUTH_PASS, value).apply()

    var wasRunningBeforeKill: Boolean
        get() = prefs.getBoolean(KEY_WAS_RUNNING_BEFORE_KILL, false)
        set(value) = prefs.edit().putBoolean(KEY_WAS_RUNNING_BEFORE_KILL, value).apply()

    private fun migrateLegacySettings(context: Context) {
        val legacy = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        if (legacy.all.isEmpty()) return

        val editor = prefs.edit()
        var migrated = false

        if (!prefs.contains(KEY_AUTH_ENABLED) && legacy.contains("basic_auth_enabled")) {
            editor.putBoolean(KEY_AUTH_ENABLED, legacy.getBoolean("basic_auth_enabled", false))
            migrated = true
        }
        if (!prefs.contains(KEY_AUTH_USER) && legacy.contains("basic_auth_user")) {
            editor.putString(KEY_AUTH_USER, legacy.getString("basic_auth_user", DEFAULT_AUTH_USER))
            migrated = true
        }
        if (!prefs.contains(KEY_AUTH_PASS) && legacy.contains("basic_auth_pass")) {
            editor.putString(KEY_AUTH_PASS, legacy.getString("basic_auth_pass", DEFAULT_AUTH_PASS))
            migrated = true
        }
        if (!prefs.contains(KEY_BATTERY_PROMPTED) && legacy.contains("battery_prompted")) {
            editor.putBoolean(KEY_BATTERY_PROMPTED, legacy.getBoolean("battery_prompted", false))
            migrated = true
        }

        if (migrated) {
            editor.apply()
        }
    }

    companion object {
        const val PREFS_NAME = "app_config"
        private const val LEGACY_PREFS_NAME = "app_settings"

        const val DEFAULT_AUTO_START = false
        const val DEFAULT_AUTO_LAUNCH_WEBVIEW = false
        const val DEFAULT_BG_TIMEOUT = 5
        const val DEFAULT_AUTO_PORT_FALLBACK = true
        const val DEFAULT_AUTH_USER = "user"
        const val DEFAULT_AUTH_PASS = "password"

        private const val KEY_AUTO_START = "auto_start_on_app_open"
        private const val KEY_AUTO_LAUNCH_WEBVIEW = "auto_launch_webview_on_start"
        private const val KEY_BG_TIMEOUT = "background_timeout_minutes"
        private const val KEY_AUTO_PORT_FALLBACK = "auto_port_fallback"
        private const val KEY_BATTERY_PROMPTED = "battery_prompted"
        private const val KEY_AUTH_ENABLED = "basic_auth_enabled"
        private const val KEY_AUTH_USER = "basic_auth_user"
        private const val KEY_AUTH_PASS = "basic_auth_pass"
        private const val KEY_WAS_RUNNING_BEFORE_KILL = "was_running_before_kill"
    }
}
