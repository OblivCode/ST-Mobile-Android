package app.stmobile.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import app.stmobile.R
import app.stmobile.models.AppConfig
import app.stmobile.models.StLocales
import app.stmobile.models.StUserSettings
import app.stmobile.ui.theme.ThemeMode

enum class SettingsDialogType {
    NONE,
    APP_LANGUAGE,
    THEME,
    ST_LANGUAGE,
    SEND_ON_ENTER,
    AVATAR_STYLE,
    NOTIFICATION_TIMEOUT,
}

@Composable
fun SettingsDialogHost(
    activeDialog: SettingsDialogType,
    onDismiss: () -> Unit,
    appConfig: AppConfig,
    userSettings: StUserSettings,
    onSaveUserSettings: () -> Unit,
    currentAppTag: String,
    effectiveStLocaleCode: String,
    onThemeChanged: (ThemeMode) -> Unit,
    context: Context = LocalContext.current,
) {
    when (activeDialog) {
        SettingsDialogType.NONE -> { /* No dialog */ }

        SettingsDialogType.THEME -> {
            val themes = listOf(
                Pair(ThemeMode.SYSTEM, stringResource(R.string.settings_theme_system)),
                Pair(ThemeMode.LIGHT, stringResource(R.string.settings_theme_light)),
                Pair(ThemeMode.DARK, stringResource(R.string.settings_theme_dark)),
            )
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.settings_theme_title)) },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        themes.forEach { (mode, label) ->
                            val isSelected = appConfig.themeMode == mode.code
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        appConfig.themeMode = mode.code
                                        onThemeChanged(mode)
                                        onDismiss()
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        appConfig.themeMode = mode.code
                                        onThemeChanged(mode)
                                        onDismiss()
                                    },
                                )
                                Text(
                                    text = label,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.btn_close))
                    }
                },
            )
        }

        SettingsDialogType.NOTIFICATION_TIMEOUT -> {
            val presets = listOf(5, 10, 15, 30, 0)
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.settings_notification_timeout)) },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        presets.forEach { seconds ->
                            val isSelected = appConfig.notificationTimeoutSeconds == seconds
                            val label = if (seconds == 0) {
                                stringResource(R.string.settings_notification_timeout_never)
                            } else {
                                stringResource(R.string.settings_notification_timeout_seconds, seconds)
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        appConfig.notificationTimeoutSeconds = seconds
                                        onDismiss()
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        appConfig.notificationTimeoutSeconds = seconds
                                        onDismiss()
                                    },
                                )
                                Text(
                                    text = label,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.btn_close))
                    }
                },
            )
        }

        SettingsDialogType.APP_LANGUAGE -> {
            AlertDialog(
                onDismissRequest = onDismiss,
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
                                        onDismiss()
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
                                        onDismiss()
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
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    if (appLocale.tag.isNotEmpty()) {
                                        Text(
                                            text = appLocale.englishName,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    onDismiss()
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
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.btn_close))
                    }
                },
            )
        }

        SettingsDialogType.ST_LANGUAGE -> {
            AlertDialog(
                onDismissRequest = onDismiss,
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
                                        userSettings.uiLanguage = loc.code
                                        onSaveUserSettings()
                                        onDismiss()
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        userSettings.uiLanguage = loc.code
                                        onSaveUserSettings()
                                        onDismiss()
                                    },
                                )
                                Column(modifier = Modifier.padding(start = 8.dp)) {
                                    Text(
                                        text = loc.nativeName,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = loc.englishName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.btn_close))
                    }
                },
            )
        }

        SettingsDialogType.SEND_ON_ENTER -> {
            val options = listOf(
                Triple(StUserSettings.SEND_ON_ENTER_AUTO, stringResource(R.string.dialog_send_auto_title), stringResource(R.string.dialog_send_auto_desc)),
                Triple(StUserSettings.SEND_ON_ENTER_DISABLED, stringResource(R.string.dialog_send_disabled_title), stringResource(R.string.dialog_send_disabled_desc)),
                Triple(StUserSettings.SEND_ON_ENTER_ENABLED, stringResource(R.string.dialog_send_enabled_title), stringResource(R.string.dialog_send_enabled_desc)),
            )
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.dialog_send_on_enter_title)) },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        options.forEach { (value, title, desc) ->
                            val isSelected = userSettings.sendOnEnter == value
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        userSettings.sendOnEnter = value
                                        onSaveUserSettings()
                                        onDismiss()
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        userSettings.sendOnEnter = value
                                        onSaveUserSettings()
                                        onDismiss()
                                    },
                                )
                                Column(modifier = Modifier.padding(start = 8.dp)) {
                                    Text(
                                        text = title,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = desc,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.btn_close))
                    }
                },
            )
        }

        SettingsDialogType.AVATAR_STYLE -> {
            val styles = listOf(
                Pair(StUserSettings.AVATAR_STYLE_ROUND, stringResource(R.string.st_settings_avatar_style_round)),
                Pair(StUserSettings.AVATAR_STYLE_RECTANGULAR, stringResource(R.string.st_settings_avatar_style_rectangular)),
                Pair(StUserSettings.AVATAR_STYLE_SQUARE, stringResource(R.string.st_settings_avatar_style_square)),
                Pair(StUserSettings.AVATAR_STYLE_ROUNDED, stringResource(R.string.st_settings_avatar_style_rounded)),
            )
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.st_settings_avatar_style)) },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        styles.forEach { (styleVal, label) ->
                            val isSelected = userSettings.avatarStyle == styleVal
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        userSettings.avatarStyle = styleVal
                                        onSaveUserSettings()
                                        onDismiss()
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        userSettings.avatarStyle = styleVal
                                        onSaveUserSettings()
                                        onDismiss()
                                    },
                                )
                                Text(
                                    text = label,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.btn_close))
                    }
                },
            )
        }
    }
}
