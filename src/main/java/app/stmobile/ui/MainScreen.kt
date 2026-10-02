package app.stmobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.stmobile.models.NodeState
import app.stmobile.models.NodeStatus

@Composable
fun MainScreen(
    currentScreen: ActiveScreen,
    status: NodeStatus,
    onSelectScreen: (ActiveScreen) -> Unit,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onOpenSillyTavern: () -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onExportLogs: () -> Unit,
    onPromptBattery: () -> Unit,
    onResetPayload: () -> Unit,
    onSetupComplete: () -> Unit,
) {
    BackHandler(enabled = currentScreen == ActiveScreen.SETTINGS) {
        onSelectScreen(ActiveScreen.DASHBOARD)
    }

    Scaffold(
        bottomBar = {
            if (currentScreen == ActiveScreen.DASHBOARD || currentScreen == ActiveScreen.SETTINGS) {
                BottomNavDock(
                    currentScreen = currentScreen,
                    onSelectScreen = onSelectScreen,
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Layer 0: StWebView kept alive underneath when server is running
            if (currentScreen != ActiveScreen.SETUP && status.state == NodeState.RUNNING) {
                StWebView(
                    url = "http://127.0.0.1:${status.port}",
                    visible = currentScreen == ActiveScreen.WEBVIEW,
                    onNavigateToDashboard = { onSelectScreen(ActiveScreen.DASHBOARD) },
                )
            }

            // Layer 1: Foreground screens (opaque backgrounds absorb touches)
            when (currentScreen) {
                ActiveScreen.SETUP -> SetupScreen(onSetupComplete = onSetupComplete)
                ActiveScreen.DASHBOARD -> DashboardScreen(
                    status = status,
                    onStartServer = onStartServer,
                    onStopServer = onStopServer,
                    onOpenSillyTavern = onOpenSillyTavern,
                    onExportBackup = onExportBackup,
                    onImportBackup = onImportBackup,
                    onExportLogs = onExportLogs,
                )
                ActiveScreen.SETTINGS -> SettingsScreen(
                    onPromptBattery = onPromptBattery,
                    onResetPayload = onResetPayload,
                    onExportBackup = onExportBackup,
                    onImportBackup = onImportBackup,
                )
                ActiveScreen.WEBVIEW -> {
                    // StWebView is active and revealed underneath
                }
            }
        }
    }
}
