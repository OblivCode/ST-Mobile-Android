package app.stmobile.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.os.PowerManager
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.stmobile.Utils
import app.stmobile.foreground.ForegroundService
import app.stmobile.foreground.ServiceNotificationHelper
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import app.stmobile.R
import app.stmobile.models.AppConfig
import app.stmobile.models.StLocales
import app.stmobile.models.StUserSettings

@Composable
fun AppSettingsTab(
    appConfig: AppConfig,
    onPromptBattery: () -> Unit,
    modifier: Modifier = Modifier,
    onExportLogs: () -> Unit = {},
    onCopyDiagnostics: () -> Unit = {},
) {
    val context = LocalContext.current
    var autoStart by remember { mutableStateOf(appConfig.autoStartOnAppOpen) }
    var autoLaunch by remember { mutableStateOf(appConfig.autoLaunchWebViewOnStart) }
    var autoPortFallback by remember { mutableStateOf(appConfig.autoPortFallback) }
    var quickToolbar by remember { mutableStateOf(appConfig.webViewMenuQuickToolbar) }
    var autoSyncLanguage by remember { mutableStateOf(appConfig.autoSyncLanguage) }
    var notifyOnCompletion by remember { mutableStateOf(appConfig.notifyOnCompletion) }
    var notifyOnText by remember { mutableStateOf(appConfig.notifyOnTextCompletion) }
    var notifyOnVector by remember { mutableStateOf(appConfig.notifyOnVectorCompletion) }
    var notificationTimeoutSeconds by remember { mutableIntStateOf(appConfig.notificationTimeoutSeconds) }
    var bgTimeout by remember { mutableStateOf(appConfig.backgroundTimeoutMinutes.toString()) }
    var showAppLanguageDialog by rememberSaveable { mutableStateOf(false) }
    var showTimeoutDialog by rememberSaveable { mutableStateOf(false) }

    val powerManager = remember(context) { context.getSystemService(PowerManager::class.java) }
    var isBatteryOptimized by remember {
        mutableStateOf(powerManager?.isIgnoringBatteryOptimizations(context.packageName) == false)
    }

    val coroutineScope = rememberCoroutineScope()
    fun checkBatteryStatus() {
        val current = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == false
        if (isBatteryOptimized != current) {
            isBatteryOptimized = current
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                checkBatteryStatus()
                coroutineScope.launch {
                    listOf(300L, 800L, 1800L, 3000L).forEach { delayMs ->
                        delay(delayMs)
                        checkBatteryStatus()
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Active app shell locale tag from AppCompatDelegate
    val currentLocales = AppCompatDelegate.getApplicationLocales()
    val currentAppTag = if (!currentLocales.isEmpty) currentLocales[0]?.toLanguageTag()?.substringBefore('-') ?: "" else ""
    val currentAppLocale = StLocales.findAppLocale(currentAppTag)
    val currentAppLocaleName = currentAppLocale?.let { "${it.nativeName} (${it.englishName})" }
        ?: stringResource(R.string.locale_system_default)

    // Check if SillyTavern has a distinct language that can be matched
    val userSettings = remember { StUserSettings.load(context) }
    val stMappedTag = StLocales.mapStLocaleToAppLocale(userSettings.uiLanguage)
    val canMatchStLanguage = !autoSyncLanguage && stMappedTag != null && stMappedTag != currentAppTag
    val stLocaleDisplay = StLocales.findLocale(userSettings.uiLanguage)?.nativeName ?: userSettings.uiLanguage

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // =====================================================================
        // Subsection 1: Server & Lifecycle
        // =====================================================================
        SettingsCategoryHeader(stringResource(R.string.settings_category_server))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.settings_auto_start), color = Color(0xFFD8DEE6))
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
                Text(stringResource(R.string.settings_auto_launch), color = Color(0xFFD8DEE6))
                Text(
                    stringResource(R.string.settings_auto_launch_desc),
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
            Text(stringResource(R.string.settings_auto_port_fallback), color = Color(0xFFD8DEE6))
            Switch(
                checked = autoPortFallback,
                onCheckedChange = {
                    autoPortFallback = it
                    appConfig.autoPortFallback = it
                },
            )
        }

        // =====================================================================
        // Subsection 2: Background & Power Management
        // =====================================================================
        SettingsCategoryHeader(stringResource(R.string.settings_category_power))

        OutlinedTextField(
            value = bgTimeout,
            onValueChange = { input ->
                val filtered = input.filter(Char::isDigit).take(3)
                bgTimeout = filtered
                val parsed = filtered.toIntOrNull() ?: 0
                appConfig.backgroundTimeoutMinutes = parsed
                ForegroundService.onTimeoutConfigChanged(parsed)
            },
            label = { Text(stringResource(R.string.settings_bg_timeout)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        val currentTimeout = bgTimeout.toIntOrNull() ?: appConfig.backgroundTimeoutMinutes
        if (isBatteryOptimized) {
            if (currentTimeout > 0) {
                Card(
                    onClick = {
                        checkBatteryStatus()
                        onPromptBattery()
                    },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = "⚠️",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.settings_battery_warning, currentTimeout),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            Button(
                onClick = {
                    checkBatteryStatus()
                    onPromptBattery()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_battery_exemption))
            }
        } else {
            Card(
                onClick = {
                    checkBatteryStatus()
                    onPromptBattery()
                },
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF7EC8A9).copy(alpha = 0.12f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFF7EC8A9).copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "✓",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFF7EC8A9),
                        fontWeight = FontWeight.Bold,
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.settings_battery_unrestricted_title),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF7EC8A9),
                        )
                        Text(
                            text = stringResource(R.string.settings_battery_unrestricted_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF8A94A3),
                        )
                    }
                }
            }
        }

        // =====================================================================
        // Subsection 3: Completion Pop-up Alerts
        // =====================================================================
        SettingsCategoryHeader(stringResource(R.string.settings_category_notifications))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text(stringResource(R.string.settings_notify_completion), color = Color(0xFFD8DEE6))
                Text(
                    stringResource(R.string.settings_notify_completion_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF8A94A3),
                )
            }
            Switch(
                checked = notifyOnCompletion,
                onCheckedChange = {
                    notifyOnCompletion = it
                    appConfig.notifyOnCompletion = it
                },
            )
        }

        if (notifyOnCompletion) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(stringResource(R.string.settings_notify_text), color = Color(0xFFD8DEE6))
                    Text(
                        stringResource(R.string.settings_notify_text_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF8A94A3),
                    )
                }
                Switch(
                    checked = notifyOnText,
                    onCheckedChange = {
                        notifyOnText = it
                        appConfig.notifyOnTextCompletion = it
                    },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(stringResource(R.string.settings_notify_vector), color = Color(0xFFD8DEE6))
                    Text(
                        stringResource(R.string.settings_notify_vector_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF8A94A3),
                    )
                }
                Switch(
                    checked = notifyOnVector,
                    onCheckedChange = {
                        notifyOnVector = it
                        appConfig.notifyOnVectorCompletion = it
                    },
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showTimeoutDialog = true }
                    .padding(start = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(stringResource(R.string.settings_notification_timeout), color = Color(0xFFD8DEE6))
                    val timeoutDisplay = if (notificationTimeoutSeconds == 0) {
                        stringResource(R.string.settings_notification_timeout_never)
                    } else {
                        stringResource(R.string.settings_notification_timeout_seconds, notificationTimeoutSeconds)
                    }
                    Text(
                        timeoutDisplay,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF7EC8A9),
                    )
                }
                OutlinedButton(onClick = { showTimeoutDialog = true }) {
                    Text(stringResource(R.string.btn_change))
                }
            }

            OutlinedButton(
                onClick = {
                    Utils.openChannelNotificationSettings(context, ServiceNotificationHelper.ALERTS_CHANNEL_ID)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.btn_configure_floating_notifications))
            }
        }

        // =====================================================================
        // Subsection 4: WebView & Navigation
        // =====================================================================
        SettingsCategoryHeader(stringResource(R.string.settings_category_webview))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text(stringResource(R.string.settings_quick_toolbar), color = Color(0xFFD8DEE6))
                Text(
                    stringResource(R.string.settings_quick_toolbar_desc),
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

        OutlinedButton(
            onClick = {
                appConfig.webViewMenuVerticalFraction = 0.5f
                Utils.toast(context, context.getString(R.string.toast_pill_position_reset))
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.btn_reset_pill_position))
        }

        // =====================================================================
        // Subsection 5: Language & Interface
        // =====================================================================
        SettingsCategoryHeader(stringResource(R.string.settings_category_localization))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text(stringResource(R.string.settings_auto_sync_lang), color = Color(0xFFD8DEE6))
                Text(
                    stringResource(R.string.settings_auto_sync_lang_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF8A94A3),
                )
            }
            Switch(
                checked = autoSyncLanguage,
                onCheckedChange = {
                    autoSyncLanguage = it
                    appConfig.autoSyncLanguage = it
                },
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showAppLanguageDialog = true },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text(stringResource(R.string.settings_app_language), color = Color(0xFFD8DEE6))
                Text(
                    currentAppLocaleName,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF7EC8A9),
                )
            }
            OutlinedButton(onClick = { showAppLanguageDialog = true }) {
                Text(stringResource(R.string.btn_change))
            }
        }

        if (canMatchStLanguage) {
            OutlinedButton(
                onClick = {
                    val targetLocales = if (stMappedTag.isNullOrEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(stMappedTag)
                    AppCompatDelegate.setApplicationLocales(targetLocales)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.btn_match_st_language, stLocaleDisplay))
            }
        }

        // =====================================================================
        // Subsection 6: Diagnostics & Troubleshooting
        // =====================================================================
        SettingsCategoryHeader(stringResource(R.string.settings_category_diagnostics))

        Text(
            text = stringResource(R.string.settings_diagnostics_desc),
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF8A94A3),
        )

        OutlinedButton(
            onClick = onExportLogs,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.btn_export_logs))
        }

        OutlinedButton(
            onClick = onCopyDiagnostics,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.btn_copy_diagnostics))
        }
    }

    // Dialog: Notification auto-dismiss duration
    if (showTimeoutDialog) {
        val presets = listOf(5, 10, 15, 30, 0)
        AlertDialog(
            onDismissRequest = { showTimeoutDialog = false },
            title = { Text(stringResource(R.string.settings_notification_timeout)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    presets.forEach { seconds ->
                        val isSelected = notificationTimeoutSeconds == seconds
                        val label = if (seconds == 0) {
                            stringResource(R.string.settings_notification_timeout_never)
                        } else {
                            stringResource(R.string.settings_notification_timeout_seconds, seconds)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    notificationTimeoutSeconds = seconds
                                    appConfig.notificationTimeoutSeconds = seconds
                                    showTimeoutDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = {
                                    notificationTimeoutSeconds = seconds
                                    appConfig.notificationTimeoutSeconds = seconds
                                    showTimeoutDialog = false
                                },
                            )
                            Text(
                                text = label,
                                color = Color(0xFFD8DEE6),
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTimeoutDialog = false }) {
                    Text(stringResource(R.string.btn_close))
                }
            },
        )
    }

    // Dialog: UI Language Selection
    if (showAppLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showAppLanguageDialog = false },
            title = { Text(stringResource(R.string.settings_app_language)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StLocales.SUPPORTED_APP_LOCALES.forEach { appLocale ->
                        val isSelected = currentAppTag.equals(appLocale.tag, ignoreCase = true)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showAppLanguageDialog = false
                                    val list = if (appLocale.tag.isEmpty()) {
                                        LocaleListCompat.getEmptyLocaleList()
                                    } else {
                                        LocaleListCompat.forLanguageTags(appLocale.tag)
                                    }
                                    AppCompatDelegate.setApplicationLocales(list)
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = {
                                    showAppLanguageDialog = false
                                    val list = if (appLocale.tag.isEmpty()) {
                                        LocaleListCompat.getEmptyLocaleList()
                                    } else {
                                        LocaleListCompat.forLanguageTags(appLocale.tag)
                                    }
                                    AppCompatDelegate.setApplicationLocales(list)
                                },
                            )
                            Column(modifier = Modifier.padding(start = 8.dp)) {
                                Text(
                                    text = if (appLocale.tag.isEmpty()) stringResource(R.string.locale_system_default) else appLocale.nativeName,
                                    color = Color(0xFFD8DEE6),
                                    fontWeight = FontWeight.SemiBold,
                                )
                                if (appLocale.tag.isNotEmpty()) {
                                    Text(
                                        text = appLocale.englishName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF8A94A3),
                                    )
                                }
                            }
                        }
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                showAppLanguageDialog = false
                                val intent = Intent(Settings.ACTION_APP_LOCALE_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                }
                                runCatching { context.startActivity(intent) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.btn_open_system_locale))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAppLanguageDialog = false }) {
                    Text(stringResource(R.string.btn_close))
                }
            },
        )
    }
}

@Composable
private fun SettingsCategoryHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = Color(0xFF7EC8A9),
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}
