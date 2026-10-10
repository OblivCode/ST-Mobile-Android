package app.stmobile.logging

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class CrashHandlerTest {

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
    fun testFormatCrashReportContainsExpectedMetadata() {
        val thread = Thread.currentThread()
        val exception = RuntimeException("Simulated fatal test crash")

        val report = CrashHandler.formatCrashReport(
            thread = thread,
            throwable = exception,
            appVersion = "1.2.3",
            manufacturer = "MockMaker",
            model = "MockPhone Pro",
            device = "mock_device",
            osRelease = "15",
            sdkInt = 35,
            supportedAbis = "arm64-v8a",
            nodeState = "RUNNING",
            nodePort = 8088,
        )

        assertTrue(report.contains("ST-MOBILE FATAL CRASH REPORT"))
        assertTrue(report.contains("App Version: 1.2.3"))
        assertTrue(report.contains("MockMaker MockPhone Pro"))
        assertTrue(report.contains("Android: 15 (API 35)"))
        assertTrue(report.contains("Node Status: RUNNING (Port: 8088)"))
        assertTrue(report.contains("RuntimeException: Simulated fatal test crash"))
        assertTrue(report.contains("at app.stmobile.logging.CrashHandlerTest"))
    }

    @Test
    fun testUncaughtExceptionWritesCrashFileAndDelegates() {
        val delegateCount = AtomicInteger(0)
        val defaultHandler = Thread.UncaughtExceptionHandler { _, _ ->
            delegateCount.incrementAndGet()
        }

        val handler = CrashHandler(
            logsDir = logsDir,
            appVersion = "0.3.0",
            defaultHandler = defaultHandler,
        )

        val testException = NullPointerException("NPE test trigger")
        handler.uncaughtException(Thread.currentThread(), testException)

        val crashFile = File(logsDir, "crash.log")
        assertTrue("crash.log must be created", crashFile.exists())
        val text = crashFile.readText()
        assertTrue(text.contains("NullPointerException: NPE test trigger"))
        assertEquals("Must delegate to chained handler once", 1, delegateCount.get())

        // AppLog should also have recorded the fatal crash
        AppLog.flush()
        val appLogFile = File(logsDir, "app.log")
        assertTrue(appLogFile.exists())
        assertTrue(appLogFile.readText().contains("FATAL CRASH on thread"))
    }
}
