package app.stmobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults.SecondaryIndicator
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.stmobile.models.AppConfig
import app.stmobile.models.NodeConfig
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    onPromptBattery: () -> Unit,
    onResetPayload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appConfig = remember { AppConfig(context) }
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabTitles = listOf("App", "SillyTavern", "Node Engine")

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF101418)),
    ) {
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color(0xFF1A2228),
            contentColor = Color(0xFF7EC8A9),
            indicator = { tabPositions ->
                SecondaryIndicator(
                    Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                    color = Color(0xFF7EC8A9),
                )
            },
        ) {
            tabTitles.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            text = title,
                            fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                            color = if (selectedTab == index) Color(0xFF7EC8A9) else Color(0xFF8A94A3),
                        )
                    },
                )
            }
        }

        when (selectedTab) {
            0 -> AppSettingsTab(appConfig = appConfig, onPromptBattery = onPromptBattery, onResetPayload = onResetPayload)
            1 -> SillyTavernSettingsTab()
            2 -> NodeEngineSettingsTab(context = context)
        }
    }
}

@Composable
private fun AppSettingsTab(
    appConfig: AppConfig,
    onPromptBattery: () -> Unit,
    onResetPayload: () -> Unit,
) {
    var autoStart by remember { mutableStateOf(appConfig.autoStartOnAppOpen) }
    var autoLaunch by remember { mutableStateOf(appConfig.autoLaunchWebViewOnStart) }
    var autoPortFallback by remember { mutableStateOf(appConfig.autoPortFallback) }
    var bgTimeout by remember { mutableStateOf(appConfig.backgroundTimeoutMinutes.toString()) }
    var showResetDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
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
            Text("Auto-launch SillyTavern", color = Color(0xFFD8DEE6))
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

@Composable
private fun SillyTavernSettingsTab() {
    // Blank for now per design requirements
    Box(modifier = Modifier.fillMaxSize())
}

@Composable
private fun NodeEngineSettingsTab(context: android.content.Context) {
    val initialConfig = remember { NodeConfig.load(context) }
    var maxOldSpace by remember { mutableIntStateOf(initialConfig.maxOldSpaceSizeMb) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "V8 Maximum Heap Size: $maxOldSpace MB",
            style = MaterialTheme.typography.titleMedium,
            color = Color(0xFFD8DEE6),
        )

        Slider(
            value = maxOldSpace.toFloat(),
            onValueChange = { value ->
                maxOldSpace = value.roundToInt()
            },
            onValueChangeFinished = {
                val updated = initialConfig.copy(maxOldSpaceSizeMb = maxOldSpace)
                NodeConfig.save(context, updated)
            },
            valueRange = 256f..4096f,
            steps = 14, // in ~256MB increments
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            text = "Allocates V8 maximum old space size (--max-old-space-size). Changes take effect on next server start.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF8A94A3),
        )
    }
}
