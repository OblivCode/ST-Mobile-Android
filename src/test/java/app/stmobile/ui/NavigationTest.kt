package app.stmobile.ui

import app.stmobile.models.NodeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun testComputeNextScreen_autoLaunchOnRunning() {
        val next = computeNextScreen(
            currentScreen = ActiveScreen.DASHBOARD,
            nodeState = NodeState.RUNNING,
            autoLaunchWebViewOnStart = true,
        )
        assertEquals(ActiveScreen.WEBVIEW, next)
    }

    @Test
    fun testComputeNextScreen_disabledAutoLaunch() {
        val next = computeNextScreen(
            currentScreen = ActiveScreen.DASHBOARD,
            nodeState = NodeState.RUNNING,
            autoLaunchWebViewOnStart = false,
        )
        assertEquals(ActiveScreen.DASHBOARD, next)
    }

    @Test
    fun testComputeNextScreen_serverStoppedFromWebView() {
        val next = computeNextScreen(
            currentScreen = ActiveScreen.WEBVIEW,
            nodeState = NodeState.STOPPED,
            autoLaunchWebViewOnStart = true,
        )
        assertEquals(ActiveScreen.DASHBOARD, next)
    }

    @Test
    fun testComputeNextScreen_serverErrorFromWebView() {
        val next = computeNextScreen(
            currentScreen = ActiveScreen.WEBVIEW,
            nodeState = NodeState.ERROR,
            autoLaunchWebViewOnStart = true,
        )
        assertEquals(ActiveScreen.DASHBOARD, next)
    }

    @Test
    fun testComputeNextScreen_serverStartingFromWebView() {
        val next = computeNextScreen(
            currentScreen = ActiveScreen.WEBVIEW,
            nodeState = NodeState.STARTING,
            autoLaunchWebViewOnStart = true,
        )
        assertEquals(ActiveScreen.DASHBOARD, next)
    }

    @Test
    fun testComputeNextScreen_preservesSettingsOnRunning() {
        val next = computeNextScreen(
            currentScreen = ActiveScreen.SETTINGS,
            nodeState = NodeState.RUNNING,
            autoLaunchWebViewOnStart = true,
        )
        assertEquals(ActiveScreen.SETTINGS, next)
    }

    @Test
    fun testComputeNextScreen_preservesSetupOnRunning() {
        val next = computeNextScreen(
            currentScreen = ActiveScreen.SETUP,
            nodeState = NodeState.RUNNING,
            autoLaunchWebViewOnStart = true,
        )
        assertEquals(ActiveScreen.SETUP, next)
    }

    @Test
    fun testComputeNextScreen_restartFlapHandling() {
        // 1. Initial state: RUNNING with auto-launch puts user on WEBVIEW
        var current = ActiveScreen.DASHBOARD
        current = computeNextScreen(current, NodeState.RUNNING, autoLaunchWebViewOnStart = true)
        assertEquals(ActiveScreen.WEBVIEW, current)

        // 2. Server flaps: crashes to STOPPED, user falls back to DASHBOARD
        current = computeNextScreen(current, NodeState.STOPPED, autoLaunchWebViewOnStart = true)
        assertEquals(ActiveScreen.DASHBOARD, current)

        // 3. Server auto-restarts into STARTING: stays on DASHBOARD
        current = computeNextScreen(current, NodeState.STARTING, autoLaunchWebViewOnStart = true)
        assertEquals(ActiveScreen.DASHBOARD, current)

        // 4. Server recovers to RUNNING: auto-launches user back to WEBVIEW
        current = computeNextScreen(current, NodeState.RUNNING, autoLaunchWebViewOnStart = true)
        assertEquals(ActiveScreen.WEBVIEW, current)
    }

    // --- NavigationController Unit Tests ---

    @Test
    fun testNavController_quickToolbarDashboardThenBackReturnsToWebView() {
        val nav = NavigationController(initialScreen = ActiveScreen.WEBVIEW)
        nav.navigateTo(ActiveScreen.DASHBOARD)

        assertEquals(ActiveScreen.DASHBOARD, nav.currentScreen)
        assertEquals(listOf(ActiveScreen.WEBVIEW), nav.stack)
        assertTrue(nav.canGoBack(NodeState.RUNNING))

        nav.handleBack(NodeState.RUNNING)
        assertEquals(ActiveScreen.WEBVIEW, nav.currentScreen)
        assertTrue(nav.stack.isEmpty())
    }

    @Test
    fun testNavController_quickToolbarSettingsThenBackReturnsToWebView() {
        val nav = NavigationController(initialScreen = ActiveScreen.WEBVIEW)
        nav.navigateTo(ActiveScreen.SETTINGS)

        assertEquals(ActiveScreen.SETTINGS, nav.currentScreen)
        assertEquals(listOf(ActiveScreen.WEBVIEW), nav.stack)
        assertTrue(nav.canGoBack(NodeState.RUNNING))

        nav.handleBack(NodeState.RUNNING)
        assertEquals(ActiveScreen.WEBVIEW, nav.currentScreen)
        assertTrue(nav.stack.isEmpty())
    }

    @Test
    fun testNavController_fullBackStackMultiHop() {
        val nav = NavigationController(initialScreen = ActiveScreen.WEBVIEW)
        nav.navigateTo(ActiveScreen.DASHBOARD)
        nav.navigateTo(ActiveScreen.SETTINGS)

        assertEquals(ActiveScreen.SETTINGS, nav.currentScreen)
        assertEquals(listOf(ActiveScreen.WEBVIEW, ActiveScreen.DASHBOARD), nav.stack)

        // First Back: pops to Dashboard
        assertTrue(nav.canGoBack(NodeState.RUNNING))
        nav.handleBack(NodeState.RUNNING)
        assertEquals(ActiveScreen.DASHBOARD, nav.currentScreen)
        assertEquals(listOf(ActiveScreen.WEBVIEW), nav.stack)

        // Second Back: pops to WebView
        assertTrue(nav.canGoBack(NodeState.RUNNING))
        nav.handleBack(NodeState.RUNNING)
        assertEquals(ActiveScreen.WEBVIEW, nav.currentScreen)
        assertTrue(nav.stack.isEmpty())

        // At root WebView: canGoBack is false (native WebView/moveTaskToBack handles root back)
        assertFalse(nav.canGoBack(NodeState.RUNNING))
    }

    @Test
    fun testNavController_tabToggleDeduplication() {
        val nav = NavigationController(initialScreen = ActiveScreen.WEBVIEW)
        nav.navigateTo(ActiveScreen.DASHBOARD)
        nav.navigateTo(ActiveScreen.SETTINGS)
        // User clicks Dashboard bottom nav tab again
        nav.navigateTo(ActiveScreen.DASHBOARD)

        assertEquals(ActiveScreen.DASHBOARD, nav.currentScreen)
        // Stack unwinds to the prior occurrence instead of appending duplicates
        assertEquals(listOf(ActiveScreen.WEBVIEW), nav.stack)

        // Back directly returns to WebView
        nav.handleBack(NodeState.RUNNING)
        assertEquals(ActiveScreen.WEBVIEW, nav.currentScreen)
        assertTrue(nav.stack.isEmpty())
    }

    @Test
    fun testNavController_rootDashboardExits() {
        val nav = NavigationController(initialScreen = ActiveScreen.DASHBOARD)
        // Initial launch on Dashboard without WebView in history: canGoBack must be false so Back exits app
        assertFalse(nav.canGoBack(NodeState.RUNNING))
        assertFalse(nav.canGoBack(NodeState.STOPPED))
    }

    @Test
    fun testNavController_serverStoppedSkipsDeadWebView() {
        val nav = NavigationController(initialScreen = ActiveScreen.WEBVIEW)
        nav.navigateTo(ActiveScreen.SETTINGS)
        assertEquals(listOf(ActiveScreen.WEBVIEW), nav.stack)

        // Server stops while in Settings
        nav.onNodeStateChanged(NodeState.STOPPED, autoLaunchWebViewOnStart = true)
        // Dead WebView should be purged from backStack
        assertFalse(nav.stack.contains(ActiveScreen.WEBVIEW))

        // Back from Settings falls back to Dashboard when server stopped
        nav.handleBack(NodeState.STOPPED)
        assertEquals(ActiveScreen.DASHBOARD, nav.currentScreen)
    }

    @Test
    fun testNavController_snapshotSaveAndRestore() {
        val nav = NavigationController(initialScreen = ActiveScreen.WEBVIEW)
        nav.navigateTo(ActiveScreen.DASHBOARD)
        nav.navigateTo(ActiveScreen.SETTINGS)

        val snapshot = nav.toSnapshot()
        assertEquals(ActiveScreen.SETTINGS, snapshot.currentScreen)
        assertEquals(listOf(ActiveScreen.WEBVIEW, ActiveScreen.DASHBOARD), snapshot.backStack)

        val restored = NavigationController.fromSnapshot(snapshot)
        assertEquals(ActiveScreen.SETTINGS, restored.currentScreen)
        assertEquals(listOf(ActiveScreen.WEBVIEW, restored.stack.lastOrNull()).filterNotNull(), restored.stack)
    }

    @Test
    fun testNavController_exitWebViewToDashboardClearsStackAndExitsOnNextBack() {
        val nav = NavigationController(initialScreen = ActiveScreen.WEBVIEW)
        nav.exitWebViewToDashboard()

        assertEquals(ActiveScreen.DASHBOARD, nav.currentScreen)
        assertTrue(nav.stack.isEmpty())
        // canGoBack must be false so subsequent back press closes the app
        assertFalse(nav.canGoBack(NodeState.RUNNING))
    }

    @Test
    fun testNavController_exitWebViewToDashboard_multiHopThroughSettingsUnwindsToExit() {
        val nav = NavigationController(initialScreen = ActiveScreen.WEBVIEW)
        nav.exitWebViewToDashboard()

        assertEquals(ActiveScreen.DASHBOARD, nav.currentScreen)
        assertTrue(nav.stack.isEmpty())

        // Navigate to Settings from Dashboard
        nav.navigateTo(ActiveScreen.SETTINGS)
        assertEquals(ActiveScreen.SETTINGS, nav.currentScreen)
        assertEquals(listOf(ActiveScreen.DASHBOARD), nav.stack)
        assertTrue(nav.canGoBack(NodeState.RUNNING))

        // First Back: returns to Dashboard
        nav.handleBack(NodeState.RUNNING)
        assertEquals(ActiveScreen.DASHBOARD, nav.currentScreen)
        assertTrue(nav.stack.isEmpty())

        // Second Back: canGoBack must be false so subsequent back exits
        assertFalse(nav.canGoBack(NodeState.RUNNING))
    }

    @Test
    fun testNavController_exitWebViewToDashboard_reopenWebViewResetsLifecycle() {
        val nav = NavigationController(initialScreen = ActiveScreen.WEBVIEW)
        // 1. Back out to Dashboard
        nav.exitWebViewToDashboard()
        assertEquals(ActiveScreen.DASHBOARD, nav.currentScreen)
        assertFalse(nav.canGoBack(NodeState.RUNNING))

        // 2. User taps "Open SillyTavern"
        nav.navigateTo(ActiveScreen.WEBVIEW)
        assertEquals(ActiveScreen.WEBVIEW, nav.currentScreen)
        assertTrue(nav.stack.isEmpty())

        // 3. User navigates via Quick Toolbar to Dashboard
        nav.navigateTo(ActiveScreen.DASHBOARD)
        assertEquals(ActiveScreen.DASHBOARD, nav.currentScreen)
        assertEquals(listOf(ActiveScreen.WEBVIEW), nav.stack)
        assertTrue(nav.canGoBack(NodeState.RUNNING))

        // 4. Back returns to WebView
        nav.handleBack(NodeState.RUNNING)
        assertEquals(ActiveScreen.WEBVIEW, nav.currentScreen)
        assertTrue(nav.stack.isEmpty())

        // 5. System back in WebView exits to Dashboard again
        nav.exitWebViewToDashboard()
        assertEquals(ActiveScreen.DASHBOARD, nav.currentScreen)
        assertFalse(nav.canGoBack(NodeState.RUNNING))
    }
}
