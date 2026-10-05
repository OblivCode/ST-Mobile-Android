package app.stmobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.stmobile.models.NodeState
import app.stmobile.models.NodeStatus

@Composable
fun MainScreen(
    navController: NavigationController,
    status: NodeStatus,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onExportLogs: () -> Unit,
    onPromptBattery: () -> Unit,
    onResetPayload: () -> Unit,
    onSetupComplete: () -> Unit,
) {
    BackHandler(enabled = navController.canGoBack(status.state)) {
        navController.handleBack(status.state)
    }

    Scaffold(
        bottomBar = {
            if (navController.currentScreen == ActiveScreen.DASHBOARD || navController.currentScreen == ActiveScreen.SETTINGS) {
                BottomNavDock(
                    currentScreen = navController.currentScreen,
                    onSelectScreen = { navController.navigateTo(it) },
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            // Layer 0: StWebView kept alive underneath when server is running
            if (navController.currentScreen != ActiveScreen.SETUP && status.state == NodeState.RUNNING) {
                StWebView(
                    url = "http://127.0.0.1:${status.port}",
                    visible = navController.currentScreen == ActiveScreen.WEBVIEW,
                    onNavigateToDashboard = { navController.navigateTo(ActiveScreen.DASHBOARD) },
                    onBackToDashboard = { navController.exitWebViewToDashboard() },
                    onNavigateToSettings = { navController.navigateTo(ActiveScreen.SETTINGS) },
                )
            }

            // Layer 1: Foreground screens (opaque backgrounds absorb touches)
            when (navController.currentScreen) {
                ActiveScreen.SETUP -> SetupScreen(onSetupComplete = onSetupComplete)
                ActiveScreen.DASHBOARD -> DashboardScreen(
                    status = status,
                    onStartServer = onStartServer,
                    onStopServer = onStopServer,
                    onOpenSillyTavern = { navController.navigateTo(ActiveScreen.WEBVIEW) },
                )
                ActiveScreen.SETTINGS -> SettingsScreen(
                    onPromptBattery = onPromptBattery,
                    onResetPayload = onResetPayload,
                    onExportBackup = onExportBackup,
                    onImportBackup = onImportBackup,
                    onExportLogs = onExportLogs,
                )
                ActiveScreen.WEBVIEW -> {
                    // StWebView is active and revealed underneath
                }
            }
        }
    }
}
