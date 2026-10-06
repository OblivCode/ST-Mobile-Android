package app.stmobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.stmobile.models.AppConfig

@Composable
fun AppSettingsTab(
    appConfig: AppConfig,
    onPromptBattery: () -> Unit,
    onResetPayload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var autoStart by remember { mutableStateOf(appConfig.autoStartOnAppOpen) }
    var autoLaunch by remember { mutableStateOf(appConfig.autoLaunchWebViewOnStart) }
    var autoPortFallback by remember { mutableStateOf(appConfig.autoPortFallback) }
    var quickToolbar by remember { mutableStateOf(appConfig.webViewMenuQuickToolbar) }
    var bgTimeout by remember { mutableStateOf(appConfig.backgroundTimeoutMinutes.toString()) }
    var showResetDialog by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Auto-start server on app launch", color = Color(0xFFD8DEE6))
            Switch(
                checked = autoStart,
                onCheckedChange = {
                    autoStart = it
                    appConfig.autoStartOnAppOpen = it
                },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text("Auto-launch SillyTavern", color = Color(0xFFD8DEE6))
                Text(
                    "Automatically switch to the SillyTavern interface whenever the server becomes running",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF8A94A3),
                )
            }
            Switch(
                checked = autoLaunch,
                onCheckedChange = {
                    autoLaunch = it
                    appConfig.autoLaunchWebViewOnStart = it
                },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Auto fallback port if occupied", color = Color(0xFFD8DEE6))
            Switch(
                checked = autoPortFallback,
                onCheckedChange = {
                    autoPortFallback = it
                    appConfig.autoPortFallback = it
                },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text("WebView menu quick toolbar", color = Color(0xFFD8DEE6))
                Text(
                    "Show a dock with Dashboard, Reload, and Settings shortcuts instead of immediately returning to Dashboard",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF8A94A3),
                )
            }
            Switch(
                checked = quickToolbar,
                onCheckedChange = {
                    quickToolbar = it
                    appConfig.webViewMenuQuickToolbar = it
                },
            )
        }

        OutlinedTextField(
            value = bgTimeout,
            onValueChange = { input ->
                val filtered = input.filter(Char::isDigit).take(3)
                bgTimeout = filtered
                filtered.toIntOrNull()?.let { appConfig.backgroundTimeoutMinutes = it }
            },
            label = { Text("Background idle timeout (minutes, 0 to disable)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = onPromptBattery,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Request Battery Optimization Exemption")
        }

        Button(
            onClick = { showResetDialog = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF4A2525),
                contentColor = Color(0xFFFFB4AB),
            ),
        ) {
            Text("Reset SillyTavern Payload")
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset Payload") },
            text = { Text("This will reinstall the bundled SillyTavern application code. User data (characters, chats) will be preserved.") },
            confirmButton = {
                TextButton(onClick = {
                    showResetDialog = false
                    onResetPayload()
                }) {
                    Text("Reset")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}
