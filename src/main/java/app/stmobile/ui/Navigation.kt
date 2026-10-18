package app.stmobile.ui

import android.os.Bundle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import app.stmobile.R
import app.stmobile.models.NodeState
import app.stmobile.node.NodeController

enum class ActiveScreen { DASHBOARD, UPDATES, BACKUPS, SETTINGS, WEBVIEW }

private val SECONDARY_DOCK_DESTINATIONS = setOf(
    ActiveScreen.UPDATES,
    ActiveScreen.BACKUPS,
    ActiveScreen.SETTINGS,
)

/**
 * Pure screen routing decision logic based on NodeService status.
 */
fun computeNextScreen(
    currentScreen: ActiveScreen,
    nodeState: NodeState,
    autoLaunchWebViewOnStart: Boolean,
): ActiveScreen {
    if (nodeState == NodeState.RUNNING && autoLaunchWebViewOnStart && currentScreen == ActiveScreen.DASHBOARD) {
        return ActiveScreen.WEBVIEW
    }
    if (nodeState != NodeState.RUNNING && currentScreen == ActiveScreen.WEBVIEW) {
        return ActiveScreen.DASHBOARD
    }
    return currentScreen
}

/**
 * Pure back navigation decision logic.
 */
fun computeBackScreen(
    currentScreen: ActiveScreen,
    previousScreen: ActiveScreen?,
    nodeState: NodeState,
): ActiveScreen {
    if (currentScreen in setOf(ActiveScreen.SETTINGS, ActiveScreen.UPDATES, ActiveScreen.BACKUPS)) {
        if (previousScreen == ActiveScreen.WEBVIEW && nodeState == NodeState.RUNNING) {
            return ActiveScreen.WEBVIEW
        }
        return ActiveScreen.DASHBOARD
    }
    return currentScreen
}

data class NavigationSnapshot(
    val currentScreen: ActiveScreen,
    val backStack: List<ActiveScreen>,
)

class NavigationController(
    initialScreen: ActiveScreen = ActiveScreen.DASHBOARD,
    initialStack: List<ActiveScreen> = emptyList(),
) {
    var currentScreen by mutableStateOf(initialScreen)
        internal set

    private val backStack = mutableStateListOf<ActiveScreen>().apply { addAll(initialStack) }

    val previousScreen: ActiveScreen?
        get() = backStack.lastOrNull()

    val stack: List<ActiveScreen>
        get() = backStack.toList()

    fun canGoBack(nodeState: NodeState = NodeState.STOPPED): Boolean {
        return when (currentScreen) {
            ActiveScreen.SETTINGS, ActiveScreen.UPDATES, ActiveScreen.BACKUPS -> true
            ActiveScreen.DASHBOARD -> backStack.contains(ActiveScreen.WEBVIEW) && nodeState == NodeState.RUNNING
            else -> false
        }
    }

    fun exitWebViewToDashboard() {
        backStack.clear()
        currentScreen = ActiveScreen.DASHBOARD
    }

    fun navigateTo(target: ActiveScreen) {
        if (currentScreen == target) return

        if (target == ActiveScreen.WEBVIEW) {
            backStack.clear()
            currentScreen = ActiveScreen.WEBVIEW
            return
        }

        val isCurrentSecondary = currentScreen in SECONDARY_DOCK_DESTINATIONS

        val existingIndex = backStack.lastIndexOf(target)
        if (existingIndex >= 0) {
            while (backStack.size > existingIndex) {
                backStack.removeAt(backStack.lastIndex)
            }
        } else if (!isCurrentSecondary) {
            backStack.add(currentScreen)
        }
        currentScreen = target
    }

    fun handleBack(nodeState: NodeState) {
        while (backStack.isNotEmpty()) {
            val candidate = backStack.removeAt(backStack.lastIndex)
            if (candidate == ActiveScreen.WEBVIEW && nodeState != NodeState.RUNNING) {
                continue
            }
            currentScreen = candidate
            return
        }
        if (currentScreen in setOf(ActiveScreen.SETTINGS, ActiveScreen.UPDATES, ActiveScreen.BACKUPS)) {
            currentScreen = ActiveScreen.DASHBOARD
        }
    }

    fun onNodeStateChanged(nodeState: NodeState, autoLaunchWebViewOnStart: Boolean) {
        if (nodeState != NodeState.RUNNING) {
            backStack.removeAll { it == ActiveScreen.WEBVIEW }
        }
        val next = computeNextScreen(currentScreen, nodeState, autoLaunchWebViewOnStart)
        if (next != currentScreen) {
            if (next == ActiveScreen.WEBVIEW || (currentScreen == ActiveScreen.WEBVIEW && next == ActiveScreen.DASHBOARD)) {
                backStack.clear()
            }
            currentScreen = next
        }
    }

    fun toSnapshot(): NavigationSnapshot = NavigationSnapshot(currentScreen, backStack.toList())

    fun saveInstanceState(outState: Bundle) {
        outState.putString(KEY_ACTIVE_SCREEN, currentScreen.name)
        outState.putStringArrayList(KEY_BACK_STACK, ArrayList(backStack.map { it.name }))
    }

    companion object {
        const val KEY_ACTIVE_SCREEN = "active_screen"
        const val KEY_BACK_STACK = "back_stack"
        const val KEY_PREVIOUS_SCREEN = "previous_screen"

        fun fromSnapshot(snapshot: NavigationSnapshot): NavigationController {
            return NavigationController(
                initialScreen = snapshot.currentScreen,
                initialStack = snapshot.backStack,
            )
        }

        fun fromBundle(
            savedState: Bundle?,
            defaultScreen: ActiveScreen = ActiveScreen.DASHBOARD,
            isServerRunning: Boolean = (NodeController.status.value.state == NodeState.RUNNING),
        ): NavigationController {
            val savedCurrent = savedState?.getString(KEY_ACTIVE_SCREEN)?.let { name ->
                runCatching { ActiveScreen.valueOf(name) }.getOrNull()
            }
            val effectiveScreen = if (savedCurrent == ActiveScreen.WEBVIEW && !isServerRunning) {
                defaultScreen
            } else {
                savedCurrent ?: defaultScreen
            }
            val savedStack = savedState?.getStringArrayList(KEY_BACK_STACK)?.mapNotNull { name ->
                runCatching { ActiveScreen.valueOf(name) }.getOrNull()
            } ?: savedState?.getString(KEY_PREVIOUS_SCREEN)?.let { prevName ->
                runCatching { ActiveScreen.valueOf(prevName) }.getOrNull()?.let { listOf(it) }
            } ?: emptyList()

            val effectiveStack = if (!isServerRunning) {
                savedStack.filter { it != ActiveScreen.WEBVIEW }
            } else {
                savedStack
            }

            return NavigationController(
                initialScreen = effectiveScreen,
                initialStack = effectiveStack,
            )
        }
    }
}

