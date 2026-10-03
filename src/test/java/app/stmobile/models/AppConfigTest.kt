package app.stmobile.models

import app.stmobile.testutils.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppConfigTest {

    @Test
    fun testDefaultValues() {
        val prefs = FakeSharedPreferences()
        val config = AppConfig(prefs)

        assertFalse("autoStartOnAppOpen default should be false", config.autoStartOnAppOpen)
        assertFalse("autoLaunchWebViewOnStart default should be false", config.autoLaunchWebViewOnStart)
        assertEquals("backgroundTimeoutMinutes default should be 5", 5, config.backgroundTimeoutMinutes)
        assertTrue("autoPortFallback default should be true", config.autoPortFallback)
        assertFalse("batteryPrompted default should be false", config.batteryPrompted)
        assertFalse("basicAuthEnabled default should be false", config.basicAuthEnabled)
        assertEquals("basicAuthUsername default should be 'user'", "user", config.basicAuthUsername)
        assertEquals("basicAuthPassword default should be 'password'", "password", config.basicAuthPassword)
        assertFalse("wasRunningBeforeKill default should be false", config.wasRunningBeforeKill)
    }

    @Test
    fun testMutationsAndPersistence() {
        val prefs = FakeSharedPreferences()
        val config = AppConfig(prefs)

        config.autoStartOnAppOpen = true
        config.autoLaunchWebViewOnStart = true
        config.backgroundTimeoutMinutes = 15
        config.autoPortFallback = false
        config.batteryPrompted = true
        config.basicAuthEnabled = true
        config.basicAuthUsername = "admin"
        config.basicAuthPassword = "customPassword123"
        config.wasRunningBeforeKill = true

        // Verify on instance
        assertTrue(config.autoStartOnAppOpen)
        assertTrue(config.autoLaunchWebViewOnStart)
        assertEquals(15, config.backgroundTimeoutMinutes)
        assertFalse(config.autoPortFallback)
        assertTrue(config.batteryPrompted)
        assertTrue(config.basicAuthEnabled)
        assertEquals("admin", config.basicAuthUsername)
        assertEquals("customPassword123", config.basicAuthPassword)
        assertTrue(config.wasRunningBeforeKill)

        // Verify across independent AppConfig instance on the same SharedPreferences
        val reloaded = AppConfig(prefs)
        assertTrue(reloaded.autoStartOnAppOpen)
        assertTrue(reloaded.autoLaunchWebViewOnStart)
        assertEquals(15, reloaded.backgroundTimeoutMinutes)
        assertFalse(reloaded.autoPortFallback)
        assertTrue(reloaded.batteryPrompted)
        assertTrue(reloaded.basicAuthEnabled)
        assertEquals("admin", reloaded.basicAuthUsername)
        assertEquals("customPassword123", reloaded.basicAuthPassword)
        assertTrue(reloaded.wasRunningBeforeKill)
    }

    @Test
    fun testSafeNumericalClampingForBackgroundTimeout() {
        val prefs = FakeSharedPreferences()
        val config = AppConfig(prefs)

        // Setting negative timeout clamps to 0
        config.backgroundTimeoutMinutes = -10
        assertEquals(0, config.backgroundTimeoutMinutes)
        assertEquals(0, prefs.getInt("background_timeout_minutes", -1))

        // Setting 0 remains 0 (disabled timeout)
        config.backgroundTimeoutMinutes = 0
        assertEquals(0, config.backgroundTimeoutMinutes)

        // Setting positive timeout retains value
        config.backgroundTimeoutMinutes = 30
        assertEquals(30, config.backgroundTimeoutMinutes)

        // Pre-existing negative value in prefs is read back clamped to 0
        prefs.edit().putInt("background_timeout_minutes", -99).apply()
        assertEquals(0, config.backgroundTimeoutMinutes)
    }
}
