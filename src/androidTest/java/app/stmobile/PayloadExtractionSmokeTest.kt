package app.stmobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class PayloadExtractionSmokeTest {

    private lateinit var sandboxDir: File

    @Before
    fun setUp() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        sandboxDir = File(targetContext.cacheDir, "smoke_sandbox_extraction")
        if (sandboxDir.exists()) sandboxDir.deleteRecursively()
        sandboxDir.mkdirs()
    }

    @After
    fun tearDown() {
        if (::sandboxDir.isInitialized && sandboxDir.exists()) {
            sandboxDir.deleteRecursively()
        }
    }

    @Test
    fun testStreamingExtractionFromAssetManagerIntoAppStorage() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val assetManager = targetContext.assets

        // 1. Verify bundle asset opens cleanly
        assetManager.open("st_bundle.tar").use { rawStream ->
            val buffered = BufferedInputStream(rawStream, 1 shl 16)
            TarArchiveInputStream(buffered).use { tar ->
                var filesExtracted = 0
                while (true) {
                    val entry: TarArchiveEntry = tar.nextEntry ?: break
                    val name = entry.name.removePrefix("st/")
                    if (name.isBlank()) continue

                    val targetFile = File(sandboxDir, name)
                    // Security / ZipSlip check
                    val rootCanonical = sandboxDir.canonicalPath + File.separator
                    if (!targetFile.canonicalPath.startsWith(rootCanonical)) {
                        throw IllegalStateException("Unsafe archive path traversal: $name")
                    }

                    if (entry.isDirectory) {
                        targetFile.mkdirs()
                    } else {
                        targetFile.parentFile?.mkdirs()
                        FileOutputStream(targetFile).use { out -> tar.copyTo(out) }
                        if ((entry.mode and 0b001_000_000) != 0) {
                            targetFile.setExecutable(true, true)
                        }
                        filesExtracted++
                    }

                    // Extract first 150 files to verify streaming I/O without hammering device flash
                    if (filesExtracted >= 150) {
                        break
                    }
                }

                assertTrue("Must stream entries without Android permissions/SELinux error", filesExtracted >= 100)
            }
        }
    }
}
