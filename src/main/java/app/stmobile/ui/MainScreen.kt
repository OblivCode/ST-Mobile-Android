package app.stmobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import app.stmobile.models.NodeState
import app.stmobile.models.NodeStatus
import app.stmobile.payload.PayloadManager
import app.stmobile.payload.UpdateCheckResult
import app.stmobile.payload.UpdateInfo

@Composable
fun MainScreen(
    navController: NavigationController,
    status: NodeStatus,
    isInstalled: Boolean,
    installedInfo: PayloadManager.InstalledInfo,
    isInstalling: Boolean,
    installProgress: PayloadManager.ExtractProgress?,
    installErrorMessage: String?,
    isCheckingUpdates: Boolean,
    updateCheckResult: UpdateCheckResult?,
    onInstallPayload: () -> Unit,
    onCancelInstall: () -> Unit,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onExportLogs: () -> Unit,
    onPromptBattery: () -> Unit,
    onCheckForUpdates: () -> Unit,
    onUpdatePayload: (UpdateInfo) -> Unit,
    onReinstallPayload: () -> Unit,
    onImportArchive: () -> Unit = {},
    onCopyDiagnostics: () -> Unit = {},
) {
    // Intercept hardware Back to cancel installation if active
    BackHandler(enabled = isInstalling) {
        onCancelInstall()
    }

    BackHandler(enabled = !isInstalling && navController.canGoBack(status.state)) {
        navController.handleBack(status.state)
    }

    val saveableStateHolder = rememberSaveableStateHolder()

    Box(modifier = Modifier.fillMaxSize()) {
        // Layer 0: StWebView kept alive underneath with constant bounds (never subject to 80dp dock oscillations)
        if (status.state == NodeState.RUNNING) {
            StWebView(
                url = "http://127.0.0.1:${status.port}",
                visible = navController.currentScreen == ActiveScreen.WEBVIEW,
                onNavigateToDashboard = { navController.navigateTo(ActiveScreen.DASHBOARD) },
                onBackToDashboard = { navController.exitWebViewToDashboard() },
                onNavigateToSettings = { navController.navigateTo(ActiveScreen.SETTINGS) },
            )
        }

        // Layer 1: Foreground screens with Scaffold bottom dock
        if (navController.currentScreen != ActiveScreen.WEBVIEW) {
            Scaffold(
                bottomBar = {
                    BottomNavDock(
                        currentScreen = navController.currentScreen,
                        onSelectScreen = { navController.navigateTo(it) },
                        enabled = !isInstalling,
                    )
                },
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .consumeWindowInsets(padding),
                ) {
                    saveableStateHolder.SaveableStateProvider(navController.currentScreen) {
                        when (navController.currentScreen) {
                            ActiveScreen.DASHBOARD -> DashboardScreen(
                                status = status,
                                isInstalled = isInstalled,
                                isInstalling = isInstalling,
                                installProgress = installProgress,
                                installErrorMessage = installErrorMessage,
                                onInstallPayload = onInstallPayload,
                                onCancelInstall = onCancelInstall,
                                onStartServer = onStartServer,
                                onStopServer = onStopServer,
                                onOpenSillyTavern = { navController.navigateTo(ActiveScreen.WEBVIEW) },
                                onCopyDiagnostics = onCopyDiagnostics,
                            )
                            ActiveScreen.UPDATES -> UpdatesScreen(
                                installedInfo = installedInfo,
                                isInstalling = isInstalling,
                                installProgress = installProgress,
                                installErrorMessage = installErrorMessage,
                                isCheckingUpdates = isCheckingUpdates,
                                updateCheckResult = updateCheckResult,
                                onCheckForUpdates = onCheckForUpdates,
                                onUpdatePayload = onUpdatePayload,
                                onImportArchive = onImportArchive,
                                onReinstallPayload = onReinstallPayload,
                                onCancelInstall = onCancelInstall,
                            )
                            ActiveScreen.BACKUPS -> BackupsScreen(
                                onExportBackup = onExportBackup,
                                onImportBackup = onImportBackup,
                            )
                            ActiveScreen.SETTINGS -> SettingsScreen(
                                onPromptBattery = onPromptBattery,
                                onExportBackup = onExportBackup,
                                onImportBackup = onImportBackup,
                                onExportLogs = onExportLogs,
                                onCopyDiagnostics = onCopyDiagnostics,
                            )
                            ActiveScreen.WEBVIEW -> {
                                // Handled in Layer 0
                            }
                        }
                    }
                }
            }
        }
    }
}
