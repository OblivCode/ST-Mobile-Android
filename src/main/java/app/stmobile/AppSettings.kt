package app.stmobile

import android.content.Context

/**
 * App-level settings (PLAN.md §6.1). ST's own settings live in the web UI.
 * These are applied to the server as SILLYTAVERN_* env overrides / CLI flags
 * so the user's config.yaml is never rewritten.
 */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    var port: Int
        get() = prefs.getInt(KEY_PORT, NodeController.DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    var basicAuthEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTH, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTH, value).apply()

    var basicAuthUsername: String
        get() = prefs.getString(KEY_AUTH_USER, "user") ?: "user"
        set(value) = prefs.edit().putString(KEY_AUTH_USER, value).apply()

    var basicAuthPassword: String
        get() = prefs.getString(KEY_AUTH_PASS, "password") ?: "password"
        set(value) = prefs.edit().putString(KEY_AUTH_PASS, value).apply()

    var batteryPrompted: Boolean
        get() = prefs.getBoolean(KEY_BATTERY, false)
        set(value) = prefs.edit().putBoolean(KEY_BATTERY, value).apply()

    private companion object {
        const val KEY_PORT = "port"
        const val KEY_AUTH = "basic_auth_enabled"
        const val KEY_AUTH_USER = "basic_auth_user"
        const val KEY_AUTH_PASS = "basic_auth_pass"
        const val KEY_BATTERY = "battery_prompted"
    }
}