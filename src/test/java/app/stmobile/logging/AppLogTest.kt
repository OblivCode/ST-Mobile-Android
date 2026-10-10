package app.stmobile.logging

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AppLogTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var logsDir: File

    @Before
    fun setUp() {
        logsDir = tempFolder.newFolder("logs")
        AppLog.init(logsDir)
    }

    @After
    fun tearDown() {
        AppLog.resetForTesting()
    }

    @Test
    fun testLogWriteAndFormatting() {
        AppLog.i("TestTag", "Hello world from test")
        AppLog.flush()

        val logFile = File(logsDir, "app.log")
        assertTrue("app.log must exist", logFile.exists())

        val content = logFile.readText()
        assertTrue("Log entry must contain tag", content.contains("[I] [TestTag]"))
        assertTrue("Log entry must contain message", content.contains("Hello world from test"))
        // Format check: YYYY-MM-DD
        assertTrue("Log entry must start with formatted date", content.matches(Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}.*", RegexOption.DOT_MATCHES_ALL)))
    }

    @Test
    fun testLogLevelCharacters() {
        AppLog.v("TagV", "Verbose msg")
        AppLog.d("TagD", "Debug msg")
        AppLog.i("TagI", "Info msg")
        AppLog.w("TagW", "Warn msg")
        AppLog.e("TagE", "Error msg")
        AppLog.flush()

        val content = File(logsDir, "app.log").readText()
        assertTrue(content.contains("[V] [TagV] Verbose msg"))
        assertTrue(content.contains("[D] [TagD] Debug msg"))
        assertTrue(content.contains("[I] [TagI] Info msg"))
        assertTrue(content.contains("[W] [TagW] Warn msg"))
        assertTrue(content.contains("[E] [TagE] Error msg"))
    }

    @Test
    fun testLogWithThrowableAppendsStackTrace() {
        val ex = IllegalStateException("Sample error")
        AppLog.e("ErrorTag", "Failed operation", ex)
        AppLog.flush()

        val content = File(logsDir, "app.log").readText()
        assertTrue(content.contains("[E] [ErrorTag] Failed operation"))
        assertTrue(content.contains("java.lang.IllegalStateException: Sample error"))
        assertTrue(content.contains("at app.stmobile.logging.AppLogTest"))
    }

    @Test
    fun testLogRotationAtThreshold() {
        // Set low threshold of 250 bytes to test rotation instantly without disk bloat
        AppLog.init(logsDir, maxBytes = 250L)

        // Write enough data to cross 250 bytes
        for (i in 1..10) {
            AppLog.i("RotTag", "Batch message line index $i - padding extra text to exceed bytes")
        }
        AppLog.flush()

        val appLog = File(logsDir, "app.log")
        val appLog1 = File(logsDir, "app.log.1")

        assertTrue("app.log must exist after rotation", appLog.exists())
        assertTrue("app.log.1 must exist after exceeding threshold", appLog1.exists())
        assertTrue("app.log.1 should contain bytes", appLog1.length() > 0)
    }

    @Test
    fun testLogcatSinkDispatching() {
        val dispatched = mutableListOf<String>()
        AppLog.init(logsDir) { level, tag, msg, _ ->
            dispatched.add("$level:$tag:$msg")
        }

        AppLog.d("CustomTag", "Custom message")
        AppLog.flush()

        assertEquals(1, dispatched.size)
        assertEquals("3:CustomTag:Custom message", dispatched[0])
    }
}
