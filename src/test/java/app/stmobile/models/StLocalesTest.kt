package app.stmobile.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class StLocalesTest {

    @Test
    fun testSupportedLocalesCatalogCompleteness() {
        assertEquals("Should support exactly 17 SillyTavern locale packs", 17, StLocales.SUPPORTED_LOCALES.size)

        val uniqueCodes = StLocales.SUPPORTED_LOCALES.map { it.code }.toSet()
        assertEquals("All 17 locale codes must be distinct", 17, uniqueCodes.size)

        val expectedCodes = listOf(
            "en", "ar-sa", "zh-cn", "zh-tw", "nl-nl", "de-de", "fr-fr", "is-is",
            "it-it", "ja-jp", "ko-kr", "pt-pt", "ru-ru", "es-es", "uk-ua", "vi-vn", "th-th"
        )
        for (code in expectedCodes) {
            assertNotNull("Locale catalog must contain code: $code", StLocales.findLocale(code))
        }
    }

    @Test
    fun testSupportedAppLocalesCatalogCompleteness() {
        assertEquals("Should support 7 app locale options (system default + 6 languages)", 7, StLocales.SUPPORTED_APP_LOCALES.size)

        val tags = StLocales.SUPPORTED_APP_LOCALES.map { it.tag }
        assertEquals(listOf("", "en", "zh", "ru", "es", "de", "ja"), tags)
    }

    @Test
    fun testFindLocaleAndFindAppLocaleCaseInsensitive() {
        assertEquals("de-de", StLocales.findLocale("DE-DE")?.code)
        assertEquals("zh-tw", StLocales.findLocale("  zh-TW  ")?.code)
        assertNull(StLocales.findLocale("non-existent"))

        assertEquals("zh", StLocales.findAppLocale("ZH")?.tag)
        assertEquals("", StLocales.findAppLocale(" ")?.tag)
        assertNull(StLocales.findAppLocale("xx"))
    }

    @Test
    fun testResolveSystemLocale() {
        assertEquals("de-de", StLocales.resolveSystemLocale(Locale.GERMANY))
        assertEquals("de-de", StLocales.resolveSystemLocale(Locale.forLanguageTag("de-AT")))
        assertEquals("es-es", StLocales.resolveSystemLocale(Locale.forLanguageTag("es-ES")))
        assertEquals("es-es", StLocales.resolveSystemLocale(Locale.forLanguageTag("es-MX")))
        assertEquals("ja-jp", StLocales.resolveSystemLocale(Locale.JAPAN))
        assertEquals("ru-ru", StLocales.resolveSystemLocale(Locale.forLanguageTag("ru-RU")))
        assertEquals("zh-cn", StLocales.resolveSystemLocale(Locale.SIMPLIFIED_CHINESE))
        assertEquals("zh-cn", StLocales.resolveSystemLocale(Locale.forLanguageTag("zh-Hans")))
        assertEquals("zh-tw", StLocales.resolveSystemLocale(Locale.TRADITIONAL_CHINESE))
        assertEquals("zh-tw", StLocales.resolveSystemLocale(Locale.forLanguageTag("zh-TW")))
        assertEquals("zh-tw", StLocales.resolveSystemLocale(Locale.forLanguageTag("zh-HK")))
        assertEquals("pt-pt", StLocales.resolveSystemLocale(Locale.forLanguageTag("pt-BR")))
        assertEquals("ar-sa", StLocales.resolveSystemLocale(Locale.forLanguageTag("ar-EG")))
        assertEquals("fr-fr", StLocales.resolveSystemLocale(Locale.FRANCE))
        assertEquals("en", StLocales.resolveSystemLocale(Locale.US))
        assertEquals("en", StLocales.resolveSystemLocale(Locale.forLanguageTag("xx-YY")))
    }

    @Test
    fun testMapAppLocaleToStLocale() {
        assertEquals("de-de", StLocales.mapAppLocaleToStLocale("de"))
        assertEquals("es-es", StLocales.mapAppLocaleToStLocale("es"))
        assertEquals("ru-ru", StLocales.mapAppLocaleToStLocale("ru"))
        assertEquals("ja-jp", StLocales.mapAppLocaleToStLocale("ja"))
        assertEquals("zh-cn", StLocales.mapAppLocaleToStLocale("zh"))
        assertEquals("zh-tw", StLocales.mapAppLocaleToStLocale("zh-tw"))
        assertEquals("en", StLocales.mapAppLocaleToStLocale("en"))

        // Blank tag delegates to fallbackLocale
        assertEquals("ja-jp", StLocales.mapAppLocaleToStLocale("", Locale.JAPAN))
        assertEquals("de-de", StLocales.mapAppLocaleToStLocale("   ", Locale.GERMANY))

        // Unbundled tag resolves via language tag resolution
        assertEquals("fr-fr", StLocales.mapAppLocaleToStLocale("fr"))
    }

    @Test
    fun testMapStLocaleToAppLocale() {
        assertEquals("en", StLocales.mapStLocaleToAppLocale("en"))
        assertEquals("zh", StLocales.mapStLocaleToAppLocale("zh-cn"))
        assertEquals("zh", StLocales.mapStLocaleToAppLocale("zh-tw"))
        assertEquals("ru", StLocales.mapStLocaleToAppLocale("ru-ru"))
        assertEquals("es", StLocales.mapStLocaleToAppLocale("es-es"))
        assertEquals("de", StLocales.mapStLocaleToAppLocale("de-de"))
        assertEquals("ja", StLocales.mapStLocaleToAppLocale("ja-jp"))

        // SillyTavern locales that do not have an app translation bucket return null
        assertNull(StLocales.mapStLocaleToAppLocale("th-th"))
        assertNull(StLocales.mapStLocaleToAppLocale("uk-ua"))
        assertNull(StLocales.mapStLocaleToAppLocale("is-is"))
    }

    @Test
    fun testGetEffectiveLocaleWithAutoSync() {
        // When autoSync is true, userSetting is ignored and appLocale is resolved
        assertEquals("ja-jp", StLocales.getEffectiveLocale(
            userSetting = "ru-ru",
            autoSync = true,
            appLocale = Locale.JAPAN
        ))
        assertEquals("de-de", StLocales.getEffectiveLocale(
            userSetting = "",
            autoSync = true,
            appLocale = Locale.GERMAN
        ))
    }

    @Test
    fun testGetEffectiveLocaleWithoutAutoSync() {
        // When autoSync is false and explicit user setting exists, user setting is honored
        assertEquals("ru-ru", StLocales.getEffectiveLocale(
            userSetting = "ru-ru",
            autoSync = false,
            appLocale = Locale.JAPAN
        ))

        // When autoSync is false but user setting is empty or "auto", falls back to appLocale
        assertEquals("ja-jp", StLocales.getEffectiveLocale(
            userSetting = "",
            autoSync = false,
            appLocale = Locale.JAPAN
        ))
        assertEquals("ja-jp", StLocales.getEffectiveLocale(
            userSetting = "auto",
            autoSync = false,
            appLocale = Locale.JAPAN
        ))
    }
}