@Composable
fun BottomNavDock(
    currentScreen: ActiveScreen,
    onSelectScreen: (ActiveScreen) -> Unit,
    enabled: Boolean = true,
) {
    NavigationBar(
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainer,
        contentColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
    ) {
        val navItemColors = NavigationBarItemDefaults.colors(
            selectedIconColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
            selectedTextColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
            unselectedIconColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            indicatorColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh,
        )

        NavigationBarItem(
            selected = currentScreen == ActiveScreen.DASHBOARD,
            onClick = { if (enabled) onSelectScreen(ActiveScreen.DASHBOARD) },
            icon = { Icon(Icons.Default.Dashboard, contentDescription = stringResource(R.string.nav_dashboard)) },
            label = { Text(stringResource(R.string.nav_dashboard)) },
            colors = navItemColors,
            enabled = enabled,
        )
        NavigationBarItem(
            selected = currentScreen == ActiveScreen.UPDATES,
            onClick = { if (enabled) onSelectScreen(ActiveScreen.UPDATES) },
            icon = { Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.nav_updates)) },
            label = { Text(stringResource(R.string.nav_updates)) },
            colors = navItemColors,
            enabled = enabled,
        )
        NavigationBarItem(
            selected = currentScreen == ActiveScreen.BACKUPS,
            onClick = { if (enabled) onSelectScreen(ActiveScreen.BACKUPS) },
            icon = { Icon(Icons.Default.Backup, contentDescription = stringResource(R.string.nav_backups)) },
            label = { Text(stringResource(R.string.nav_backups)) },
            colors = navItemColors,
            enabled = enabled,
        )
        NavigationBarItem(
            selected = currentScreen == ActiveScreen.SETTINGS,
            onClick = { if (enabled) onSelectScreen(ActiveScreen.SETTINGS) },
            icon = { Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.nav_settings)) },
            label = { Text(stringResource(R.string.nav_settings)) },
            colors = navItemColors,
            enabled = enabled,
        )
    }
}
