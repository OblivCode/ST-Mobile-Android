package app.stmobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class Arm64RuntimeSmokeTest {

    @Test
    fun testArm64NodeBinaryExecutionAndDynamicLinking() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val nativeLibDir = targetContext.applicationInfo.nativeLibraryDir
        val launcher = File(nativeLibDir, "libstnode.so")

        assertTrue("libstnode.so must exist in nativeLibraryDir ($nativeLibDir)", launcher.exists())
        assertTrue("libstnode.so must be marked executable", launcher.canExecute())

        val pb = ProcessBuilder(
            launcher.absolutePath,
            "-e",
            "console.log('ARM64_SMOKE_OK:' + process.arch); console.log('NODE_VER:' + process.version);"
        )
        pb.environment()["LD_LIBRARY_PATH"] = nativeLibDir

        val process = pb.start()
        val stdout = process.inputStream.bufferedReader().use { it.readText() }
        val stderr = process.errorStream.bufferedReader().use { it.readText() }

        val exited = process.waitFor(5, TimeUnit.SECONDS)
        assertTrue("Node process must complete within 5 seconds", exited)
        assertEquals("Node process must exit with code 0 (stderr: $stderr)", 0, process.exitValue())

        assertTrue("Stdout must verify ARM64 architecture (got: $stdout)", stdout.contains("ARM64_SMOKE_OK:arm64"))
        assertTrue("Stdout must output valid Node version (got: $stdout)", stdout.contains("NODE_VER:v"))
    }
}
