package app.stmobile.logging

import app.stmobile.models.NodeState
import app.stmobile.models.NodeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DiagnosticsSnapshotTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testReadTailCircularBufferTruncation() {
        val file = tempFolder.newFile("sample.log")
        file.bufferedWriter().use { writer ->
            for (i in 1..100) {
                writer.write("Log line number $i\n")
            }
        }

        val tail = DiagnosticsSnapshot.readTail(file, maxLines = 15)
        assertEquals(15, tail.size)
        assertEquals("Log line number 86", tail.first())
        assertEquals("Log line number 100", tail.last())
    }

    @Test
    fun testReadAppLogTailWithFallbackMultiFile() {
        val logsDir = tempFolder.newFolder("logs")
        val appLog1 = File(logsDir, "app.log.1")
        val appLog = File(logsDir, "app.log")

        appLog1.bufferedWriter().use { writer ->
            for (i in 1..20) {
                writer.write("Old log line $i\n")
            }
        }

        appLog.bufferedWriter().use { writer ->
            for (i in 1..5) {
                writer.write("New log line $i\n")
            }
        }

        // Request 15 lines total: should take 10 from appLog1 and all 5 from appLog
        val tail = DiagnosticsSnapshot.readAppLogTailWithFallback(logsDir, maxLines = 15)
        assertEquals(15, tail.size)
        assertEquals("Old log line 11", tail.first())
        assertEquals("New log line 5", tail.last())
    }

    @Test
    fun testFormatProducesExpectedSnapshotText() {
        val devInfo = mapOf(
            "Model" to "MockPhone 9",
            "Android" to "14 (API 34)",
        )
        val status = NodeStatus(NodeState.RUNNING, "Running", 8080, 4321, isGenerating = true)

        val text = DiagnosticsSnapshot.format(
            appVersion = "0.3.0",
            flavor = "full",
            installedInfo = null,
            deviceInfo = devInfo,
            isBatteryExempt = true,
            status = status,
            appLogTail = listOf("line 1", "line 2"),
            nodeStderrTail = listOf("err 1"),
            crashLogTail = null,
        )

        assertTrue(text.contains("ST-MOBILE DIAGNOSTICS SNAPSHOT"))
        assertTrue(text.contains("App Version: 0.3.0 (Flavor: full)"))
        assertTrue(text.contains("Model: MockPhone 9"))
        assertTrue(text.contains("Battery Optimization Exempt: true"))
        assertTrue(text.contains("State: RUNNING (Port: 8080, PID: 4321)"))
        assertTrue(text.contains("Generating: true"))
        assertTrue(text.contains("[Log Tail: app.log (last 2 lines)]"))
        assertTrue(text.contains("line 1"))
        assertTrue(text.contains("line 2"))
        assertTrue(text.contains("[Log Tail: node_stderr.log (last 1 lines)]"))
        assertTrue(text.contains("err 1"))
    }

    @Test
    fun testFormatIsBoundedUnderBinderLimit() {
        val hugeLines = (1..2000).map { "A".repeat(200) }
        val devInfo = mapOf("Model" to "Test")
        val status = NodeStatus(NodeState.STOPPED, "Stopped", 8000)

        val formatted = DiagnosticsSnapshot.format(
            appVersion = "1",
            flavor = "full",
            installedInfo = null,
            deviceInfo = devInfo,
            isBatteryExempt = false,
            status = status,
            appLogTail = hugeLines,
            nodeStderrTail = emptyList(),
        )

        assertTrue("Formatted diagnostics must be <= MAX_CLIPBOARD_CHARS", formatted.length <= DiagnosticsSnapshot.MAX_CLIPBOARD_CHARS)
    }

    @Test
    fun testCreateLogBundleZipArchive() {
        val logsDir = tempFolder.newFolder("bundle_logs")
        File(logsDir, "app.log").writeText("Test app log content\n")
        File(logsDir, "node_stderr.log").writeText("Test node stderr content\n")
        File(logsDir, "crash.log").writeText("Test crash log content\n")

        val zipFile = DiagnosticsSnapshot.createLogBundle(
            logsDir = logsDir,
            appVersion = "0.3.0",
            flavor = "full",
        )

        assertTrue("Zip file must be created", zipFile.exists())
        assertEquals("stmobile_logs_bundle.zip", zipFile.name)

        // Read entries from the created ZIP
        val zipEntries = mutableListOf<String>()
        java.util.zip.ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                zipEntries.add(entry.name)
                entry = zis.nextEntry
            }
        }

        assertTrue("Zip must contain diagnostics.txt", zipEntries.contains("diagnostics.txt"))
        assertTrue("Zip must contain app.log", zipEntries.contains("app.log"))
        assertTrue("Zip must contain node_stderr.log", zipEntries.contains("node_stderr.log"))
        assertTrue("Zip must contain crash.log", zipEntries.contains("crash.log"))
        // Zip must NOT contain itself or .tmp
        assertTrue(!zipEntries.contains("stmobile_logs_bundle.zip"))
        assertTrue(!zipEntries.contains("stmobile_logs_bundle.tmp"))
    }
}
