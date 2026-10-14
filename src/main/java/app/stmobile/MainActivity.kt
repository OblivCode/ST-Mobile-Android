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
import app.stmobile.backup.BackupManager
import app.stmobile.logging.DiagnosticsSnapshot
import app.stmobile.payload.LocalArchivePayloadSource
import app.stmobile.payload.PayloadManager
import app.stmobile.payload.PayloadSource
import app.stmobile.payload.RemotePayloadSource
import app.stmobile.payload.UpdateChecker
import app.stmobile.payload.UpdateCheckResult
import app.stmobile.payload.UpdateInfo
import app.stmobile.foreground.ForegroundService
import app.stmobile.node.NodeController
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

import androidx.compose.ui.graphics.Color

val StDarkColorScheme = darkColorScheme(
    primary = Color(0xFF7EC8A9),
    surface = Color(0xFF101418),
    surfaceContainerHigh = Color(0xFF23303A),
    surfaceContainer = Color(0xFF1A232A),
    onSurface = Color(0xFFD8DEE6),
    onSurfaceVariant = Color(0xFF8A94A3),
    error = Color(0xFFEF5350),
    onError = Color(0xFF101418),
)

/**
 * Lean UI coordinator connecting lifecycle, reactive status collection, top-level screen routing,
 * SAF backup contracts, and in-app OTA lifecycle management.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var navController: NavigationController

    // Installation and payload inspection state
    private var installedInfo by mutableStateOf(PayloadManager.InstalledInfo(isInstalled = false))
    private var isInstalling by mutableStateOf(false)
    private var installProgress by mutableStateOf<PayloadManager.ExtractProgress?>(null)
    private var installErrorMessage by mutableStateOf<String?>(null)
    private var installJob: Job? = null

    // OTA update checking state
    private var isCheckingUpdates by mutableStateOf(false)
    private var updateCheckResult by mutableStateOf<UpdateCheckResult?>(null)

    // Pending state for export and import across SAF contracts
    private var pendingExportPassword by mutableStateOf<String?>(null)
    private var pendingImportUri by mutableStateOf<Uri?>(null)
    private var pendingImportPassword by mutableStateOf<String?>(null)
    private var pendingArchiveInfo by mutableStateOf<BackupManager.ArchiveInfo?>(null)

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            val password = pendingExportPassword
            pendingExportPassword = null
            if (NodeController.status.value.state !in setOf(NodeState.STOPPED, NodeState.ERROR)) {
                Utils.toast(this@MainActivity, getString(R.string.toast_export_failed, "Server must be stopped before exporting"))
                return@registerForActivityResult
            }
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
            installPayload(LocalArchivePayloadSource(uri, contentResolver))
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
        installedInfo = payloadManager.getInstalledInfo()
        navController = NavigationController.fromBundle(
            savedState = savedInstanceState,
            defaultScreen = ActiveScreen.DASHBOARD,
            isServerRunning = NodeController.status.value.state == NodeState.RUNNING,
        )

        Utils.requestNotificationPermission(this)

        // Auto-resumes the server on-demand if auto-start is enabled OR if the OS killed the app process while running
        if (installedInfo.isInstalled && (appConfig.autoStartOnAppOpen || appConfig.wasRunningBeforeKill)) {
            ForegroundService.start(this)
        }

        setContent {
            MaterialTheme(colorScheme = StDarkColorScheme) {
                val nodeStatus by NodeController.status.collectAsState()

                LaunchedEffect(nodeStatus.state) {
                    navController.onNodeStateChanged(nodeStatus.state, appConfig.autoLaunchWebViewOnStart)
                }

                MainScreen(
                    navController = navController,
                    status = nodeStatus,
                    isInstalled = installedInfo.isInstalled,
                    installedInfo = installedInfo,
                    isInstalling = isInstalling,
                    installProgress = installProgress,
                    installErrorMessage = installErrorMessage,
                    isCheckingUpdates = isCheckingUpdates,
                    updateCheckResult = updateCheckResult,
                    onInstallPayload = { installPayload() },
                    onCancelInstall = { cancelInstall() },
                    onStartServer = { ForegroundService.start(this@MainActivity) },
                    onStopServer = { ForegroundService.stop(this@MainActivity) },
                    onExportBackup = {
                        if (NodeController.status.value.state !in setOf(NodeState.STOPPED, NodeState.ERROR)) {
                            Utils.toast(this@MainActivity, getString(R.string.toast_export_failed, "Server must be stopped before exporting"))
                        } else {
                            showExportDialog.value = true
                        }
                    },
                    onImportBackup = { importLauncher.launch(arrayOf("*/*")) },
                    onExportLogs = { Utils.shareLogs(this@MainActivity) },
                    onCopyDiagnostics = { DiagnosticsSnapshot.copyToClipboard(this@MainActivity) },
                    onPromptBattery = { Utils.promptBatteryOptimization(this@MainActivity) },
                    onCheckForUpdates = { checkForUpdates() },
                    onUpdatePayload = { update -> performUpdate(update) },
                    onReinstallPayload = { reinstallPayload() },
                    onImportArchive = { payloadArchiveLauncher.launch(arrayOf("application/zip", "application/x-tar", "application/gzip", "*/*")) },
                )

                // Dialog: Export with optional AES-256 password
                if (showExportDialog.value) {
                    ExportBackupDialog(
                        onDismiss = { showExportDialog.value = false },
                        onConfirm = { password ->
                            showExportDialog.value = false
                            if (NodeController.status.value.state !in setOf(NodeState.STOPPED, NodeState.ERROR)) {
                                Utils.toast(this@MainActivity, getString(R.string.toast_export_failed, "Server must be stopped before exporting"))
                            } else {
                                pendingExportPassword = password
                                exportLauncher.launch("st-backup.zip")
                            }
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

    private fun installPayload(
        source: PayloadSource? = null,
        isUpdate: Boolean = false,
        isRepair: Boolean = false,
    ) {
        if (isInstalling) return
        isInstalling = true
        installErrorMessage = null
        installProgress = null

        installJob = lifecycleScope.launch(Dispatchers.IO) {
            val wasActive = NodeController.status.value.state !in setOf(NodeState.STOPPED, NodeState.ERROR)
            if (wasActive) {
                ForegroundService.stop(this@MainActivity)
                withTimeoutOrNull(5000) {
                    NodeController.status.first { it.state == NodeState.STOPPED }
                }
            }
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
                    installedInfo = payloadManager.getInstalledInfo()
                    isInstalling = false
                    installProgress = null
                    when {
                        isUpdate -> {
                            updateCheckResult = UpdateCheckResult.UpToDate(
                                currentVersion = installedInfo.stVersion ?: "",
                                latestVersion = installedInfo.stVersion ?: "",
                            )
                            Utils.toast(this@MainActivity, getString(R.string.toast_update_success))
                        }
                        isRepair -> Utils.toast(this@MainActivity, getString(R.string.toast_repair_success))
                        source is LocalArchivePayloadSource -> Utils.toast(this@MainActivity, getString(R.string.toast_import_archive_success))
                        source != null -> Utils.toast(this@MainActivity, getString(R.string.toast_import_archive_success))
                    }
                    val appConfig = AppConfig(this@MainActivity)
                    if (wasActive || appConfig.autoStartOnAppOpen) {
                        ForegroundService.start(this@MainActivity)
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
                    when {
                        isUpdate -> Utils.toast(this@MainActivity, t.message ?: "Update failed")
                        source != null -> Utils.toast(this@MainActivity, getString(R.string.toast_import_archive_failed, t.message ?: ""))
                    }
                }
            }
        }
    }

    private fun checkForUpdates() {
        if (isCheckingUpdates) return
        isCheckingUpdates = true
        lifecycleScope.launch {
            val payloadManager = PayloadManager(this@MainActivity)
            val info = payloadManager.getInstalledInfo()
            val result = UpdateChecker().checkForUpdates(
                currentPayloadVersion = info.payloadVersion,
                currentStVersion = info.stVersion,
            )
            withContext(Dispatchers.Main) {
                isCheckingUpdates = false
                updateCheckResult = result
                if (result is UpdateCheckResult.UpToDate) {
                    Utils.toast(this@MainActivity, getString(R.string.toast_check_up_to_date))
                } else if (result is UpdateCheckResult.Error) {
                    Utils.toast(this@MainActivity, result.message)
                }
            }
        }
    }

    private fun performUpdate(update: UpdateInfo) {
        val remoteSource = RemotePayloadSource(
            url = update.bundleUrl,
            versionStamp = update.payloadVersion,
            expectedSha256 = update.bundleSha256,
            sourceDescription = "OTA (${update.tagName})",
        )
        installPayload(remoteSource, isUpdate = true)
    }

    private fun reinstallPayload() {
        installPayload(null, isRepair = true)
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
            val wasActive = NodeController.status.value.state !in setOf(NodeState.STOPPED, NodeState.ERROR)
            if (wasActive) {
                ForegroundService.stop(this@MainActivity)
                val stopped = withTimeoutOrNull(5000) {
                    NodeController.status.first { it.state == NodeState.STOPPED }
                }
                if (stopped == null) {
                    Utils.toast(this@MainActivity, getString(R.string.toast_restore_failed, "Server failed to stop cleanly"))
                    return@launch
                }
            }

            BackupManager(this@MainActivity).import(uri, password, strategy) {}
                .onFailure { Utils.toast(this@MainActivity, getString(R.string.toast_restore_failed, it.message)) }
                .onSuccess { count ->
                    Utils.toast(this@MainActivity, getString(R.string.toast_restore_success, count))
                    if (wasActive) {
                        ForegroundService.start(this@MainActivity)
                    } else if (!installedInfo.isInstalled) {
                        lifecycleScope.launch(Dispatchers.Main) {
                            navController.navigateTo(ActiveScreen.DASHBOARD)
                        }
                    }
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
