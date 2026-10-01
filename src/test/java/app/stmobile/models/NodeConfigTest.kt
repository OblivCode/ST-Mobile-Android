package app.stmobile.models

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
}
