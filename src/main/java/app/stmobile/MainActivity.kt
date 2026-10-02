package app.stmobile

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
import androidx.lifecycle.lifecycleScope
import app.stmobile.models.AppConfig
import app.stmobile.models.NodeState
import app.stmobile.node.NodeService
import app.stmobile.sillytavern.BackupManager
import app.stmobile.sillytavern.PayloadManager
import app.stmobile.ui.ActiveScreen
import app.stmobile.ui.MainScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {

    private val activeScreen = mutableStateOf(ActiveScreen.SETUP)

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                BackupManager(this@MainActivity).export(uri) {}
                    .onFailure { Utils.toast(this@MainActivity, "Export failed: ${it.message}") }
                    .onSuccess { Utils.toast(this@MainActivity, "Backup exported") }
            }
        }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                NodeService.stop(this@MainActivity)
                withTimeoutOrNull(3000) {
                    NodeService.status.first { it.state == NodeState.STOPPED }
                }
                BackupManager(this@MainActivity).import(uri) {}
                    .onFailure { Utils.toast(this@MainActivity, "Import failed: ${it.message}") }
                    .onSuccess {
                        Utils.toast(this@MainActivity, "Backup imported")
                        NodeService.start(this@MainActivity)
                    }
            }
        }
    }

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
                    onExportBackup = { exportLauncher.launch("st-backup.zip") },
                    onImportBackup = { importLauncher.launch(arrayOf("*/*")) },
                    onExportLogs = { Utils.shareLogs(this@MainActivity) },
                    onPromptBattery = { Utils.promptBatteryOptimization(this@MainActivity) },
                    onResetPayload = { resetPayload() },
                    onSetupComplete = {
                        activeScreen.value = ActiveScreen.DASHBOARD
                        if (appConfig.autoStartOnAppOpen) NodeService.start(this@MainActivity)
                    },
                )
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