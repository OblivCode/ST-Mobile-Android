package app.stmobile.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class ActiveScreen { SETUP, DASHBOARD, SETTINGS, WEBVIEW }

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
