package app.stmobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.stmobile.R
import app.stmobile.models.AppConfig
import app.stmobile.models.StLocales
import app.stmobile.models.StUserSettings
import kotlin.math.round
import kotlin.math.roundToInt

@Composable
fun SillyTavernSettingsTab(
    userSettings: StUserSettings,
    appConfig: AppConfig,
    onSaveUserSettings: () -> Unit,
    onRequestDialog: (SettingsDialogType) -> Unit,
    effectiveStLocaleCode: String,
    currentAppStCode: String,
    modifier: Modifier = Modifier,
) {
    var autoLoadChat by remember(userSettings) { mutableStateOf(userSettings.autoLoadChat) }
    var autoConnect by remember(userSettings) { mutableStateOf(userSettings.autoConnect) }
    var compactInputArea by remember(userSettings) { mutableStateOf(userSettings.compactInputArea) }
    var confirmMessageDelete by remember(userSettings) { mutableStateOf(userSettings.confirmMessageDelete) }
    var clickToEdit by remember(userSettings) { mutableStateOf(userSettings.clickToEdit) }
    var restoreUserInput by remember(userSettings) { mutableStateOf(userSettings.restoreUserInput) }
    var showSwipeNumAllMessages by remember(userSettings) { mutableStateOf(userSettings.showSwipeNumAllMessages) }

    var smoothStreaming by remember(userSettings) { mutableStateOf(userSettings.smoothStreaming) }
    var streamingFps by remember(userSettings) { mutableIntStateOf(userSettings.streamingFps) }
    var autoScrollChatToBottom by remember(userSettings) { mutableStateOf(userSettings.autoScrollChatToBottom) }
    var fastUiMode by remember(userSettings) { mutableStateOf(userSettings.fastUiMode) }
    var reducedMotion by remember(userSettings) { mutableStateOf(userSettings.reducedMotion) }

    var chatWidth by remember(userSettings) { mutableIntStateOf(userSettings.chatWidth) }
    var fontScale by remember(userSettings) { mutableFloatStateOf(userSettings.fontScale) }
    var timestampsEnabled by remember(userSettings) { mutableStateOf(userSettings.timestampsEnabled) }
    var neverResizeAvatars by remember(userSettings) { mutableStateOf(userSettings.neverResizeAvatars) }

    val autoSync = appConfig.autoSyncLanguage
    val effectiveStLocale = StLocales.findLocale(effectiveStLocaleCode)
    val effectiveStDisplayName = effectiveStLocale?.let { "${it.nativeName} (${it.englishName})" } ?: effectiveStLocaleCode
    val currentAppStName = StLocales.findLocale(currentAppStCode)?.nativeName ?: currentAppStCode

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // --- 1. Language ---
        SettingsCategoryHeader(stringResource(R.string.st_settings_category_language))

        SettingPickerRow(
            title = stringResource(R.string.st_settings_ui_language),
            currentValueText = if (autoSync) {
                stringResource(R.string.st_settings_lang_synced, effectiveStDisplayName)
            } else {
                effectiveStDisplayName
            },
            buttonText = stringResource(R.string.btn_change),
            enabled = !autoSync,
            onClick = { onRequestDialog(SettingsDialogType.ST_LANGUAGE) },
        )

        if (!autoSync && effectiveStLocaleCode != currentAppStCode) {
            androidx.compose.material3.OutlinedButton(
                onClick = {
                    userSettings.uiLanguage = currentAppStCode
                    onSaveUserSettings()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                androidx.compose.material3.Text(stringResource(R.string.btn_sync_app_language, currentAppStName))
            }
        }

        // --- 2. Chat & Input ---
        SettingsCategoryHeader(stringResource(R.string.st_settings_subheader_chat_input))

        SettingSwitchRow(
            title = stringResource(R.string.st_settings_auto_load_chat),
            desc = stringResource(R.string.st_settings_auto_load_chat_desc),
            checked = autoLoadChat,
            onCheckedChange = {
                autoLoadChat = it
                userSettings.autoLoadChat = it
                onSaveUserSettings()
            },
        )

        SettingSwitchRow(
            title = stringResource(R.string.st_settings_auto_connect),
            desc = stringResource(R.string.st_settings_auto_connect_desc),
            checked = autoConnect,
            onCheckedChange = {
                autoConnect = it
                userSettings.autoConnect = it
                onSaveUserSettings()
            },
        )

        SettingSwitchRow(
            title = stringResource(R.string.st_settings_compact_input),
            desc = stringResource(R.string.st_settings_compact_input_desc),
            checked = compactInputArea,
            onCheckedChange = {
                compactInputArea = it
                userSettings.compactInputArea = it
                onSaveUserSettings()
            },
        )

        val sendOnEnterText = when (userSettings.sendOnEnter) {
            StUserSettings.SEND_ON_ENTER_DISABLED -> stringResource(R.string.st_settings_send_disabled)
            StUserSettings.SEND_ON_ENTER_ENABLED -> stringResource(R.string.st_settings_send_enabled)
            else -> stringResource(R.string.st_settings_send_auto)
        }
        SettingPickerRow(
            title = stringResource(R.string.st_settings_send_on_enter),
            currentValueText = sendOnEnterText,
            buttonText = stringResource(R.string.btn_change),
            onClick = { onRequestDialog(SettingsDialogType.SEND_ON_ENTER) },
        )

        SettingSwitchRow(
            title = stringResource(R.string.st_settings_confirm_delete),
            desc = stringResource(R.string.st_settings_confirm_delete_desc),
            checked = confirmMessageDelete,
            onCheckedChange = {
                confirmMessageDelete = it
                userSettings.confirmMessageDelete = it
                onSaveUserSettings()
            },
        )

        SettingSwitchRow(
            title = stringResource(R.string.st_settings_click_to_edit),
            desc = stringResource(R.string.st_settings_click_to_edit_desc),
            checked = clickToEdit,
            onCheckedChange = {
                clickToEdit = it
                userSettings.clickToEdit = it
                onSaveUserSettings()
            },
        )

        SettingSwitchRow(
            title = stringResource(R.string.st_settings_restore_input),
            desc = stringResource(R.string.st_settings_restore_input_desc),
            checked = restoreUserInput,
            onCheckedChange = {
                restoreUserInput = it
                userSettings.restoreUserInput = it
                onSaveUserSettings()
            },
        )

        SettingSwitchRow(
            title = stringResource(R.string.st_settings_swipe_numbers),
            desc = stringResource(R.string.st_settings_swipe_numbers_desc),
            checked = showSwipeNumAllMessages,
            onCheckedChange = {
                showSwipeNumAllMessages = it
                userSettings.showSwipeNumAllMessages = it
                onSaveUserSettings()
            },
        )

        // --- 3. Streaming & Motion ---
        SettingsCategoryHeader(stringResource(R.string.st_settings_subheader_streaming))

        SettingSwitchRow(
            title = stringResource(R.string.st_settings_smooth_streaming),
            desc = stringResource(R.string.st_settings_smooth_streaming_desc),
            checked = smoothStreaming,
            onCheckedChange = {
                smoothStreaming = it
                userSettings.smoothStreaming = it
                onSaveUserSettings()
            },
        )

        SettingSliderRow(
            title = stringResource(R.string.st_settings_streaming_fps, streamingFps),
            desc = stringResource(R.string.st_settings_streaming_fps_desc),
            value = streamingFps.toFloat(),
            range = 5f..100f,
            steps = 18, // 5fps increments
            onValueChange = { streamingFps = (round(it / 5f) * 5f).toInt() },
            onValueChangeFinished = {
                userSettings.streamingFps = streamingFps
                onSaveUserSettings()
            },
        )

        SettingSwitchRow(
            title = stringResource(R.string.st_settings_auto_scroll),
            desc = stringResource(R.string.st_settings_auto_scroll_desc),
            checked = autoScrollChatToBottom,
            onCheckedChange = {
                autoScrollChatToBottom = it
                userSettings.autoScrollChatToBottom = it
                onSaveUserSettings()
            },
        )

        SettingSwitchRow(
            title = stringResource(R.string.st_settings_fast_ui),
            desc = stringResource(R.string.st_settings_fast_ui_desc),
            checked = fastUiMode,
            onCheckedChange = {
                fastUiMode = it
                userSettings.fastUiMode = it
                onSaveUserSettings()
            },
        )

        SettingSwitchRow(
            title = stringResource(R.string.st_settings_reduced_motion),
            desc = stringResource(R.string.st_settings_reduced_motion_desc),
            checked = reducedMotion,
            onCheckedChange = {
                reducedMotion = it
                userSettings.reducedMotion = it
                onSaveUserSettings()
            },
        )

        // --- 4. Display & Styling ---
        SettingsCategoryHeader(stringResource(R.string.st_settings_subheader_styling))

        SettingSliderRow(
            title = stringResource(R.string.st_settings_chat_width, chatWidth),
            desc = stringResource(R.string.st_settings_chat_width_desc),
            value = chatWidth.toFloat(),
            range = 25f..100f,
            steps = 14, // 5% increments
            onValueChange = { chatWidth = (round(it / 5f) * 5f).toInt() },
            onValueChangeFinished = {
                userSettings.chatWidth = chatWidth
                onSaveUserSettings()
            },
        )

        SettingSliderRow(
            title = stringResource(R.string.st_settings_font_scale, (fontScale * 100).roundToInt()),
            desc = stringResource(R.string.st_settings_font_scale_desc),
            value = fontScale,
            range = 0.5f..1.5f,
            steps = 19,
            onValueChange = { fontScale = it },
            onValueChangeFinished = {
                val rounded = (round(fontScale * 100) / 100f).coerceIn(0.5f, 1.5f)
                fontScale = rounded
                userSettings.fontScale = rounded
                onSaveUserSettings()
            },
        )

        SettingSwitchRow(
            title = stringResource(R.string.st_settings_timestamps),
            desc = stringResource(R.string.st_settings_timestamps_desc),
            checked = timestampsEnabled,
            onCheckedChange = {
                timestampsEnabled = it
                userSettings.timestampsEnabled = it
                onSaveUserSettings()
            },
        )

        val avatarStyleLabel = when (userSettings.avatarStyle) {
            StUserSettings.AVATAR_STYLE_RECTANGULAR -> stringResource(R.string.st_settings_avatar_style_rectangular)
            StUserSettings.AVATAR_STYLE_SQUARE -> stringResource(R.string.st_settings_avatar_style_square)
            StUserSettings.AVATAR_STYLE_ROUNDED -> stringResource(R.string.st_settings_avatar_style_rounded)
            else -> stringResource(R.string.st_settings_avatar_style_round)
        }
        SettingPickerRow(
            title = stringResource(R.string.st_settings_avatar_style),
            desc = stringResource(R.string.st_settings_avatar_style_desc),
            currentValueText = avatarStyleLabel,
            buttonText = stringResource(R.string.btn_change),
            onClick = { onRequestDialog(SettingsDialogType.AVATAR_STYLE) },
        )

        SettingSwitchRow(
            title = stringResource(R.string.st_settings_fullres_avatars),
            desc = stringResource(R.string.st_settings_fullres_avatars_desc),
            checked = neverResizeAvatars,
            onCheckedChange = {
                neverResizeAvatars = it
                userSettings.neverResizeAvatars = it
                onSaveUserSettings()
            },
        )
    }
}
