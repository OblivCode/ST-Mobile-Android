package app.stmobile.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StConfigTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testParseDefaultsAndPreserveUnknownKeys() {
        val sampleYaml = """
            port: 8000
            listen: false
            whitelistMode: true
            whitelist:
              - "127.0.0.1"
              - "::1"
            protocol:
              ipv4: true
              ipv6: false
            customPluginSettings:
              enabled: true
              token: "secret-token-xyz"
              threshold: 42
            unknownList:
              - alpha
              - beta
        """.trimIndent()

        val config = StConfig.fromYaml(sampleYaml)

        // Verify typed accessors
        assertEquals(8000, config.port)
        assertFalse(config.listen)
        assertTrue(config.whitelistMode)
        assertEquals(listOf("127.0.0.1", "::1"), config.whitelist)

        // Verify dotted path navigation
        assertEquals(true, config.getPath("protocol.ipv4"))
        assertEquals(false, config.getPath("protocol.ipv6"))
        assertEquals(true, config.getPath("customPluginSettings.enabled"))
        assertEquals("secret-token-xyz", config.getPath("customPluginSettings.token"))
        assertEquals(42, config.getPath("customPluginSettings.threshold"))

        // Mutate known and unknown properties
        config.port = 9090
        config.setPath("customPluginSettings.token", "new-token-123")
        config.setPath("newSection.nestedField", "helloWorld")

        // Dump YAML and reparse
        val dumped = config.dumpYaml()
        val reloaded = StConfig.fromYaml(dumped)

        // Verify mutations persisted
        assertEquals(9090, reloaded.port)
        assertEquals("new-token-123", reloaded.getPath("customPluginSettings.token"))
        assertEquals("helloWorld", reloaded.getPath("newSection.nestedField"))

        // Verify preserved unknown keys
        assertEquals(42, reloaded.getPath("customPluginSettings.threshold"))
        assertEquals(listOf("alpha", "beta"), reloaded.raw()["unknownList"])
    }

    @Test
    fun testSafeTypeCoercion() {
        // String port and string booleans
        val yamlWithStringPort = """
            port: "8085"
            listen: "true"
            whitelistMode: "false"
        """.trimIndent()

        val config = StConfig.fromYaml(yamlWithStringPort)
        assertEquals(8085, config.port)
        assertTrue(config.listen)
        assertFalse(config.whitelistMode)
    }

    @Test
    fun testAtomicFileSaveAndReload() {
        val file = File(tempFolder.root, "config.yaml")
        val sampleYaml = """
            port: 7000
            browserLaunch:
              enabled: false
        """.trimIndent()

        val config = StConfig.fromYaml(sampleYaml, file)
        config.save(file)

        assertTrue("File should exist on disk", file.exists())

        // Read back from file
        val fromDisk = StConfig.fromFile(file)
        assertEquals(7000, fromDisk.port)
        assertEquals(false, fromDisk.getPath("browserLaunch.enabled"))

        // Update and save again
        fromDisk.port = 7050
        fromDisk.save()

        val reloadedDisk = StConfig.fromFile(file)
        assertEquals(7050, reloadedDisk.port)
    }
}
