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
        assertTrue("autoLaunchWebViewOnStart default should be true", config.autoLaunchWebViewOnStart)
        assertEquals("backgroundTimeoutMinutes default should be 5", 5, config.backgroundTimeoutMinutes)
        assertTrue("autoPortFallback default should be true", config.autoPortFallback)
        assertFalse("batteryPrompted default should be false", config.batteryPrompted)
        assertFalse("basicAuthEnabled default should be false", config.basicAuthEnabled)
        assertEquals("basicAuthUsername default should be 'user'", "user", config.basicAuthUsername)
        assertEquals("basicAuthPassword default should be 'password'", "password", config.basicAuthPassword)
        assertFalse("wasRunningBeforeKill default should be false", config.wasRunningBeforeKill)
        assertTrue("webViewMenuQuickToolbar default should be true", config.webViewMenuQuickToolbar)
    }

    @Test
    fun testMutationsAndPersistence() {
        val prefs = FakeSharedPreferences()
        val config = AppConfig(prefs)

        config.autoStartOnAppOpen = true
        config.autoLaunchWebViewOnStart = false
        config.backgroundTimeoutMinutes = 15
        config.autoPortFallback = false
        config.batteryPrompted = true
        config.basicAuthEnabled = true
        config.basicAuthUsername = "admin"
        config.basicAuthPassword = "customPassword123"
        config.wasRunningBeforeKill = true
        config.webViewMenuQuickToolbar = false

        // Verify on instance
        assertTrue(config.autoStartOnAppOpen)
        assertFalse(config.autoLaunchWebViewOnStart)
        assertEquals(15, config.backgroundTimeoutMinutes)
        assertFalse(config.autoPortFallback)
        assertTrue(config.batteryPrompted)
        assertTrue(config.basicAuthEnabled)
        assertEquals("admin", config.basicAuthUsername)
        assertEquals("customPassword123", config.basicAuthPassword)
        assertTrue(config.wasRunningBeforeKill)
        assertFalse(config.webViewMenuQuickToolbar)

        // Verify across independent AppConfig instance on the same SharedPreferences
        val reloaded = AppConfig(prefs)
        assertTrue(reloaded.autoStartOnAppOpen)
        assertFalse(reloaded.autoLaunchWebViewOnStart)
        assertEquals(15, reloaded.backgroundTimeoutMinutes)
        assertFalse(reloaded.autoPortFallback)
        assertTrue(reloaded.batteryPrompted)
        assertTrue(reloaded.basicAuthEnabled)
        assertEquals("admin", reloaded.basicAuthUsername)
        assertEquals("customPassword123", reloaded.basicAuthPassword)
        assertTrue(reloaded.wasRunningBeforeKill)
        assertFalse(reloaded.webViewMenuQuickToolbar)
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

    @Test
    fun testUpgradeSemantics_unwrittenKeysAdoptNewDefaults() {
        val prefs = FakeSharedPreferences()
        // Simulate an older installation where only legacy or partial keys exist
        prefs.edit()
            .putBoolean("auto_start_on_app_open", true)
            .putBoolean("battery_prompted", true)
            .apply()

        assertFalse("auto_launch_webview_on_start should be unwritten", prefs.contains("auto_launch_webview_on_start"))
        assertFalse("auto_port_fallback should be unwritten", prefs.contains("auto_port_fallback"))
        assertFalse("webview_menu_quick_toolbar should be unwritten", prefs.contains("webview_menu_quick_toolbar"))
        assertFalse("background_timeout_minutes should be unwritten", prefs.contains("background_timeout_minutes"))

        val config = AppConfig(prefs)

        // Pre-existing keys are preserved
        assertTrue(config.autoStartOnAppOpen)
        assertTrue(config.batteryPrompted)

        // Newly added keys adopt modern defaults
        assertTrue("Unwritten autoLaunchWebViewOnStart adopts true", config.autoLaunchWebViewOnStart)
        assertTrue("Unwritten autoPortFallback adopts true", config.autoPortFallback)
        assertTrue("Unwritten webViewMenuQuickToolbar adopts true", config.webViewMenuQuickToolbar)
        assertEquals("Unwritten backgroundTimeoutMinutes adopts 5", 5, config.backgroundTimeoutMinutes)
        assertEquals("Unwritten basicAuthUsername adopts 'user'", "user", config.basicAuthUsername)
        assertEquals("Unwritten basicAuthPassword adopts 'password'", "password", config.basicAuthPassword)
    }

    @Test
    fun testUpgradeSemantics_explicitUserOverridesPreserved() {
        val prefs = FakeSharedPreferences()
        // User explicitly turned off default-true features in an earlier session
        prefs.edit()
            .putBoolean("auto_launch_webview_on_start", false)
            .putBoolean("auto_port_fallback", false)
            .putBoolean("webview_menu_quick_toolbar", false)
            .putInt("background_timeout_minutes", 0)
            .putString("basic_auth_user", "custom_admin")
            .putString("basic_auth_pass", "secret_pass_123")
            .apply()

        val config = AppConfig(prefs)

        assertFalse("Explicit user override for autoLaunchWebViewOnStart preserved", config.autoLaunchWebViewOnStart)
        assertFalse("Explicit user override for autoPortFallback preserved", config.autoPortFallback)
        assertFalse("Explicit user override for webViewMenuQuickToolbar preserved", config.webViewMenuQuickToolbar)
        assertEquals("Explicit user override for backgroundTimeoutMinutes preserved", 0, config.backgroundTimeoutMinutes)
        assertEquals("Explicit user override for basicAuthUsername preserved", "custom_admin", config.basicAuthUsername)
        assertEquals("Explicit user override for basicAuthPassword preserved", "secret_pass_123", config.basicAuthPassword)
    }

    @Test
    fun testUpgradeSemantics_mutationOfUnwrittenKeyPersists() {
        val prefs = FakeSharedPreferences()
        val config = AppConfig(prefs)

        assertFalse(prefs.contains("webview_menu_quick_toolbar"))
        assertTrue(config.webViewMenuQuickToolbar)

        // Mutating from default writes to SharedPreferences
        config.webViewMenuQuickToolbar = false
        assertTrue(prefs.contains("webview_menu_quick_toolbar"))
        assertFalse(prefs.getBoolean("webview_menu_quick_toolbar", true))

        config.autoLaunchWebViewOnStart = false
        assertTrue(prefs.contains("auto_launch_webview_on_start"))
        assertFalse(prefs.getBoolean("auto_launch_webview_on_start", true))

        // Re-read from a separate instance
        val reloaded = AppConfig(prefs)
        assertFalse(reloaded.webViewMenuQuickToolbar)
        assertFalse(reloaded.autoLaunchWebViewOnStart)
    }
}
