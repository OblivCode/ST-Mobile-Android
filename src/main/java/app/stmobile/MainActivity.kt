package app.stmobile

import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import app.stmobile.sillytavern.LocalArchivePayloadSource
import app.stmobile.sillytavern.PayloadManager
import app.stmobile.sillytavern.PayloadSource
import app.stmobile.ui.ActiveScreen
import app.stmobile.ui.ExportBackupDialog
import app.stmobile.ui.ImportPasswordDialog
import app.stmobile.ui.ImportStrategyDialog
import app.stmobile.ui.MainScreen
import app.stmobile.ui.NavigationController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Lean UI coordinator connecting lifecycle, reactive status collection, top-level screen routing,
 * and SAF backup contracts (Phase D, Phase F, Phase L.1).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var navController: NavigationController

    // Installation state for dynamic dashboard flow (Phase L.1)
    private var isInstalled by mutableStateOf(false)
    private var isInstalling by mutableStateOf(false)
    private var installProgress by mutableStateOf<PayloadManager.ExtractProgress?>(null)
    private var installErrorMessage by mutableStateOf<String?>(null)
    private var installJob: Job? = null

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
                    .onFailure { Utils.toast(this@MainActivity, getString(R.string.toast_export_failed, it.message)) }
                    .onSuccess { Utils.toast(this@MainActivity, getString(R.string.toast_export_success)) }
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
                        Utils.toast(this@MainActivity, getString(R.string.toast_invalid_backup, info.errorMessage ?: ""))
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

    private val payloadArchiveLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                val wasActive = NodeService.status.value.state !in setOf(NodeState.STOPPED, NodeState.ERROR)
                if (wasActive) {
                    NodeService.stop(this@MainActivity)
                    withTimeoutOrNull(3000) {
                        NodeService.status.first { it.state == NodeState.STOPPED }
                    }
                }
                withContext(Dispatchers.Main) {
                    navController.navigateTo(ActiveScreen.DASHBOARD)
                    installPayload(LocalArchivePayloadSource(uri, contentResolver))
                }
            }
        }
    }

    private val showExportDialog = mutableStateOf(false)
    private val showImportPasswordDialog = mutableStateOf(false)
    private val importPasswordError = mutableStateOf<String?>(null)
    private val showImportStrategyDialog = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val appConfig = AppConfig(this)
        val payloadManager = PayloadManager(this)
        isInstalled = payloadManager.isInstalled()
        navController = NavigationController.fromBundle(savedInstanceState, ActiveScreen.DASHBOARD)

        Utils.requestNotificationPermission(this)

        // Auto-resumes the server on-demand if auto-start is enabled OR if the OS killed the app process while running
        if (isInstalled && (appConfig.autoStartOnAppOpen || appConfig.wasRunningBeforeKill)) {
            NodeService.start(this)
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val nodeStatus by NodeService.status.collectAsState()

                LaunchedEffect(nodeStatus.state) {
                    navController.onNodeStateChanged(nodeStatus.state, appConfig.autoLaunchWebViewOnStart)
                }

                MainScreen(
                    navController = navController,
                    status = nodeStatus,
                    isInstalled = isInstalled,
                    isInstalling = isInstalling,
                    installProgress = installProgress,
                    installErrorMessage = installErrorMessage,
                    onInstallPayload = { installPayload() },
                    onCancelInstall = { cancelInstall() },
                    onStartServer = { NodeService.start(this@MainActivity) },
                    onStopServer = { NodeService.stop(this@MainActivity) },
                    onExportBackup = { showExportDialog.value = true },
                    onImportBackup = { importLauncher.launch(arrayOf("*/*")) },
                    onExportLogs = { Utils.shareLogs(this@MainActivity) },
                    onPromptBattery = { Utils.promptBatteryOptimization(this@MainActivity) },
                    onResetPayload = { resetPayload() },
                    onImportArchive = { payloadArchiveLauncher.launch(arrayOf("application/zip", "application/x-tar", "application/gzip", "*/*")) },
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
                                        importPasswordError.value = info.errorMessage ?: getString(R.string.error_incorrect_password)
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

    private fun installPayload(source: PayloadSource? = null) {
        if (isInstalling) return
        isInstalling = true
        installErrorMessage = null
        installProgress = null

        installJob = lifecycleScope.launch(Dispatchers.IO) {
            val payloadManager = PayloadManager(this@MainActivity)
            val effectiveSource = source ?: payloadManager.defaultPayloadSource()
            try {
                payloadManager.extract(
                    source = effectiveSource,
                    isCancelled = { !coroutineContext.isActive },
                    onProgress = { p ->
                        lifecycleScope.launch(Dispatchers.Main) {
                            installProgress = p
                        }
                    },
                )
                withContext(Dispatchers.Main) {
                    isInstalled = true
                    isInstalling = false
                    installProgress = null
                    if (source != null) {
                        Utils.toast(this@MainActivity, getString(R.string.toast_import_archive_success))
                    }
                    val appConfig = AppConfig(this@MainActivity)
                    if (appConfig.autoStartOnAppOpen) {
                        NodeService.start(this@MainActivity)
                    }
                }
            } catch (_: CancellationException) {
                withContext(Dispatchers.Main) {
                    isInstalling = false
                    installProgress = null
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    isInstalling = false
                    installErrorMessage = t.message ?: "Extraction failed"
                    if (source != null) {
                        Utils.toast(this@MainActivity, getString(R.string.toast_import_archive_failed, t.message ?: ""))
                    }
                }
            }
        }
    }

    private fun cancelInstall() {
        installJob?.cancel()
        installJob = null
        isInstalling = false
        installProgress = null
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
                .onFailure { Utils.toast(this@MainActivity, getString(R.string.toast_restore_failed, it.message)) }
                .onSuccess { count ->
                    Utils.toast(this@MainActivity, getString(R.string.toast_restore_success, count))
                    if (wasActive) {
                        NodeService.start(this@MainActivity)
                    } else if (!isInstalled) {
                        lifecycleScope.launch(Dispatchers.Main) {
                            navController.navigateTo(ActiveScreen.DASHBOARD)
                        }
                    }
                }
        }
    }

    private fun resetPayload() {
        lifecycleScope.launch(Dispatchers.IO) {
            val wasActive = NodeService.status.value.state !in setOf(NodeState.STOPPED, NodeState.ERROR)
            if (wasActive) {
                NodeService.stop(this@MainActivity)
                withTimeoutOrNull(3000) {
                    NodeService.status.first { it.state == NodeState.STOPPED }
                }
            }
            PayloadManager(this@MainActivity).resetPayload()
            withContext(Dispatchers.Main) {
                isInstalled = false
                installErrorMessage = null
                installProgress = null
                navController.navigateTo(ActiveScreen.DASHBOARD)
                Utils.toast(this@MainActivity, getString(R.string.toast_payload_reset))
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        navController.saveInstanceState(outState)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}
