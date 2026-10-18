package app.stmobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchEngineTest {

    private val sampleItems = listOf(
        SettingsSearchItem(
            id = "app_theme",
            tab = SettingsSearchTab.APP,
            category = "Theme",
            title = "App Theme",
            description = "Select dark, light, or auto-sync with device theme",
            keywords = listOf("theme", "dark", "light", "mode", "appearance"),
        ),
        SettingsSearchItem(
            id = "app_language",
            tab = SettingsSearchTab.APP,
            category = "Language",
            title = "App Language",
            description = "Select app shell interface language",
            keywords = listOf("language", "locale", "translate"),
        ),
        SettingsSearchItem(
            id = "st_smooth_streaming",
            tab = SettingsSearchTab.SILLYTAVERN,
            category = "Streaming & Motion",
            title = "Smooth token streaming",
            description = "Buffer incoming tokens for smoother text flow",
            keywords = listOf("streaming", "smooth", "tokens", "animation"),
        ),
        SettingsSearchItem(
            id = "st_streaming_fps",
            tab = SettingsSearchTab.SILLYTAVERN,
            category = "Streaming & Motion",
            title = "Streaming refresh rate",
            description = "Token render animation frequency",
            keywords = listOf("fps", "refresh", "rate", "streaming", "speed"),
        ),
        SettingsSearchItem(
            id = "node_max_heap",
            tab = SettingsSearchTab.NODE_ENGINE,
            category = "Memory & V8",
            title = "V8 Maximum Heap Size",
            description = "V8 engine maximum old generation memory limit",
            keywords = listOf("memory", "ram", "heap", "v8", "node"),
        ),
        SettingsSearchItem(
            id = "wv_raster",
            tab = SettingsSearchTab.WEBVIEW,
            category = "Rendering",
            title = "Offscreen pre-rasterization",
            description = "Pre-render hidden cards outside viewport",
            keywords = listOf("raster", "gpu", "rendering", "performance"),
        ),
    )

    private val engine = SettingsSearchEngine(sampleItems)

    @Test
    fun testEmptyQueryReturnsAllItems() {
        val results = engine.search("")
        assertEquals(sampleItems.size, results.size)
    }

    @Test
    fun testSingleKeywordMatch() {
        val results = engine.search("theme")
        assertEquals(1, results.size)
        assertEquals("app_theme", results[0].id)
    }

    @Test
    fun testMultiTokenMatch() {
        val results = engine.search("stream smooth")
        assertEquals(1, results.size)
        assertEquals("st_smooth_streaming", results[0].id)
    }

    @Test
    fun testCaseInsensitiveAndWhitespaceHandling() {
        val results = engine.search("  sTrEaM   ")
        assertEquals(2, results.size)
        assertTrue(results.any { it.id == "st_smooth_streaming" })
        assertTrue(results.any { it.id == "st_streaming_fps" })
    }

    @Test
    fun testTabFiltering() {
        val allStreaming = engine.search("stream", SettingsSearchTab.ALL)
        assertEquals(2, allStreaming.size)

        val appStreaming = engine.search("stream", SettingsSearchTab.APP)
        assertEquals(0, appStreaming.size)

        val stStreaming = engine.search("stream", SettingsSearchTab.SILLYTAVERN)
        assertEquals(2, stStreaming.size)

        val wvItems = engine.search("raster", SettingsSearchTab.WEBVIEW)
        assertEquals(1, wvItems.size)
        assertEquals("wv_raster", wvItems[0].id)
    }

    @Test
    fun testNonMatchingQuery() {
        val results = engine.search("xyznonexistentword")
        assertTrue(results.isEmpty())
    }
}
