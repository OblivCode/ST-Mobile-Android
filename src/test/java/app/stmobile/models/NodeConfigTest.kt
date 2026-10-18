package app.stmobile.models

import app.stmobile.testutils.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeConfigTest {

    @Test
    fun testClampedMemoryLimits() {
        val defaultConfig = NodeConfig()
        assertEquals(1024, defaultConfig.clampedMaxOldSpaceSizeMb)

        val lowConfig = NodeConfig(maxOldSpaceSizeMb = 64)
        assertEquals(256, lowConfig.clampedMaxOldSpaceSizeMb)

        val highConfig = NodeConfig(maxOldSpaceSizeMb = 16384)
        assertEquals(4096, highConfig.clampedMaxOldSpaceSizeMb)
    }

    @Test
    fun testClampedSemiSpaceLimits() {
        val defaultConfig = NodeConfig()
        assertEquals(16, defaultConfig.clampedMaxSemiSpaceSizeMb)

        val lowConfig = NodeConfig(maxSemiSpaceSizeMb = 4)
        assertEquals(8, lowConfig.clampedMaxSemiSpaceSizeMb)

        val highConfig = NodeConfig(maxSemiSpaceSizeMb = 128)
        assertEquals(64, highConfig.clampedMaxSemiSpaceSizeMb)
    }

    @Test
    fun testClampedThreadPoolLimits() {
        val defaultConfig = NodeConfig()
        assertEquals(2, defaultConfig.clampedUvThreadPoolSize)

        val lowConfig = NodeConfig(uvThreadPoolSize = 0)
        assertEquals(1, lowConfig.clampedUvThreadPoolSize)

        val highConfig = NodeConfig(uvThreadPoolSize = 32)
        assertEquals(16, highConfig.clampedUvThreadPoolSize)
    }

    @Test
    fun testToNodeOptions() {
        val configWithoutExtras = NodeConfig()
        assertEquals("", configWithoutExtras.toNodeOptions())

        val configWithExtras = NodeConfig(
            extraNodeOptions = listOf("--expose-gc", "--trace-warnings")
        )
        assertEquals("--expose-gc --trace-warnings", configWithExtras.toNodeOptions())
    }

    @Test
    fun testDefaultTimezone() {
        val tz = NodeConfig.defaultTimezone()
        assertTrue("Timezone must not be blank", tz.isNotBlank())
    }

    @Test
    fun testSharedPreferencesFallbackDefaults() {
        val prefs = FakeSharedPreferences()
        val config = NodeConfig.load(prefs)

        assertEquals(NodeConfig.DEFAULT_MAX_OLD_SPACE_SIZE_MB, config.maxOldSpaceSizeMb)
        assertFalse(config.optimizeForSize)
        assertEquals(NodeConfig.DEFAULT_MAX_SEMI_SPACE_SIZE_MB, config.maxSemiSpaceSizeMb)
        assertEquals(NodeConfig.DEFAULT_UV_THREADPOOL_SIZE, config.uvThreadPoolSize)
        assertEquals(NodeConfig.DEFAULT_NODE_ENV, config.nodeEnv)
        assertTrue(config.timezone.isNotBlank())
        assertTrue(config.extraNodeOptions.isEmpty())
        assertTrue(config.extraEnv.isEmpty())
    }

    @Test
    fun testSharedPreferencesSerializationRoundTrip() {
        val prefs = FakeSharedPreferences()
        val original = NodeConfig(
            maxOldSpaceSizeMb = 2048,
            optimizeForSize = true,
            maxSemiSpaceSizeMb = 32,
            uvThreadPoolSize = 4,
            timezone = "Europe/Paris",
            nodeEnv = "development",
            extraNodeOptions = listOf("--max-http-header-size=32768", "--inspect=0.0.0.0:9229"),
            extraEnv = mapOf("DEBUG" to "st:*", "CUSTOM_VAR" to "test_value")
        )

        NodeConfig.save(prefs, original)
        val loaded = NodeConfig.load(prefs)

        assertEquals(original.clampedMaxOldSpaceSizeMb, loaded.maxOldSpaceSizeMb)
        assertTrue(loaded.optimizeForSize)
        assertEquals(32, loaded.maxSemiSpaceSizeMb)
        assertEquals(4, loaded.uvThreadPoolSize)
        assertEquals("Europe/Paris", loaded.timezone)
        assertEquals("development", loaded.nodeEnv)
        assertEquals(listOf("--max-http-header-size=32768", "--inspect=0.0.0.0:9229"), loaded.extraNodeOptions)
        assertEquals(mapOf("DEBUG" to "st:*", "CUSTOM_VAR" to "test_value"), loaded.extraEnv)
    }

    @Test
    fun testMemoryLimitClampingOnSave() {
        val prefs = FakeSharedPreferences()

        // Saving low memory bound clamps to 256MB in SharedPreferences
        val lowConfig = NodeConfig(maxOldSpaceSizeMb = 32, maxSemiSpaceSizeMb = 4, uvThreadPoolSize = 0)
        NodeConfig.save(prefs, lowConfig)
        assertEquals(256, prefs.getInt("max_old_space_size_mb", -1))
        assertEquals(8, prefs.getInt("max_semi_space_size_mb", -1))
        assertEquals(1, prefs.getInt("uv_threadpool_size", -1))
        val lowLoaded = NodeConfig.load(prefs)
        assertEquals(256, lowLoaded.maxOldSpaceSizeMb)
        assertEquals(8, lowLoaded.maxSemiSpaceSizeMb)
        assertEquals(1, lowLoaded.uvThreadPoolSize)

        // Saving high memory bound clamps to 4096MB in SharedPreferences
        val highConfig = NodeConfig(maxOldSpaceSizeMb = 12000, maxSemiSpaceSizeMb = 128, uvThreadPoolSize = 64)
        NodeConfig.save(prefs, highConfig)
        assertEquals(4096, prefs.getInt("max_old_space_size_mb", -1))
        assertEquals(64, prefs.getInt("max_semi_space_size_mb", -1))
        assertEquals(16, prefs.getInt("uv_threadpool_size", -1))
        val highLoaded = NodeConfig.load(prefs)
        assertEquals(4096, highLoaded.maxOldSpaceSizeMb)
        assertEquals(64, highLoaded.maxSemiSpaceSizeMb)
        assertEquals(16, highLoaded.uvThreadPoolSize)
    }
}
