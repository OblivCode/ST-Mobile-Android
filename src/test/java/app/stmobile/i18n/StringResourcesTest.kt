package app.stmobile.i18n

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class StringResourcesTest {

    private fun findResDir(): File {
        val candidates = listOf(
            File("src/main/res"),
            File("app-native/src/main/res"),
            File("../app-native/src/main/res")
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Could not locate src/main/res directory. Cwd is ${File(".").absolutePath}")
    }

    private fun extractStringKeys(file: File): Set<String> {
        val db = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val doc = db.parse(file)
        val stringNodes = doc.getElementsByTagName("string")
        val keys = mutableSetOf<String>()
        for (i in 0 until stringNodes.length) {
            val element = stringNodes.item(i) as Element
            val name = element.getAttribute("name")
            if (name.isNotBlank()) {
                keys.add(name)
            }
        }
        return keys
    }

    @Test
    fun testBaseStringsCatalogIsValid() {
        val resDir = findResDir()
        val baseFile = File(resDir, "values/strings.xml")
        assertTrue("Base strings.xml must exist", baseFile.exists())

        val keys = extractStringKeys(baseFile)
        assertTrue("Base strings.xml should contain at least 40 string keys", keys.size >= 40)
        assertTrue("Must define app_name", keys.contains("app_name"))
        assertTrue("Must define tab_app", keys.contains("tab_app"))
        assertTrue("Must define settings_auto_sync_lang", keys.contains("settings_auto_sync_lang"))
    }

    @Test
    fun testAllLocalizedBucketsHaveFullParityWithBase() {
        val resDir = findResDir()
        val baseFile = File(resDir, "values/strings.xml")
        val baseKeys = extractStringKeys(baseFile)

        val localizedBuckets = listOf(
            "values-zh",
            "values-ru",
            "values-es",
            "values-de",
            "values-ja",
        )

        for (bucket in localizedBuckets) {
            val localizedFile = File(resDir, "$bucket/strings.xml")
            assertTrue("Localized file $bucket/strings.xml must exist", localizedFile.exists())

            val localizedKeys = extractStringKeys(localizedFile)
            val missingKeys = baseKeys - localizedKeys

            assertTrue(
                "Localized bucket '$bucket' is missing ${missingKeys.size} keys from base strings.xml: $missingKeys",
                missingKeys.isEmpty()
            )
        }
    }
}
