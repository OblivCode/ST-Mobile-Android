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
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.stmobile.foreground.ForegroundService
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
    onCopyDiagnostics: () -> Unit = {},
) {
    val context = LocalContext.current
    var autoStart by remember { mutableStateOf(appConfig.autoStartOnAppOpen) }
    var autoLaunch by remember { mutableStateOf(appConfig.autoLaunchWebViewOnStart) }
    var autoPortFallback by remember { mutableStateOf(appConfig.autoPortFallback) }
    var quickToolbar by remember { mutableStateOf(appConfig.webViewMenuQuickToolbar) }
    var autoSyncLanguage by remember { mutableStateOf(appConfig.autoSyncLanguage) }
    var notifyOnCompletion by remember { mutableStateOf(appConfig.notifyOnCompletion) }
    var bgTimeout by remember { mutableStateOf(appConfig.backgroundTimeoutMinutes.toString()) }
    var showAppLanguageDialog by rememberSaveable { mutableStateOf(false) }

    val powerManager = remember(context) { context.getSystemService(PowerManager::class.java) }
    var isBatteryOptimized by remember {
        mutableStateOf(powerManager?.isIgnoringBatteryOptimizations(context.packageName) == false)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isBatteryOptimized = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == false
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

        // Language & Localization Settings
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
        if (currentTimeout > 0 && isBatteryOptimized) {
            Card(
                onClick = onPromptBattery,
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
            onClick = onPromptBattery,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.settings_battery_exemption))
        }

        OutlinedButton(
            onClick = onCopyDiagnostics,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.btn_copy_diagnostics))
        }
    }

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
