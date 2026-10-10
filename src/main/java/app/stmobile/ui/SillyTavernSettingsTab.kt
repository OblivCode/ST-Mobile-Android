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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.LocaleManagerCompat
import app.stmobile.R
import app.stmobile.Utils
import app.stmobile.models.AppConfig
import app.stmobile.models.StLocales
import app.stmobile.models.StUserSettings
import java.util.Locale
import kotlin.math.round
import kotlin.math.roundToInt

@Composable
fun SillyTavernSettingsTab(
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    modifier: Modifier = Modifier,
    onExportLogs: () -> Unit = {},
    onCopyDiagnostics: () -> Unit = {},
) {
    val context = LocalContext.current
    val appConfig = remember(context) { AppConfig(context) }
    val userSettings = remember { StUserSettings.load(context) }

    fun persistSettings() {
        runCatching {
            userSettings.save()
        }.onFailure { e ->
            Utils.toast(context, context.getString(R.string.toast_save_settings_failed, e.message ?: ""))
        }
    }

    var uiLanguage by remember { mutableStateOf(userSettings.uiLanguage) }
    var autoLoadChat by remember { mutableStateOf(userSettings.autoLoadChat) }
    var autoConnect by remember { mutableStateOf(userSettings.autoConnect) }
    var compactInputArea by remember { mutableStateOf(userSettings.compactInputArea) }
    var sendOnEnter by remember { mutableIntStateOf(userSettings.sendOnEnter) }
    var fastUiMode by remember { mutableStateOf(userSettings.fastUiMode) }
    var reducedMotion by remember { mutableStateOf(userSettings.reducedMotion) }
    var fontScale by remember { mutableFloatStateOf(userSettings.fontScale) }
    var showSendOnEnterDialog by rememberSaveable { mutableStateOf(false) }
    var showLanguageDialog by rememberSaveable { mutableStateOf(false) }

    val autoSync = appConfig.autoSyncLanguage
    val currentLocales = LocaleManagerCompat.getApplicationLocales(context)
    val currentAppLocale = if (!currentLocales.isEmpty) currentLocales[0] ?: Locale.getDefault() else Locale.getDefault()
    val effectiveStLocaleCode = StLocales.getEffectiveLocale(uiLanguage, autoSync, currentAppLocale)
    val effectiveStLocale = StLocales.findLocale(effectiveStLocaleCode)
    val effectiveStDisplayName = effectiveStLocale?.let { "${it.nativeName} (${it.englishName})" } ?: effectiveStLocaleCode

    val currentAppStCode = StLocales.resolveSystemLocale(currentAppLocale)
    val currentAppStName = StLocales.findLocale(currentAppStCode)?.nativeName ?: currentAppStCode

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // --- 1. User Settings (Phase N + Phase O) ---
        Text(
            text = stringResource(R.string.st_settings_user_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFD8DEE6),
        )

        Text(
            text = stringResource(R.string.st_settings_user_desc),
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF8A94A3),
        )

        // UI Language (Phase O)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !autoSync) { showLanguageDialog = true },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text(stringResource(R.string.st_settings_ui_language), color = Color(0xFFD8DEE6))
                Text(
                    text = if (autoSync) {
                        stringResource(R.string.st_settings_lang_synced, effectiveStDisplayName)
                    } else {
                        effectiveStDisplayName
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (autoSync) Color(0xFF8A94A3) else Color(0xFF7EC8A9),
                )
            }
            OutlinedButton(
                onClick = { showLanguageDialog = true },
                enabled = !autoSync,
            ) {
                Text(stringResource(R.string.btn_change))
            }
        }

        // Manual Sync to App Language button (only available in independent mode when languages differ)
        if (!autoSync && effectiveStLocaleCode != currentAppStCode) {
            OutlinedButton(
                onClick = {
                    uiLanguage = currentAppStCode
                    userSettings.uiLanguage = currentAppStCode
                    persistSettings()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.btn_sync_app_language, currentAppStName))
            }
        }

        // Workflow & Resume
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text(stringResource(R.string.st_settings_auto_load_chat), color = Color(0xFFD8DEE6))
                Text(
                    stringResource(R.string.st_settings_auto_load_chat_desc),
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
                Text(stringResource(R.string.st_settings_auto_connect), color = Color(0xFFD8DEE6))
                Text(
                    stringResource(R.string.st_settings_auto_connect_desc),
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
                Text(stringResource(R.string.st_settings_compact_input), color = Color(0xFFD8DEE6))
                Text(
                    stringResource(R.string.st_settings_compact_input_desc),
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
                Text(stringResource(R.string.st_settings_send_on_enter), color = Color(0xFFD8DEE6))
                Text(
                    when (sendOnEnter) {
                        StUserSettings.SEND_ON_ENTER_DISABLED -> stringResource(R.string.st_settings_send_disabled)
                        StUserSettings.SEND_ON_ENTER_ENABLED -> stringResource(R.string.st_settings_send_enabled)
                        else -> stringResource(R.string.st_settings_send_auto)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF7EC8A9),
                )
            }
            OutlinedButton(onClick = { showSendOnEnterDialog = true }) {
                Text(stringResource(R.string.btn_change))
            }
        }

        // Performance & Display
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text(stringResource(R.string.st_settings_fast_ui), color = Color(0xFFD8DEE6))
                Text(
                    stringResource(R.string.st_settings_fast_ui_desc),
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
                Text(stringResource(R.string.st_settings_reduced_motion), color = Color(0xFFD8DEE6))
                Text(
                    stringResource(R.string.st_settings_reduced_motion_desc),
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
                text = stringResource(R.string.st_settings_font_scale, (fontScale * 100).roundToInt()),
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
                text = stringResource(R.string.st_settings_font_scale_desc),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF8A94A3),
            )
        }

        Spacer(Modifier.height(8.dp))

        // --- 2. Data & Backups ---
        Text(
            text = stringResource(R.string.st_settings_backups_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFD8DEE6),
        )

        Text(
            text = stringResource(R.string.st_settings_backups_desc),
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF8A94A3),
        )

        Button(
            onClick = onExportBackup,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.btn_export_backup))
        }

        OutlinedButton(
            onClick = onImportBackup,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.btn_restore_backup))
        }

        Spacer(Modifier.height(8.dp))

        // --- 3. Diagnostics & Logs ---
        Text(
            text = stringResource(R.string.st_settings_logs_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFD8DEE6),
        )

        Text(
            text = stringResource(R.string.st_settings_logs_desc),
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

    // Dialog: UI Language Selection
    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(stringResource(R.string.st_settings_ui_language)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StLocales.SUPPORTED_LOCALES.forEach { loc ->
                        val isSelected = effectiveStLocaleCode.equals(loc.code, ignoreCase = true)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    uiLanguage = loc.code
                                    userSettings.uiLanguage = loc.code
                                    persistSettings()
                                    showLanguageDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = {
                                    uiLanguage = loc.code
                                    userSettings.uiLanguage = loc.code
                                    persistSettings()
                                    showLanguageDialog = false
                                },
                            )
                            Column(modifier = Modifier.padding(start = 8.dp)) {
                                Text(loc.nativeName, color = Color(0xFFD8DEE6), fontWeight = FontWeight.SemiBold)
                                Text(loc.englishName, style = MaterialTheme.typography.bodySmall, color = Color(0xFF8A94A3))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguageDialog = false }) {
                    Text(stringResource(R.string.btn_close))
                }
            },
        )
    }

    if (showSendOnEnterDialog) {
        val options = listOf(
            Triple(StUserSettings.SEND_ON_ENTER_AUTO, stringResource(R.string.dialog_send_auto_title), stringResource(R.string.dialog_send_auto_desc)),
            Triple(StUserSettings.SEND_ON_ENTER_DISABLED, stringResource(R.string.dialog_send_disabled_title), stringResource(R.string.dialog_send_disabled_desc)),
            Triple(StUserSettings.SEND_ON_ENTER_ENABLED, stringResource(R.string.dialog_send_enabled_title), stringResource(R.string.dialog_send_enabled_desc)),
        )
        AlertDialog(
            onDismissRequest = { showSendOnEnterDialog = false },
            title = { Text(stringResource(R.string.dialog_send_on_enter_title)) },
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
                    Text(stringResource(R.string.btn_close))
                }
            },
        )
    }
}
