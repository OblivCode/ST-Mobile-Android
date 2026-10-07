package app.stmobile.models

import android.content.Context
import app.stmobile.AppPaths
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Two-way typed synchronization for SillyTavern's `settings.json` (Phase N).
 * Backed by an internal [JSONObject] preserving all unknown root sections and
 * power_user configuration keys across read/write cycles.
 */
class StUserSettings(
    private val rawJson: JSONObject,
    var sourceFile: File? = null,
) {

    private fun getOrCreatePowerUser(): JSONObject {
        var pu = rawJson.optJSONObject(KEY_POWER_USER)
        if (pu == null) {
            pu = JSONObject()
            rawJson.put(KEY_POWER_USER, pu)
        }
        return pu
    }

    var autoLoadChat: Boolean
        get() = rawJson.optJSONObject(KEY_POWER_USER)?.optBoolean("auto_load_chat", DEFAULT_AUTO_LOAD_CHAT)
            ?: DEFAULT_AUTO_LOAD_CHAT
        set(value) {
            getOrCreatePowerUser().put("auto_load_chat", value)
        }

    var autoConnect: Boolean
        get() = rawJson.optJSONObject(KEY_POWER_USER)?.optBoolean("auto_connect", DEFAULT_AUTO_CONNECT)
            ?: DEFAULT_AUTO_CONNECT
        set(value) {
            getOrCreatePowerUser().put("auto_connect", value)
        }

    var compactInputArea: Boolean
        get() = rawJson.optJSONObject(KEY_POWER_USER)?.optBoolean("compact_input_area", DEFAULT_COMPACT_INPUT_AREA)
            ?: DEFAULT_COMPACT_INPUT_AREA
        set(value) {
            getOrCreatePowerUser().put("compact_input_area", value)
        }

    var sendOnEnter: Int
        get() = rawJson.optJSONObject(KEY_POWER_USER)?.optInt("send_on_enter", DEFAULT_SEND_ON_ENTER)
            ?: DEFAULT_SEND_ON_ENTER
        set(value) {
            getOrCreatePowerUser().put("send_on_enter", value)
        }

    var fastUiMode: Boolean
        get() = rawJson.optJSONObject(KEY_POWER_USER)?.optBoolean("fast_ui_mode", DEFAULT_FAST_UI_MODE)
            ?: DEFAULT_FAST_UI_MODE
        set(value) {
            getOrCreatePowerUser().put("fast_ui_mode", value)
        }

    var reducedMotion: Boolean
        get() = rawJson.optJSONObject(KEY_POWER_USER)?.optBoolean("reduced_motion", DEFAULT_REDUCED_MOTION)
            ?: DEFAULT_REDUCED_MOTION
        set(value) {
            getOrCreatePowerUser().put("reduced_motion", value)
        }

    var fontScale: Float
        get() {
            val raw = rawJson.optJSONObject(KEY_POWER_USER)?.optDouble("font_scale", DEFAULT_FONT_SCALE.toDouble())?.toFloat()
                ?: DEFAULT_FONT_SCALE
            return if (raw.isNaN()) DEFAULT_FONT_SCALE else raw.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
        }
        set(value) {
            val clamped = if (value.isNaN()) DEFAULT_FONT_SCALE else value.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)
            getOrCreatePowerUser().put("font_scale", clamped.toDouble())
        }

    var uiLanguage: String
        get() = rawJson.optJSONObject(KEY_POWER_USER)?.optString("ui_language", DEFAULT_UI_LANGUAGE)
            ?: DEFAULT_UI_LANGUAGE
        set(value) {
            getOrCreatePowerUser().put("ui_language", value)
        }

    /** Direct access to the underlying preserved JSON object. */
    fun raw(): JSONObject = rawJson

    /** Atomically saves the user settings to the target file. */
    fun save(targetFile: File? = sourceFile) {
        val destination = targetFile ?: error("No target file specified for saving StUserSettings")
        destination.parentFile?.mkdirs()

        val tmpFile = File(destination.parentFile, "${destination.name}.tmp")
        tmpFile.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(rawJson.toString(4))
            writer.newLine()
        }

        try {
            Files.move(
                tmpFile.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                tmpFile.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        } finally {
            if (tmpFile.exists()) {
                tmpFile.delete()
            }
        }
    }

    companion object {
        private const val KEY_POWER_USER = "power_user"

        const val SEND_ON_ENTER_DISABLED = -1
        const val SEND_ON_ENTER_AUTO = 0
        const val SEND_ON_ENTER_ENABLED = 1

        const val DEFAULT_AUTO_LOAD_CHAT = false
        const val DEFAULT_AUTO_CONNECT = false
        const val DEFAULT_COMPACT_INPUT_AREA = true
        const val DEFAULT_SEND_ON_ENTER = SEND_ON_ENTER_AUTO
        const val DEFAULT_FAST_UI_MODE = true
        const val DEFAULT_REDUCED_MOTION = false
        const val DEFAULT_FONT_SCALE = 1.0f
        const val MIN_FONT_SCALE = 0.5f
        const val MAX_FONT_SCALE = 1.5f
        const val DEFAULT_UI_LANGUAGE = ""

        fun fromJson(jsonText: String, sourceFile: File? = null): StUserSettings {
            val obj = runCatching { JSONObject(jsonText) }.getOrElse { JSONObject() }
            return StUserSettings(obj, sourceFile)
        }

        fun fromFile(file: File): StUserSettings {
            return fromJson(file.readText(Charsets.UTF_8), file)
        }

        fun fromFileOrDefault(file: File, templateFile: File? = null): StUserSettings {
            if (file.exists() && file.length() > 0L) {
                val parsed = runCatching { fromFile(file) }.getOrNull()
                if (parsed != null) return parsed
            }
            if (templateFile != null && templateFile.exists() && templateFile.length() > 0L) {
                val parsed = runCatching {
                    val content = templateFile.readText(Charsets.UTF_8)
                    // Always bind sourceFile to the target destination file
                    StUserSettings(JSONObject(content), file)
                }.getOrNull()
                if (parsed != null) return parsed
            }
            return StUserSettings(JSONObject(), file)
        }

        fun load(context: Context): StUserSettings {
            val paths = AppPaths(context)
            return fromFileOrDefault(paths.defaultUserSettingsFile, paths.defaultContentSettingsFile)
        }
    }
}
