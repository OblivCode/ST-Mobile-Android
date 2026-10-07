package app.stmobile.models

import java.util.Locale

data class StLocale(
    val code: String,
    val nativeName: String,
    val englishName: String,
)

data class AppLocale(
    val tag: String,
    val nativeName: String,
    val englishName: String,
)

object StLocales {
    /**
     * The 17 bundled SillyTavern locale packs from public/locales/lang.json.
     */
    val SUPPORTED_LOCALES = listOf(
        StLocale("en", "English", "English"),
        StLocale("ar-sa", "عربي", "Arabic"),
        StLocale("zh-cn", "简体中文", "Chinese (Simplified)"),
        StLocale("zh-tw", "繁體中文", "Chinese (Taiwan)"),
        StLocale("nl-nl", "Nederlands", "Dutch"),
        StLocale("de-de", "Deutsch", "German"),
        StLocale("fr-fr", "Français", "French"),
        StLocale("is-is", "Íslenska", "Icelandic"),
        StLocale("it-it", "Italiano", "Italian"),
        StLocale("ja-jp", "日本語", "Japanese"),
        StLocale("ko-kr", "한국어", "Korean"),
        StLocale("pt-pt", "Português", "Portuguese (Brazil)"),
        StLocale("ru-ru", "Русский", "Russian"),
        StLocale("es-es", "Español", "Spanish"),
        StLocale("uk-ua", "Українська", "Ukrainian"),
        StLocale("vi-vn", "Tiếng Việt", "Vietnamese"),
        StLocale("th-th", "ไทย", "Thai"),
    )

    /**
     * Supported native Android shell languages corresponding to available resource buckets.
     */
    val SUPPORTED_APP_LOCALES = listOf(
        AppLocale("", "System Default", "System Default"),
        AppLocale("en", "English", "English"),
        AppLocale("zh", "简体中文", "Chinese (Simplified)"),
        AppLocale("ru", "Русский", "Russian"),
        AppLocale("es", "Español", "Spanish"),
        AppLocale("de", "Deutsch", "German"),
        AppLocale("ja", "日本語", "Japanese"),
    )

    fun findLocale(code: String): StLocale? =
        SUPPORTED_LOCALES.find { it.code.equals(code.trim(), ignoreCase = true) }

    fun findAppLocale(tag: String): AppLocale? =
        SUPPORTED_APP_LOCALES.find { it.tag.equals(tag.trim(), ignoreCase = true) }

    /**
     * Maps an arbitrary device/app [Locale] to the closest supported SillyTavern pack code.
     */
    fun resolveSystemLocale(locale: Locale = Locale.getDefault()): String {
        val lang = locale.language.lowercase()
        val country = locale.country.uppercase()
        val script = locale.script.lowercase()

        return when (lang) {
            "ar" -> "ar-sa"
            "de" -> "de-de"
            "es" -> "es-es"
            "fr" -> "fr-fr"
            "is" -> "is-is"
            "it" -> "it-it"
            "ja" -> "ja-jp"
            "ko" -> "ko-kr"
            "nl" -> "nl-nl"
            "pt" -> "pt-pt"
            "ru" -> "ru-ru"
            "th" -> "th-th"
            "uk" -> "uk-ua"
            "vi" -> "vi-vn"
            "zh" -> if (script == "hant" || country in setOf("TW", "HK", "MO")) "zh-tw" else "zh-cn"
            else -> "en"
        }
    }

    /**
     * Maps a native app language tag (e.g. "de", "es", "zh") to the corresponding SillyTavern locale code.
     */
    fun mapAppLocaleToStLocale(appTag: String, fallbackLocale: Locale = Locale.getDefault()): String {
        val trimmed = appTag.trim()
        if (trimmed.isEmpty()) {
            return resolveSystemLocale(fallbackLocale)
        }
        return when (trimmed.lowercase()) {
            "zh", "zh-cn", "zh-hans" -> "zh-cn"
            "zh-tw", "zh-hant", "zh-hk" -> "zh-tw"
            "de" -> "de-de"
            "es" -> "es-es"
            "ru" -> "ru-ru"
            "ja" -> "ja-jp"
            "en" -> "en"
            else -> resolveSystemLocale(Locale.forLanguageTag(trimmed))
        }
    }

    /**
     * Maps a SillyTavern pack code to an available app shell language tag, or null if unsupported.
     */
    fun mapStLocaleToAppLocale(stCode: String): String? {
        return when (stCode.trim().lowercase()) {
            "en" -> "en"
            "zh-cn", "zh-tw" -> "zh"
            "ru-ru" -> "ru"
            "es-es" -> "es"
            "de-de" -> "de"
            "ja-jp" -> "ja"
            else -> null
        }
    }

    /**
     * Computes the effective SillyTavern locale code given user settings, auto-sync state,
     * and the active app/system locale.
     */
    fun getEffectiveLocale(
        userSetting: String,
        autoSync: Boolean,
        appLocale: Locale = Locale.getDefault(),
    ): String {
        if (autoSync) {
            return resolveSystemLocale(appLocale)
        }
        val trimmed = userSetting.trim()
        if (trimmed.isNotEmpty() && !trimmed.equals("auto", ignoreCase = true)) {
            return trimmed
        }
        return resolveSystemLocale(appLocale)
    }
}
