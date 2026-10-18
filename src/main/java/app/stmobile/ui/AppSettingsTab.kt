package app.stmobile.ui

import android.os.PowerManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.stmobile.R
import app.stmobile.Utils
import app.stmobile.foreground.ForegroundService
import app.stmobile.foreground.ServiceNotificationHelper
import app.stmobile.models.AppConfig
import app.stmobile.models.StLocales
import app.stmobile.models.StUserSettings
import app.stmobile.ui.theme.ThemeMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AppSettingsTab(
    appConfig: AppConfig,
    userSettings: StUserSettings,
    currentAppTag: String,
    onPromptBattery: () -> Unit,
    onRequestDialog: (SettingsDialogType) -> Unit,
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
    var notifyOnText by remember { mutableStateOf(appConfig.notifyOnTextCompletion) }
    var notifyOnVector by remember { mutableStateOf(appConfig.notifyOnVectorCompletion) }
    var bgTimeout by remember { mutableStateOf(appConfig.backgroundTimeoutMinutes.toString()) }

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

    val currentAppLocale = StLocales.findAppLocale(currentAppTag)
    val currentAppLocaleName = currentAppLocale?.let { "${it.nativeName} (${it.englishName})" }
        ?: stringResource(R.string.locale_system_default)

    val stMappedTag = StLocales.mapStLocaleToAppLocale(userSettings.uiLanguage)
    val canMatchStLanguage = !autoSyncLanguage && stMappedTag != null && stMappedTag != currentAppTag
    val stLocaleDisplay = StLocales.findLocale(userSettings.uiLanguage)?.nativeName ?: userSettings.uiLanguage

    val themeLabel = when (appConfig.themeMode) {
        ThemeMode.LIGHT.code -> stringResource(R.string.settings_theme_light)
        ThemeMode.DARK.code -> stringResource(R.string.settings_theme_dark)
        else -> stringResource(R.string.settings_theme_system)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // =====================================================================
        // Subsection 1: Language
        // =====================================================================
        SettingsCategoryHeader(stringResource(R.string.settings_category_localization))

        SettingSwitchRow(
            title = stringResource(R.string.settings_auto_sync_lang),
            desc = stringResource(R.string.settings_auto_sync_lang_desc),
            checked = autoSyncLanguage,
            onCheckedChange = {
                autoSyncLanguage = it
                appConfig.autoSyncLanguage = it
            },
        )

        SettingPickerRow(
            title = stringResource(R.string.settings_app_language),
            currentValueText = currentAppLocaleName,
            buttonText = stringResource(R.string.btn_change),
            onClick = { onRequestDialog(SettingsDialogType.APP_LANGUAGE) },
        )

        if (canMatchStLanguage) {
            OutlinedButton(
                onClick = {
                    val targetLocales = if (stMappedTag.isNullOrEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(stMappedTag)
                    androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(targetLocales)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.btn_match_st_language, stLocaleDisplay))
            }
        }

        // =====================================================================
        // Subsection 2: Theme
        // =====================================================================
        SettingsCategoryHeader(stringResource(R.string.settings_category_theme))

        SettingPickerRow(
            title = stringResource(R.string.settings_theme_title),
            desc = stringResource(R.string.settings_theme_desc),
            currentValueText = themeLabel,
            buttonText = stringResource(R.string.btn_change),
            onClick = { onRequestDialog(SettingsDialogType.THEME) },
        )

        // =====================================================================
        // Subsection 3: Server
        // =====================================================================
        SettingsCategoryHeader(stringResource(R.string.settings_category_server))

        SettingSwitchRow(
            title = stringResource(R.string.settings_auto_start),
            checked = autoStart,
            onCheckedChange = {
                autoStart = it
                appConfig.autoStartOnAppOpen = it
            },
        )

        SettingSwitchRow(
            title = stringResource(R.string.settings_auto_launch),
            desc = stringResource(R.string.settings_auto_launch_desc),
            checked = autoLaunch,
            onCheckedChange = {
                autoLaunch = it
                appConfig.autoLaunchWebViewOnStart = it
            },
        )

        SettingSwitchRow(
            title = stringResource(R.string.settings_auto_port_fallback),
            checked = autoPortFallback,
            onCheckedChange = {
                autoPortFallback = it
                appConfig.autoPortFallback = it
            },
        )

        // =====================================================================
        // Subsection 4: Power
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
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
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
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
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
                        color = MaterialTheme.colorScheme.primary,
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
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = stringResource(R.string.settings_battery_unrestricted_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // =====================================================================
        // Subsection 5: Notifications
        // =====================================================================
        SettingsCategoryHeader(stringResource(R.string.settings_category_notifications))

        SettingSwitchRow(
            title = stringResource(R.string.settings_notify_text),
            desc = stringResource(R.string.settings_notify_text_desc),
            checked = notifyOnText,
            onCheckedChange = {
                notifyOnText = it
                appConfig.notifyOnTextCompletion = it
            },
        )

        SettingSwitchRow(
            title = stringResource(R.string.settings_notify_vector),
            desc = stringResource(R.string.settings_notify_vector_desc),
            checked = notifyOnVector,
            onCheckedChange = {
                notifyOnVector = it
                appConfig.notifyOnVectorCompletion = it
            },
        )

        if (notifyOnText || notifyOnVector) {
            val timeoutDisplay = if (appConfig.notificationTimeoutSeconds == 0) {
                stringResource(R.string.settings_notification_timeout_never)
            } else {
                stringResource(R.string.settings_notification_timeout_seconds, appConfig.notificationTimeoutSeconds)
            }
            SettingPickerRow(
                title = stringResource(R.string.settings_notification_timeout),
                currentValueText = timeoutDisplay,
                buttonText = stringResource(R.string.btn_change),
                onClick = { onRequestDialog(SettingsDialogType.NOTIFICATION_TIMEOUT) },
            )

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
        // Subsection 6: Navigation
        // =====================================================================
        SettingsCategoryHeader(stringResource(R.string.settings_category_webview))

        SettingSwitchRow(
            title = stringResource(R.string.settings_quick_toolbar),
            desc = stringResource(R.string.settings_quick_toolbar_desc),
            checked = quickToolbar,
            onCheckedChange = {
                quickToolbar = it
                appConfig.webViewMenuQuickToolbar = it
            },
        )

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
        // Subsection 7: Diagnostics
        // =====================================================================
        SettingsCategoryHeader(stringResource(R.string.settings_category_diagnostics))

        Text(
            text = stringResource(R.string.settings_diagnostics_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
}
