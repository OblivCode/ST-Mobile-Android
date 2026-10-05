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

    @Test
    fun testServerPluginsAndSkipContentCheckProperties() {
        val yaml = """
            enableServerPlugins: "true"
            skipContentCheck: false
        """.trimIndent()
        val config = StConfig.fromYaml(yaml)

        assertTrue(config.enableServerPlugins)
        assertFalse(config.skipContentCheck)

        config.enableServerPlugins = false
        config.skipContentCheck = true

        val dumped = StConfig.fromYaml(config.dumpYaml())
        assertFalse(dumped.enableServerPlugins)
        assertTrue(dumped.skipContentCheck)
    }

    @Test
    fun testFromFileOrDefaultBehavior() {
        val file = File(tempFolder.root, "non_existent_config.yaml")
        var providerInvoked = false
        val defaultYaml = "port: 8088\nlisten: true\n"

        // 1. File absent: invokes defaultProvider
        val config = StConfig.fromFileOrDefault(file) {
            providerInvoked = true
            defaultYaml.byteInputStream()
        }
        assertTrue("Provider must be invoked when file is absent", providerInvoked)
        assertEquals(8088, config.port)
        assertTrue(config.listen)

        // Save to create file on disk
        config.save(file)
        assertTrue(file.exists())

        // 2. File present: reads file directly without invoking provider
        providerInvoked = false
        val reloaded = StConfig.fromFileOrDefault(file) {
            providerInvoked = true
            "port: 9999\n".byteInputStream()
        }
        assertFalse("Provider must NOT be invoked when file is present on disk", providerInvoked)
        assertEquals(8088, reloaded.port)
    }

    @Test
    fun testEmptyOrNonMapYamlHandling() {
        val emptyConfig = StConfig.fromYaml("")
        assertEquals(StConfig.DEFAULT_PORT, emptyConfig.port)
        assertFalse(emptyConfig.listen)

        val nonMapConfig = StConfig.fromYaml("scalar string instead of yaml map")
        assertEquals(StConfig.DEFAULT_PORT, nonMapConfig.port)
    }

    @Test
    fun testDeepNestedPathCreationOverwritingPrimitive() {
        val config = StConfig.fromYaml("root: 42\n")
        // Overwriting primitive 'root' with nested path 'root.child.leaf'
        config.setPath("root.child.leaf", "nested_secret")
        assertEquals("nested_secret", config.getPath("root.child.leaf"))

        val dumped = StConfig.fromYaml(config.dumpYaml())
        assertEquals("nested_secret", dumped.getPath("root.child.leaf"))
    }

    @Test
    fun testWhitelistModifications() {
        val config = StConfig.fromYaml("port: 8000\n")
        assertEquals(listOf("127.0.0.1", "::1"), config.whitelist)

        val customWhitelist = listOf("10.0.0.1", "192.168.1.100")
        config.whitelist = customWhitelist
        assertEquals(customWhitelist, config.whitelist)

        val reloaded = StConfig.fromYaml(config.dumpYaml())
        assertEquals(customWhitelist, reloaded.whitelist)
    }
}
