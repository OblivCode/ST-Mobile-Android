package app.stmobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.stmobile.Utils
import app.stmobile.models.StUserSettings
import kotlin.math.round
import kotlin.math.roundToInt

@Composable
fun SillyTavernSettingsTab(
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    modifier: Modifier = Modifier,
    onExportLogs: () -> Unit = {},
) {
    val context = LocalContext.current
    val userSettings = remember { StUserSettings.load(context) }

    fun persistSettings() {
        runCatching {
            userSettings.save()
        }.onFailure { e ->
            Utils.toast(context, "Failed to save settings: ${e.message}")
        }
    }

    var autoLoadChat by remember { mutableStateOf(userSettings.autoLoadChat) }
    var autoConnect by remember { mutableStateOf(userSettings.autoConnect) }
    var compactInputArea by remember { mutableStateOf(userSettings.compactInputArea) }
    var sendOnEnter by remember { mutableIntStateOf(userSettings.sendOnEnter) }
    var fastUiMode by remember { mutableStateOf(userSettings.fastUiMode) }
    var reducedMotion by remember { mutableStateOf(userSettings.reducedMotion) }
    var fontScale by remember { mutableFloatStateOf(userSettings.fontScale) }
    var showSendOnEnterDialog by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // --- 1. User Settings (Phase N) ---
        Text(
            text = "User Settings",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFD8DEE6),
        )

        Text(
            text = "Preferences synchronized directly with SillyTavern's settings.json. Changes take effect on page reload (via the quick toolbar 🔄 button) or server restart.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF8A94A3),
        )

        // Workflow & Resume
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text("Auto load last chat", color = Color(0xFFD8DEE6))
                Text(
                    "Resume active character and chat history upon opening without searching the character drawer",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF8A94A3),
                )
            }
            Switch(
                checked = autoLoadChat,
                onCheckedChange = {
                    autoLoadChat = it
                    userSettings.autoLoadChat = it
                    persistSettings()
                },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text("Auto connect API", color = Color(0xFFD8DEE6))
                Text(
                    "Automatically establish backend connections on startup",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF8A94A3),
                )
            }
            Switch(
                checked = autoConnect,
                onCheckedChange = {
                    autoConnect = it
                    userSettings.autoConnect = it
                    persistSettings()
                },
            )
        }

        // Mobile Usability & Keyboard Ergonomics
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text("Compact input area", color = Color(0xFFD8DEE6))
                Text(
                    "Single-row message input to retain chat history visibility when the virtual keyboard is open",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF8A94A3),
                )
            }
            Switch(
                checked = compactInputArea,
                onCheckedChange = {
                    compactInputArea = it
                    userSettings.compactInputArea = it
                    persistSettings()
                },
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showSendOnEnterDialog = true },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text("Send on Enter", color = Color(0xFFD8DEE6))
                Text(
                    when (sendOnEnter) {
                        StUserSettings.SEND_ON_ENTER_DISABLED -> "Disabled (Enter inserts newline)"
                        StUserSettings.SEND_ON_ENTER_ENABLED -> "Enabled (Enter always sends)"
                        else -> "Automatic (PC sends, mobile newline)"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF7EC8A9),
                )
            }
            OutlinedButton(onClick = { showSendOnEnterDialog = true }) {
                Text("Change")
            }
        }

        // Performance & Display
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text("Fast UI mode", color = Color(0xFFD8DEE6))
                Text(
                    "Disable GPU-heavy blur effects and filters for smoother scrolling and battery savings",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF8A94A3),
                )
            }
            Switch(
                checked = fastUiMode,
                onCheckedChange = {
                    fastUiMode = it
                    userSettings.fastUiMode = it
                    persistSettings()
                },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text("Reduced motion", color = Color(0xFFD8DEE6))
                Text(
                    "Disable interface animations and transitions for snappier navigation",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF8A94A3),
                )
            }
            Switch(
                checked = reducedMotion,
                onCheckedChange = {
                    reducedMotion = it
                    userSettings.reducedMotion = it
                    persistSettings()
                },
            )
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Font scaling: ${(fontScale * 100).roundToInt()}%",
                color = Color(0xFFD8DEE6),
            )
            Slider(
                value = fontScale,
                onValueChange = { fontScale = it },
                onValueChangeFinished = {
                    val rounded = (round(fontScale * 100) / 100f).coerceIn(0.5f, 1.5f)
                    fontScale = rounded
                    userSettings.fontScale = rounded
                    persistSettings()
                },
                valueRange = 0.5f..1.5f,
                steps = 19, // ~0.05 increments
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "Adjust text readability across varying device display DPIs (50% – 150%).",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF8A94A3),
            )
        }

        Spacer(Modifier.height(8.dp))

        // --- 2. Data & Backups ---
        Text(
            text = "Data & Backups",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFD8DEE6),
        )

        Text(
            text = "Export your characters, chats, and configurations to an archive (with optional AES-256 password encryption), or restore an existing backup.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF8A94A3),
        )

        Button(
            onClick = onExportBackup,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Export Backup")
        }

        OutlinedButton(
            onClick = onImportBackup,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Restore Backup")
        }

        Spacer(Modifier.height(8.dp))

        // --- 3. Diagnostics & Logs ---
        Text(
            text = "Diagnostics & Logs",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFD8DEE6),
        )

        Text(
            text = "Share or export the application log file for troubleshooting and debugging server issues.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF8A94A3),
        )

        OutlinedButton(
            onClick = onExportLogs,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Export Logs")
        }
    }

    if (showSendOnEnterDialog) {
        val options = listOf(
            Triple(StUserSettings.SEND_ON_ENTER_AUTO, "Automatic (PC)", "Enter sends on physical keyboard; newline on virtual keyboard"),
            Triple(StUserSettings.SEND_ON_ENTER_DISABLED, "Disabled", "Enter always inserts a new line"),
            Triple(StUserSettings.SEND_ON_ENTER_ENABLED, "Enabled", "Enter always sends message"),
        )
        AlertDialog(
            onDismissRequest = { showSendOnEnterDialog = false },
            title = { Text("Send on Enter") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { (value, title, desc) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    sendOnEnter = value
                                    userSettings.sendOnEnter = value
                                    persistSettings()
                                    showSendOnEnterDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = sendOnEnter == value,
                                onClick = {
                                    sendOnEnter = value
                                    userSettings.sendOnEnter = value
                                    persistSettings()
                                    showSendOnEnterDialog = false
                                },
                            )
                            Column(modifier = Modifier.padding(start = 8.dp)) {
                                Text(title, color = Color(0xFFD8DEE6), fontWeight = FontWeight.SemiBold)
                                Text(desc, style = MaterialTheme.typography.bodySmall, color = Color(0xFF8A94A3))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSendOnEnterDialog = false }) {
                    Text("Close")
                }
            },
        )
    }
}
