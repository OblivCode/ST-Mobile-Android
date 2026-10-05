package app.stmobile.node

import app.stmobile.models.AppConfig
import app.stmobile.models.NodeConfig
import app.stmobile.models.StConfig
import app.stmobile.sillytavern.PayloadManager
import app.stmobile.testutils.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

class ProcessLaunchContractTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createFakeLayout(): Pair<PayloadManager.Layout, File> {
        val root = tempFolder.newFolder("runtime_root")
        val launcher = File(root, "libstnode.so").apply { createNewFile() }
        val stDir = File(root, "st").apply { mkdirs() }
        val stEntry = File(stDir, "server.js").apply { createNewFile() }
        val configFile = File(root, "config.yaml").apply { createNewFile() }
        val dataDir = File(root, "data").apply { mkdirs() }
        val logsDir = File(root, "logs").apply { mkdirs() }
        val nodeTmpDir = File(root, "node_tmp").apply { mkdirs() }
        val nativeLibDir = File(root, "lib").apply { mkdirs() }.absolutePath
        val homeDir = File(root, "home").apply { mkdirs() }

        val layout = PayloadManager.Layout(
            launcher = launcher,
            stDir = stDir,
            stEntry = stEntry,
            configFile = configFile,
            dataDir = dataDir,
            logsDir = logsDir,
            nodeTmpDir = nodeTmpDir,
            nativeLibDir = nativeLibDir,
            payloadUpdated = false
        )
        return layout to homeDir
    }

    @Test
    fun testCommandLineArgumentsOrdering() {
        val (layout, homeDir) = createFakeLayout()
        val stConfig = StConfig.fromYaml("port: 8000\n")
        val nodeConfig = NodeConfig(
            maxOldSpaceSizeMb = 1024,
            extraNodeOptions = listOf("--expose-gc", "--trace-warnings")
        )
        val prefs = FakeSharedPreferences()
        val appConfig = AppConfig(prefs)

        val spec = NodeController.buildLaunchSpec(layout, stConfig, nodeConfig, appConfig, homeDir)
        val cmd = spec.command

        // 1. Program must be argv[0]
        assertEquals(layout.launcher.absolutePath, cmd[0])

        // 2. Node/V8 options strictly BEFORE script entry point
        val entryIndex = cmd.indexOf(layout.stEntry.absolutePath)
        assertTrue("Entry file must exist in command line", entryIndex > 0)

        val memoryFlagIndex = cmd.indexOf("--max-old-space-size=1024")
        assertTrue("Memory flag must be present", memoryFlagIndex in 1 until entryIndex)

        val gcFlagIndex = cmd.indexOf("--expose-gc")
        assertTrue("--expose-gc must be present before entry", gcFlagIndex in 1 until entryIndex)

        val warnFlagIndex = cmd.indexOf("--trace-warnings")
        assertTrue("--trace-warnings must be present before entry", warnFlagIndex in 1 until entryIndex)

        // 3. SillyTavern CLI args AFTER script entry point
        val configFlagIndex = cmd.indexOf("--configPath")
        assertTrue("--configPath must follow entry file", configFlagIndex > entryIndex)
        assertEquals(layout.configFile.absolutePath, cmd[configFlagIndex + 1])

        val dataFlagIndex = cmd.indexOf("--dataRoot")
        assertTrue("--dataRoot must follow entry file", dataFlagIndex > entryIndex)
        assertEquals(layout.dataDir.absolutePath, cmd[dataFlagIndex + 1])

        val browserFlagIndex = cmd.indexOf("--browserLaunchEnabled")
        assertTrue("--browserLaunchEnabled must follow entry file", browserFlagIndex > entryIndex)
        assertEquals("false", cmd[browserFlagIndex + 1])

        val portFlagIndex = cmd.indexOf("--port")
        assertTrue("--port must follow entry file", portFlagIndex > entryIndex)
        assertEquals("8000", cmd[portFlagIndex + 1])
    }

    @Test
    fun testWorkingDirectoryAndLogRedirection() {
        val (layout, homeDir) = createFakeLayout()
        val stConfig = StConfig.fromYaml("port: 8000\n")
        val nodeConfig = NodeConfig()
        val appConfig = AppConfig(FakeSharedPreferences())

        val spec = NodeController.buildLaunchSpec(layout, stConfig, nodeConfig, appConfig, homeDir)

        assertEquals("Working directory must be layout.stDir", layout.stDir, spec.directory)
        assertEquals(File(layout.logsDir, "node_stdout.log"), spec.stdoutFile)
        assertEquals(File(layout.logsDir, "node_stderr.log"), spec.stderrFile)
    }

    @Test
    fun testEnvironmentVariablesContract() {
        val (layout, homeDir) = createFakeLayout()
        val stConfig = StConfig.fromYaml("port: 8080\n")
        val nodeConfig = NodeConfig(
            nodeEnv = "production",
            timezone = "Europe/Berlin",
            extraNodeOptions = listOf("--no-deprecation"),
            extraEnv = mapOf("CUSTOM_TEST_VAR" to "hello_world")
        )
        val prefs = FakeSharedPreferences()
        val appConfig = AppConfig(prefs).apply {
            basicAuthEnabled = true
            basicAuthUsername = "adminUser"
            basicAuthPassword = "adminPass123"
        }

        val spec = NodeController.buildLaunchSpec(layout, stConfig, nodeConfig, appConfig, homeDir)
        val env = spec.environment

        assertEquals(layout.nativeLibDir, env["LD_LIBRARY_PATH"])
        assertEquals(homeDir.absolutePath, env["HOME"])
        assertEquals(layout.nodeTmpDir.absolutePath, env["TMPDIR"])
        assertEquals(layout.nodeTmpDir.absolutePath, env["TMP"])
        assertEquals(layout.nodeTmpDir.absolutePath, env["TEMP"])
        assertEquals("production", env["NODE_ENV"])
        assertEquals("Europe/Berlin", env["TZ"])
        assertEquals("8080", env["SILLYTAVERN_PORT"])
        assertEquals("1", env["ST_ANDROID"])
        assertEquals("--no-deprecation", env["NODE_OPTIONS"])
        assertEquals("true", env["SILLYTAVERN_BASICAUTHMODE"])
        assertEquals("adminUser", env["SILLYTAVERN_BASICAUTHUSER_USERNAME"])
        assertEquals("adminPass123", env["SILLYTAVERN_BASICAUTHUSER_PASSWORD"])
        assertEquals("hello_world", env["CUSTOM_TEST_VAR"])
    }

    @Test
    fun testBasicAuthOmittedWhenDisabled() {
        val (layout, homeDir) = createFakeLayout()
        val stConfig = StConfig.fromYaml("port: 8000\n")
        val nodeConfig = NodeConfig()
        val appConfig = AppConfig(FakeSharedPreferences()).apply {
            basicAuthEnabled = false
        }

        val spec = NodeController.buildLaunchSpec(layout, stConfig, nodeConfig, appConfig, homeDir)
        val env = spec.environment

        assertFalse("SILLYTAVERN_BASICAUTHMODE must not be set when disabled", env.containsKey("SILLYTAVERN_BASICAUTHMODE"))
        assertFalse("SILLYTAVERN_BASICAUTHUSER_USERNAME must not be set when disabled", env.containsKey("SILLYTAVERN_BASICAUTHUSER_USERNAME"))
        assertFalse("SILLYTAVERN_BASICAUTHUSER_PASSWORD must not be set when disabled", env.containsKey("SILLYTAVERN_BASICAUTHUSER_PASSWORD"))
    }

    @Test
    fun testPortConflictAutoFallbackSelection() {
        val (layout, homeDir) = createFakeLayout()

        // Bind dummy server to port 8000 to simulate occupied port
        val dummySocket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 1)
        }
        val occupiedPort = dummySocket.localPort

        try {
            val stConfig = StConfig.fromYaml("port: $occupiedPort\n")
            val nodeConfig = NodeConfig()
            val prefs = FakeSharedPreferences()

            // 1. autoPortFallback = true selects an alternative port
            val appConfigWithFallback = AppConfig(prefs).apply { autoPortFallback = true }
            val specWithFallback = NodeController.buildLaunchSpec(layout, stConfig, nodeConfig, appConfigWithFallback, homeDir)

            assertNotEquals(occupiedPort, specWithFallback.effectivePort)
            assertTrue(specWithFallback.effectivePort in 1..65535)
            assertEquals(specWithFallback.effectivePort.toString(), specWithFallback.environment["SILLYTAVERN_PORT"])
            val portArgIndex = specWithFallback.command.indexOf("--port")
            assertEquals(specWithFallback.effectivePort.toString(), specWithFallback.command[portArgIndex + 1])
        } finally {
            dummySocket.close()
        }
    }
}
