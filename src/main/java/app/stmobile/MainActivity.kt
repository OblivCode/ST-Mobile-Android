package app.stmobile

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import app.stmobile.models.AppConfig
import app.stmobile.models.NodeState
import app.stmobile.node.NodeService
import app.stmobile.sillytavern.BackupManager
import app.stmobile.sillytavern.PayloadManager
import app.stmobile.ui.ActiveScreen
import app.stmobile.ui.ExportBackupDialog
import app.stmobile.ui.ImportPasswordDialog
import app.stmobile.ui.ImportStrategyDialog
import app.stmobile.ui.MainScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Lean UI coordinator connecting lifecycle, reactive status collection, top-level screen routing,
 * and SAF backup contracts (Phase D, Phase F).
 */
class MainActivity : ComponentActivity() {

    private val activeScreen = mutableStateOf(ActiveScreen.SETUP)

    // Pending state for export and import across SAF contracts
    private var pendingExportPassword by mutableStateOf<String?>(null)
    private var pendingImportUri by mutableStateOf<Uri?>(null)
    private var pendingImportPassword by mutableStateOf<String?>(null)
    private var pendingArchiveInfo by mutableStateOf<BackupManager.ArchiveInfo?>(null)

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            val password = pendingExportPassword
            pendingExportPassword = null
            lifecycleScope.launch(Dispatchers.IO) {
                BackupManager(this@MainActivity).export(uri, password) {}
                    .onFailure { Utils.toast(this@MainActivity, "Export failed: ${it.message}") }
                    .onSuccess { Utils.toast(this@MainActivity, "Backup exported") }
            }
        } else {
            pendingExportPassword = null
        }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                val info = BackupManager(this@MainActivity).inspect(uri)
                withContext(Dispatchers.Main) {
                    if (!info.isValid) {
                        Utils.toast(this@MainActivity, "Invalid backup: ${info.errorMessage}")
                    } else {
                        pendingImportUri = uri
                        pendingImportPassword = null
                        if (info.isEncrypted && !info.isUnlocked) {
                            showImportPasswordDialog.value = true
                        } else {
                            pendingArchiveInfo = info
                            showImportStrategyDialog.value = true
                        }
                    }
                }
            }
        }
    }

    private val showExportDialog = mutableStateOf(false)
    private val showImportPasswordDialog = mutableStateOf(false)
    private val importPasswordError = mutableStateOf<String?>(null)
    private val showImportStrategyDialog = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appConfig = AppConfig(this)
        val needsExtraction = PayloadManager(this).isExtractionNeeded()
        activeScreen.value = if (needsExtraction) ActiveScreen.SETUP else ActiveScreen.DASHBOARD

        Utils.requestNotificationPermission(this)

        // Auto-resumes the server on-demand if auto-start is enabled OR if the OS killed the app process while running
        if (!needsExtraction && (appConfig.autoStartOnAppOpen || appConfig.wasRunningBeforeKill)) {
            NodeService.start(this)
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val nodeStatus by NodeService.status.collectAsState()

                LaunchedEffect(nodeStatus.state) {
                    if (nodeStatus.state == NodeState.RUNNING &&
                        appConfig.autoLaunchWebViewOnStart &&
                        activeScreen.value == ActiveScreen.DASHBOARD
                    ) {
                        activeScreen.value = ActiveScreen.WEBVIEW
                    } else if (nodeStatus.state != NodeState.RUNNING && activeScreen.value == ActiveScreen.WEBVIEW) {
                        activeScreen.value = ActiveScreen.DASHBOARD
                    }
                }

                MainScreen(
                    currentScreen = activeScreen.value,
                    status = nodeStatus,
                    onSelectScreen = { activeScreen.value = it },
                    onStartServer = { NodeService.start(this@MainActivity) },
                    onStopServer = { NodeService.stop(this@MainActivity) },
                    onOpenSillyTavern = { activeScreen.value = ActiveScreen.WEBVIEW },
                    onExportBackup = { showExportDialog.value = true },
                    onImportBackup = { importLauncher.launch(arrayOf("*/*")) },
                    onExportLogs = { Utils.shareLogs(this@MainActivity) },
                    onPromptBattery = { Utils.promptBatteryOptimization(this@MainActivity) },
                    onResetPayload = { resetPayload() },
                    onSetupComplete = {
                        activeScreen.value = ActiveScreen.DASHBOARD
                        if (appConfig.autoStartOnAppOpen) NodeService.start(this@MainActivity)
                    },
                )

                // Dialog: Export with optional AES-256 password
                if (showExportDialog.value) {
                    ExportBackupDialog(
                        onDismiss = { showExportDialog.value = false },
                        onConfirm = { password ->
                            showExportDialog.value = false
                            pendingExportPassword = password
                            exportLauncher.launch("st-backup.zip")
                        },
                    )
                }

                // Dialog: Unlock encrypted archive
                if (showImportPasswordDialog.value && pendingImportUri != null) {
                    ImportPasswordDialog(
                        errorMessage = importPasswordError.value,
                        onDismiss = {
                            showImportPasswordDialog.value = false
                            pendingImportUri = null
                            importPasswordError.value = null
                        },
                        onConfirm = { enteredPassword ->
                            val uri = pendingImportUri ?: return@ImportPasswordDialog
                            lifecycleScope.launch(Dispatchers.IO) {
                                val info = BackupManager(this@MainActivity).inspect(uri, enteredPassword)
                                withContext(Dispatchers.Main) {
                                    if (!info.isUnlocked) {
                                        importPasswordError.value = info.errorMessage ?: "Incorrect password"
                                    } else {
                                        showImportPasswordDialog.value = false
                                        importPasswordError.value = null
                                        pendingImportPassword = enteredPassword
                                        pendingArchiveInfo = info
                                        showImportStrategyDialog.value = true
                                    }
                                }
                            }
                        },
                    )
                }

                // Dialog: Strategy confirmation (Clean Restore vs Merge)
                val archiveInfo = pendingArchiveInfo
                if (showImportStrategyDialog.value && pendingImportUri != null && archiveInfo != null) {
                    ImportStrategyDialog(
                        info = archiveInfo,
                        onDismiss = {
                            showImportStrategyDialog.value = false
                            pendingImportUri = null
                            pendingImportPassword = null
                            pendingArchiveInfo = null
                        },
                        onConfirm = { strategy ->
                            showImportStrategyDialog.value = false
                            executeRestore(strategy)
                        },
                    )
                }
            }
        }
    }

    private fun executeRestore(strategy: BackupManager.Strategy) {
        val uri = pendingImportUri ?: return
        val password = pendingImportPassword
        pendingImportUri = null
        pendingImportPassword = null
        pendingArchiveInfo = null

        lifecycleScope.launch(Dispatchers.IO) {
            val wasActive = NodeService.status.value.state !in setOf(NodeState.STOPPED, NodeState.ERROR)
            if (wasActive) {
                NodeService.stop(this@MainActivity)
                withTimeoutOrNull(5000) {
                    NodeService.status.first { it.state == NodeState.STOPPED }
                }
            }

            BackupManager(this@MainActivity).import(uri, password, strategy) {}
                .onFailure { Utils.toast(this@MainActivity, "Restore failed: ${it.message}") }
                .onSuccess { count ->
                    Utils.toast(this@MainActivity, "Restored $count files")
                    if (wasActive) {
                        NodeService.start(this@MainActivity)
                    }
                }
        }
    }

    private fun resetPayload() {
        lifecycleScope.launch(Dispatchers.IO) {
            NodeService.stop(this@MainActivity)
            withTimeoutOrNull(3000) {
                NodeService.status.first { it.state == NodeState.STOPPED }
            }
            PayloadManager(this@MainActivity).resetPayload()
            withContext(Dispatchers.Main) {
                activeScreen.value = ActiveScreen.SETUP
                Utils.toast(this@MainActivity, "SillyTavern payload reset")
            }
        }
    }
}