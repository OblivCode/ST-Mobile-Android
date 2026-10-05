package app.stmobile.ui

import android.os.Bundle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
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
import app.stmobile.models.NodeState

enum class ActiveScreen { SETUP, DASHBOARD, SETTINGS, WEBVIEW }

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
    if (currentScreen == ActiveScreen.SETTINGS) {
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
    initialScreen: ActiveScreen = ActiveScreen.SETUP,
    initialStack: List<ActiveScreen> = emptyList(),
) {
    var currentScreen by mutableStateOf(initialScreen)
        internal set

    private val backStack = mutableStateListOf<ActiveScreen>().apply { addAll(initialStack) }

    val previousScreen: ActiveScreen?
        get() = backStack.lastOrNull()

    val stack: List<ActiveScreen>
        get() = backStack.toList()

    fun canGoBack(nodeState: NodeState): Boolean {
        return when (currentScreen) {
            ActiveScreen.SETTINGS -> true
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

        if (currentScreen == ActiveScreen.SETUP) {
            backStack.clear()
            currentScreen = target
            return
        }

        val existingIndex = backStack.lastIndexOf(target)
        if (existingIndex >= 0) {
            while (backStack.size > existingIndex) {
                backStack.removeAt(backStack.lastIndex)
            }
        } else {
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
        if (currentScreen == ActiveScreen.SETTINGS) {
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
            defaultScreen: ActiveScreen,
        ): NavigationController {
            val savedCurrent = savedState?.getString(KEY_ACTIVE_SCREEN)?.let { name ->
                runCatching { ActiveScreen.valueOf(name) }.getOrNull()
            }
            val savedStack = savedState?.getStringArrayList(KEY_BACK_STACK)?.mapNotNull { name ->
                runCatching { ActiveScreen.valueOf(name) }.getOrNull()
            } ?: savedState?.getString(KEY_PREVIOUS_SCREEN)?.let { prevName ->
                runCatching { ActiveScreen.valueOf(prevName) }.getOrNull()?.let { listOf(it) }
            } ?: emptyList()

            return NavigationController(
                initialScreen = savedCurrent ?: defaultScreen,
                initialStack = savedStack,
            )
        }
    }
}

@Composable
fun BottomNavDock(
    currentScreen: ActiveScreen,
    onSelectScreen: (ActiveScreen) -> Unit,
) {
    NavigationBar(
        containerColor = Color(0xFF1A2228),
        contentColor = Color(0xFF7EC8A9),
    ) {
        NavigationBarItem(
            selected = currentScreen == ActiveScreen.DASHBOARD,
            onClick = { onSelectScreen(ActiveScreen.DASHBOARD) },
            icon = { Icon(Icons.Default.Dashboard, contentDescription = "Dashboard") },
            label = { Text("Dashboard") },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color(0xFF7EC8A9),
                selectedTextColor = Color(0xFF7EC8A9),
                unselectedIconColor = Color(0xFF8A94A3),
                unselectedTextColor = Color(0xFF8A94A3),
                indicatorColor = Color(0xFF23303A),
            ),
        )
        NavigationBarItem(
            selected = currentScreen == ActiveScreen.SETTINGS,
            onClick = { onSelectScreen(ActiveScreen.SETTINGS) },
            icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
            label = { Text("Settings") },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color(0xFF7EC8A9),
                selectedTextColor = Color(0xFF7EC8A9),
                unselectedIconColor = Color(0xFF8A94A3),
                unselectedTextColor = Color(0xFF8A94A3),
                indicatorColor = Color(0xFF23303A),
            ),
        )
    }
}
