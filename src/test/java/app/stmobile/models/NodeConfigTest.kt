package app.stmobile.models

import app.stmobile.testutils.FakeSharedPreferences
import org.junit.Assert.assertEquals
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
            timezone = "Europe/Paris",
            nodeEnv = "development",
            extraNodeOptions = listOf("--max-http-header-size=32768", "--inspect=0.0.0.0:9229"),
            extraEnv = mapOf("DEBUG" to "st:*", "CUSTOM_VAR" to "test_value")
        )

        NodeConfig.save(prefs, original)
        val loaded = NodeConfig.load(prefs)

        assertEquals(original.clampedMaxOldSpaceSizeMb, loaded.maxOldSpaceSizeMb)
        assertEquals("Europe/Paris", loaded.timezone)
        assertEquals("development", loaded.nodeEnv)
        assertEquals(listOf("--max-http-header-size=32768", "--inspect=0.0.0.0:9229"), loaded.extraNodeOptions)
        assertEquals(mapOf("DEBUG" to "st:*", "CUSTOM_VAR" to "test_value"), loaded.extraEnv)
    }

    @Test
    fun testMemoryLimitClampingOnSave() {
        val prefs = FakeSharedPreferences()

        // Saving low memory bound clamps to 256MB in SharedPreferences
        val lowConfig = NodeConfig(maxOldSpaceSizeMb = 32)
        NodeConfig.save(prefs, lowConfig)
        assertEquals(256, prefs.getInt("max_old_space_size_mb", -1))
        assertEquals(256, NodeConfig.load(prefs).maxOldSpaceSizeMb)

        // Saving high memory bound clamps to 4096MB in SharedPreferences
        val highConfig = NodeConfig(maxOldSpaceSizeMb = 12000)
        NodeConfig.save(prefs, highConfig)
        assertEquals(4096, prefs.getInt("max_old_space_size_mb", -1))
        assertEquals(4096, NodeConfig.load(prefs).maxOldSpaceSizeMb)
    }
}
