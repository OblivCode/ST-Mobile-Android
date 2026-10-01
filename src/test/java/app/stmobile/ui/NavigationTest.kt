package app.stmobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationTest {

    @Test
    fun testActiveScreenEnumValues() {
        val screens = ActiveScreen.values().map { it.name }
        assertEquals(4, screens.size)
        assertTrue(screens.contains("SETUP"))
        assertTrue(screens.contains("DASHBOARD"))
        assertTrue(screens.contains("SETTINGS"))
        assertTrue(screens.contains("WEBVIEW"))
    }
}
