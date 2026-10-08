package app.stmobile.ui

import android.os.Bundle
import app.stmobile.models.NodeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationTest {

    @Test
    fun testActiveScreenEnumValues() {
        val screens = ActiveScreen.values().map { it.name }
        assertEquals(5, screens.size)
        assertFalse(screens.contains("SETUP"))
        assertTrue(screens.contains("DASHBOARD"))
        assertTrue(screens.contains("UPDATES"))
        assertTrue(screens.contains("BACKUPS"))
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
    fun testComputeNextScreen_preservesSecondaryScreensOnRunning() {
        assertEquals(
            ActiveScreen.SETTINGS,
            computeNextScreen(ActiveScreen.SETTINGS, NodeState.RUNNING, autoLaunchWebViewOnStart = true)
        )
        assertEquals(
            ActiveScreen.UPDATES,
            computeNextScreen(ActiveScreen.UPDATES, NodeState.RUNNING, autoLaunchWebViewOnStart = true)
        )
        assertEquals(
            ActiveScreen.BACKUPS,
            computeNextScreen(ActiveScreen.BACKUPS, NodeState.RUNNING, autoLaunchWebViewOnStart = true)
        )
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
    fun testNavController_emptyStackFallbackFromSecondaryScreensReturnsToDashboard() {
        for (screen in listOf(ActiveScreen.SETTINGS, ActiveScreen.UPDATES, ActiveScreen.BACKUPS)) {
            val nav = NavigationController(initialScreen = screen)
            assertTrue(nav.canGoBack(NodeState.STOPPED))
            nav.handleBack(NodeState.STOPPED)
            assertEquals(ActiveScreen.DASHBOARD, nav.currentScreen)
        }
    }

    @Test
    fun testNavController_quickToolbarReturnsToWebView() {
        for (screen in listOf(ActiveScreen.DASHBOARD, ActiveScreen.SETTINGS, ActiveScreen.UPDATES, ActiveScreen.BACKUPS)) {
            val nav = NavigationController(initialScreen = ActiveScreen.WEBVIEW)
            nav.navigateTo(screen)

            assertEquals(screen, nav.currentScreen)
            assertEquals(listOf(ActiveScreen.WEBVIEW), nav.stack)
            assertTrue(nav.canGoBack(NodeState.RUNNING))

            nav.handleBack(NodeState.RUNNING)
            assertEquals(ActiveScreen.WEBVIEW, nav.currentScreen)
            assertTrue(nav.stack.isEmpty())
        }
    }

    @Test
    fun testNavController_fourTabNavigationAndUnwind() {
        val nav = NavigationController(initialScreen = ActiveScreen.WEBVIEW)
        nav.navigateTo(ActiveScreen.DASHBOARD)
        nav.navigateTo(ActiveScreen.UPDATES)
        nav.navigateTo(ActiveScreen.BACKUPS)
        nav.navigateTo(ActiveScreen.SETTINGS)

        assertEquals(ActiveScreen.SETTINGS, nav.currentScreen)
        // Secondary dock tabs do not accumulate linear history
        assertEquals(
            listOf(ActiveScreen.WEBVIEW, ActiveScreen.DASHBOARD),
            nav.stack,
        )

        // 1. Back pops directly to DASHBOARD
        assertTrue(nav.canGoBack(NodeState.RUNNING))
        nav.handleBack(NodeState.RUNNING)
        assertEquals(ActiveScreen.DASHBOARD, nav.currentScreen)
        assertEquals(listOf(ActiveScreen.WEBVIEW), nav.stack)

        // 2. Back pops to WEBVIEW
        assertTrue(nav.canGoBack(NodeState.RUNNING))
        nav.handleBack(NodeState.RUNNING)
        assertEquals(ActiveScreen.WEBVIEW, nav.currentScreen)
        assertTrue(nav.stack.isEmpty())

        // At root WEBVIEW: canGoBack is false
        assertFalse(nav.canGoBack(NodeState.RUNNING))
    }

    @Test
    fun testNavController_tabToggleDeduplication() {
        val nav = NavigationController(initialScreen = ActiveScreen.WEBVIEW)
        nav.navigateTo(ActiveScreen.DASHBOARD)
        nav.navigateTo(ActiveScreen.UPDATES)
        nav.navigateTo(ActiveScreen.SETTINGS)
        // User clicks Updates bottom nav tab again
        nav.navigateTo(ActiveScreen.UPDATES)

        assertEquals(ActiveScreen.UPDATES, nav.currentScreen)
        // Secondary tabs do not accumulate on stack
        assertEquals(listOf(ActiveScreen.WEBVIEW, ActiveScreen.DASHBOARD), nav.stack)

        // Back directly returns to Dashboard
        nav.handleBack(NodeState.RUNNING)
        assertEquals(ActiveScreen.DASHBOARD, nav.currentScreen)
        assertEquals(listOf(ActiveScreen.WEBVIEW), nav.stack)

        // Further back returns to WebView
        nav.handleBack(NodeState.RUNNING)
        assertEquals(ActiveScreen.WEBVIEW, nav.currentScreen)
        assertTrue(nav.stack.isEmpty())
    }

    @Test
    fun testNavController_tabSwitchingFromDashboardRoot_unwindsDirectly() {
        val nav = NavigationController(initialScreen = ActiveScreen.DASHBOARD)
        nav.navigateTo(ActiveScreen.UPDATES)
        nav.navigateTo(ActiveScreen.BACKUPS)
        nav.navigateTo(ActiveScreen.SETTINGS)

        assertEquals(ActiveScreen.SETTINGS, nav.currentScreen)
        assertEquals(listOf(ActiveScreen.DASHBOARD), nav.stack)

        // Back from secondary tab returns directly to DASHBOARD in single press
        assertTrue(nav.canGoBack(NodeState.RUNNING))
        nav.handleBack(NodeState.RUNNING)
        assertEquals(ActiveScreen.DASHBOARD, nav.currentScreen)
        assertTrue(nav.stack.isEmpty())

        // At root DASHBOARD: cannot go back further (exits app)
        assertFalse(nav.canGoBack(NodeState.RUNNING))
    }

    @Test
    fun testNavController_tabSwitchingDirectFromWebViewQuickToolbar() {
        val nav = NavigationController(initialScreen = ActiveScreen.WEBVIEW)
        // Direct entry to Settings from WebView quick toolbar
        nav.navigateTo(ActiveScreen.SETTINGS)
        assertEquals(ActiveScreen.SETTINGS, nav.currentScreen)
        assertEquals(listOf(ActiveScreen.WEBVIEW), nav.stack)

        // Tab switches across dock secondary destinations
        nav.navigateTo(ActiveScreen.BACKUPS)
        nav.navigateTo(ActiveScreen.UPDATES)
        assertEquals(ActiveScreen.UPDATES, nav.currentScreen)
        assertEquals(listOf(ActiveScreen.WEBVIEW), nav.stack)

        // Back directly returns to WEBVIEW
        assertTrue(nav.canGoBack(NodeState.RUNNING))
        nav.handleBack(NodeState.RUNNING)
        assertEquals(ActiveScreen.WEBVIEW, nav.currentScreen)
        assertTrue(nav.stack.isEmpty())
        assertFalse(nav.canGoBack(NodeState.RUNNING))
    }

    @Test
    fun testNavController_dockTabSelectionDashboardUnwindsBackStack() {
        val nav = NavigationController(initialScreen = ActiveScreen.DASHBOARD)
        nav.navigateTo(ActiveScreen.UPDATES)
        assertEquals(listOf(ActiveScreen.DASHBOARD), nav.stack)

        // Selecting Dashboard dock tab returns to Dashboard and clears stack
        nav.navigateTo(ActiveScreen.DASHBOARD)
        assertEquals(ActiveScreen.DASHBOARD, nav.currentScreen)
        assertTrue(nav.stack.isEmpty())
        assertFalse(nav.canGoBack(NodeState.RUNNING))
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
        nav.navigateTo(ActiveScreen.BACKUPS)

        val snapshot = nav.toSnapshot()
        assertEquals(ActiveScreen.BACKUPS, snapshot.currentScreen)
        assertEquals(listOf(ActiveScreen.WEBVIEW, ActiveScreen.DASHBOARD), snapshot.backStack)

        val restored = NavigationController.fromSnapshot(snapshot)
        assertEquals(ActiveScreen.BACKUPS, restored.currentScreen)
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
