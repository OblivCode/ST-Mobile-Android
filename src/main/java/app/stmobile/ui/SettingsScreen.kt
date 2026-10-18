package app.stmobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults.SecondaryIndicator
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import app.stmobile.ui.theme.ThemeMode
import java.util.Locale
import kotlin.math.round
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    onPromptBattery: () -> Unit,
    modifier: Modifier = Modifier,
    onExportLogs: () -> Unit = {},
    onCopyDiagnostics: () -> Unit = {},
    onThemeChanged: (ThemeMode) -> Unit = {},
) {
    val context = LocalContext.current
    val appConfig = remember(context) { AppConfig(context) }
    val userSettings = remember(context) { StUserSettings.load(context) }

    fun saveUserSettings() {
        runCatching {
            userSettings.save()
        }.onFailure { e ->
            Utils.toast(context, context.getString(R.string.toast_save_settings_failed, e.message ?: ""))
        }
    }

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchFilterTab by rememberSaveable { mutableStateOf(SettingsSearchTab.ALL) }
    var activeDialog by rememberSaveable { mutableStateOf(SettingsDialogType.NONE) }

    val currentLocales = LocaleManagerCompat.getApplicationLocales(context)
    val currentAppLocale = if (!currentLocales.isEmpty) currentLocales[0] ?: Locale.getDefault() else Locale.getDefault()
    val currentAppTag = currentAppLocale.language
    val effectiveStLocaleCode = StLocales.getEffectiveLocale(userSettings.uiLanguage, appConfig.autoSyncLanguage, currentAppLocale)
    val currentAppStCode = StLocales.resolveSystemLocale(currentAppLocale)

    val tabTitles = listOf(
        stringResource(R.string.tab_app),
        stringResource(R.string.tab_sillytavern),
        stringResource(R.string.tab_node_engine),
        stringResource(R.string.tab_webview),
    )

    // Build search index across all 4 tabs
    val searchItems = remember(
        appConfig.autoSyncLanguage,
        appConfig.themeMode,
        appConfig.autoStartOnAppOpen,
        userSettings.uiLanguage,
        userSettings.autoLoadChat,
        userSettings.smoothStreaming,
        userSettings.chatWidth,
        userSettings.avatarStyle,
    ) {
        listOf(
            // App Tab Items
            SettingsSearchItem("app_sync_lang", SettingsSearchTab.APP, "Language", "Auto-sync with SillyTavern", "Inherit SillyTavern UI language", listOf("language", "locale", "sync")),
            SettingsSearchItem("app_language", SettingsSearchTab.APP, "Language", "App Language", "Select app shell interface language", listOf("language", "locale", "translate")),
            SettingsSearchItem("app_theme", SettingsSearchTab.APP, "Theme", "App Theme", "Select dark, light, or auto-sync with device theme", listOf("theme", "dark", "light", "mode", "appearance")),
            SettingsSearchItem("app_auto_start", SettingsSearchTab.APP, "Server", "Auto-start server on app launch", "Automatically launch Node.js service", listOf("server", "start", "boot")),
            SettingsSearchItem("app_auto_launch", SettingsSearchTab.APP, "Server", "Auto-open web view", "Open web view when server starts", listOf("webview", "launch", "open")),
            SettingsSearchItem("app_port_fallback", SettingsSearchTab.APP, "Server", "Auto fallback port if occupied", "Use alternative port if default is bound", listOf("port", "network", "fallback")),
            SettingsSearchItem("app_bg_timeout", SettingsSearchTab.APP, "Power", "Background idle timeout", "Minutes before stopping inactive server", listOf("timeout", "power", "idle", "battery")),
            SettingsSearchItem("app_notify_text", SettingsSearchTab.APP, "Notifications", "Text generation alerts", "Show alert when text generation finishes", listOf("notifications", "alerts", "text", "completion")),
            SettingsSearchItem("app_notify_vector", SettingsSearchTab.APP, "Notifications", "Vector indexing alerts", "Show alert when vector indexing finishes", listOf("notifications", "alerts", "vector", "embedding")),
            SettingsSearchItem("app_notify_timeout", SettingsSearchTab.APP, "Notifications", "Notification auto-dismiss timeout", "Duration before heads-up alert closes", listOf("notifications", "timeout", "duration")),
            SettingsSearchItem("app_quick_toolbar", SettingsSearchTab.APP, "Navigation", "Quick action pill", "Show floating menu pill in web view", listOf("pill", "menu", "floating", "navigation")),
            SettingsSearchItem("app_reset_pill", SettingsSearchTab.APP, "Navigation", "Reset Pill Position", "Return floating menu pill to center", listOf("pill", "reset", "position")),
            SettingsSearchItem("app_diagnostics", SettingsSearchTab.APP, "Diagnostics", "Diagnostics & Logs", "Export logs or copy system diagnostics", listOf("logs", "diagnostics", "troubleshooting", "debug")),

            // SillyTavern Tab Items
            SettingsSearchItem("st_language", SettingsSearchTab.SILLYTAVERN, "Language", "UI Language", "SillyTavern web client interface language", listOf("language", "locale", "translation")),
            SettingsSearchItem("st_auto_load_chat", SettingsSearchTab.SILLYTAVERN, "Chat & Input", "Auto load last chat", "Resume last chat on launch", listOf("chat", "load", "history")),
            SettingsSearchItem("st_auto_connect", SettingsSearchTab.SILLYTAVERN, "Chat & Input", "Auto connect API", "Connect backend on launch", listOf("api", "connect", "backend")),
            SettingsSearchItem("st_compact_input", SettingsSearchTab.SILLYTAVERN, "Chat & Input", "Compact input area", "Single-row input when keyboard is open", listOf("input", "compact", "keyboard")),
            SettingsSearchItem("st_send_on_enter", SettingsSearchTab.SILLYTAVERN, "Chat & Input", "Send on Enter", "Configure Enter key behavior", listOf("enter", "keyboard", "send")),
            SettingsSearchItem("st_confirm_delete", SettingsSearchTab.SILLYTAVERN, "Chat & Input", "Confirm message deletion", "Ask for confirmation before deleting chat messages", listOf("delete", "confirm", "message")),
            SettingsSearchItem("st_click_to_edit", SettingsSearchTab.SILLYTAVERN, "Chat & Input", "Click message to edit", "Tap message bubble directly to open edit view", listOf("edit", "click", "bubble")),
            SettingsSearchItem("st_restore_input", SettingsSearchTab.SILLYTAVERN, "Chat & Input", "Restore unsent input", "Keep drafted message in input box across chat switches", listOf("input", "restore", "draft")),
            SettingsSearchItem("st_swipe_numbers", SettingsSearchTab.SILLYTAVERN, "Chat & Input", "Always show swipe count", "Display swipe number badges on all messages", listOf("swipe", "swipes", "counter", "badge")),
            SettingsSearchItem("st_smooth_streaming", SettingsSearchTab.SILLYTAVERN, "Streaming & Motion", "Smooth token streaming", "Buffer incoming tokens for smoother text flow", listOf("streaming", "smooth", "tokens", "animation")),
            SettingsSearchItem("st_streaming_fps", SettingsSearchTab.SILLYTAVERN, "Streaming & Motion", "Streaming refresh rate", "Token render animation frequency", listOf("fps", "refresh", "rate", "streaming", "speed")),
            SettingsSearchItem("st_auto_scroll", SettingsSearchTab.SILLYTAVERN, "Streaming & Motion", "Auto-scroll to bottom", "Scroll chat container downward as new tokens arrive", listOf("scroll", "bottom", "auto")),
            SettingsSearchItem("st_fast_ui", SettingsSearchTab.SILLYTAVERN, "Streaming & Motion", "Fast UI mode", "Disable blur effects for performance", listOf("blur", "fast", "performance", "ui")),
            SettingsSearchItem("st_reduced_motion", SettingsSearchTab.SILLYTAVERN, "Streaming & Motion", "Reduced motion", "Disable animations and transitions", listOf("motion", "animation", "transitions")),
            SettingsSearchItem("st_chat_width", SettingsSearchTab.SILLYTAVERN, "Display & Styling", "Chat bubble width", "Horizontal screen width consumed by chat bubbles", listOf("width", "chat", "bubbles", "layout")),
            SettingsSearchItem("st_font_scale", SettingsSearchTab.SILLYTAVERN, "Display & Styling", "Font scaling", "Adjust UI font scale", listOf("font", "scale", "size", "text")),
            SettingsSearchItem("st_timestamps", SettingsSearchTab.SILLYTAVERN, "Display & Styling", "Message timestamps", "Display date and time header above chat messages", listOf("time", "timestamps", "date", "clock")),
            SettingsSearchItem("st_avatar_style", SettingsSearchTab.SILLYTAVERN, "Display & Styling", "Avatar shape", "Border geometry for character and user avatars", listOf("avatar", "round", "square", "shape")),
            SettingsSearchItem("st_fullres_avatars", SettingsSearchTab.SILLYTAVERN, "Display & Styling", "Full-resolution avatars", "Disable client-side downscaling of character avatars", listOf("avatar", "resolution", "quality", "hd")),

            // Node Engine Tab Items
            SettingsSearchItem("node_max_heap", SettingsSearchTab.NODE_ENGINE, "Memory & V8", "V8 Maximum Heap Size", "V8 engine maximum old generation memory limit", listOf("memory", "ram", "heap", "v8", "node")),
            SettingsSearchItem("node_young_gen", SettingsSearchTab.NODE_ENGINE, "Memory & V8", "Young Gen Nursery", "V8 semi-space size for temporary allocations", listOf("memory", "nursery", "semi-space")),
            SettingsSearchItem("node_optimize_size", SettingsSearchTab.NODE_ENGINE, "Memory & V8", "Low Memory Footprint Mode", "Enables --optimize-for-size", listOf("memory", "ram", "size", "gc")),
            SettingsSearchItem("node_threadpool", SettingsSearchTab.NODE_ENGINE, "Concurrency", "libuv Worker Threadpool", "Threads allocated for async disk I/O and crypto", listOf("threads", "libuv", "concurrency")),
            SettingsSearchItem("node_env", SettingsSearchTab.NODE_ENGINE, "Environment", "Runtime Environment (NODE_ENV)", "Production enables internal template and route caching", listOf("env", "environment", "production")),
            SettingsSearchItem("node_options", SettingsSearchTab.NODE_ENGINE, "Environment", "Extra Node.js Options", "Additional V8 or Node CLI flags passed on startup", listOf("options", "flags", "cli", "v8")),

            // WebView Tab Items
            SettingsSearchItem("wv_raster", SettingsSearchTab.WEBVIEW, "Rendering", "Offscreen pre-rasterization", "Pre-render hidden cards outside viewport", listOf("raster", "gpu", "rendering", "performance")),
            SettingsSearchItem("wv_safe_browsing", SettingsSearchTab.WEBVIEW, "Network", "Safe Browsing on localhost", "Checks 127.0.0.1 against Google database", listOf("security", "safe browsing", "network")),
            SettingsSearchItem("wv_geolocation", SettingsSearchTab.WEBVIEW, "Network", "Geolocation hardware API", "Allow web extensions to access GPS/GNSS receiver", listOf("gps", "geolocation", "location")),
            SettingsSearchItem("wv_media_gesture", SettingsSearchTab.WEBVIEW, "Media", "Require gesture for media playback", "Prevents background audio drain", listOf("audio", "gesture", "tts", "media")),
        )
    }

    val searchEngine = remember(searchItems) { SettingsSearchEngine(searchItems) }
    val isSearchActive = searchQuery.isNotBlank()
    val searchResults = remember(searchQuery, searchFilterTab, searchEngine) {
        if (isSearchActive) searchEngine.search(searchQuery, searchFilterTab) else emptyList()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        // Top Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text(stringResource(R.string.settings_search_placeholder)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = stringResource(R.string.settings_search_clear),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        if (isSearchActive) {
            // Filter Chips Row
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(SettingsSearchTab.entries) { tab ->
                    val label = when (tab) {
                        SettingsSearchTab.ALL -> stringResource(R.string.settings_search_all)
                        SettingsSearchTab.APP -> stringResource(R.string.tab_app)
                        SettingsSearchTab.SILLYTAVERN -> stringResource(R.string.tab_sillytavern)
                        SettingsSearchTab.NODE_ENGINE -> stringResource(R.string.tab_node_engine)
                        SettingsSearchTab.WEBVIEW -> stringResource(R.string.tab_webview)
                    }
                    FilterChip(
                        selected = searchFilterTab == tab,
                        onClick = { searchFilterTab = tab },
                        label = { Text(label) },
                    )
                }
            }

            if (searchResults.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = stringResource(R.string.settings_search_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 12.dp),
                ) {
                    items(searchResults, key = { it.id }) { item ->
                        SettingSearchCard(
                            tabLabel = when (item.tab) {
                                SettingsSearchTab.APP -> stringResource(R.string.tab_app)
                                SettingsSearchTab.SILLYTAVERN -> stringResource(R.string.tab_sillytavern)
                                SettingsSearchTab.NODE_ENGINE -> stringResource(R.string.tab_node_engine)
                                SettingsSearchTab.WEBVIEW -> stringResource(R.string.tab_webview)
                                else -> ""
                            },
                            categoryLabel = item.category,
                        ) {
                            when (item.id) {
                                "app_theme" -> {
                                    val themeLabel = when (appConfig.themeMode) {
                                        ThemeMode.LIGHT.code -> stringResource(R.string.settings_theme_light)
                                        ThemeMode.DARK.code -> stringResource(R.string.settings_theme_dark)
                                        else -> stringResource(R.string.settings_theme_system)
                                    }
                                    SettingPickerRow(
                                        title = item.title,
                                        desc = item.description,
                                        currentValueText = themeLabel,
                                        buttonText = stringResource(R.string.btn_change),
                                        onClick = { activeDialog = SettingsDialogType.THEME },
                                    )
                                }
                                "app_language" -> {
                                    val appLocaleName = StLocales.findAppLocale(currentAppTag)?.let { "${it.nativeName} (${it.englishName})" }
                                        ?: stringResource(R.string.locale_system_default)
                                    SettingPickerRow(
                                        title = item.title,
                                        desc = item.description,
                                        currentValueText = appLocaleName,
                                        buttonText = stringResource(R.string.btn_change),
                                        onClick = { activeDialog = SettingsDialogType.APP_LANGUAGE },
                                    )
                                }
                                "app_sync_lang" -> {
                                    var autoSync by remember { mutableStateOf(appConfig.autoSyncLanguage) }
                                    SettingSwitchRow(
                                        title = item.title,
                                        desc = item.description,
                                        checked = autoSync,
                                        onCheckedChange = {
                                            autoSync = it
                                            appConfig.autoSyncLanguage = it
                                        },
                                    )
                                }
                                "app_auto_start" -> {
                                    var autoStart by remember { mutableStateOf(appConfig.autoStartOnAppOpen) }
                                    SettingSwitchRow(
                                        title = item.title,
                                        desc = item.description,
                                        checked = autoStart,
                                        onCheckedChange = {
                                            autoStart = it
                                            appConfig.autoStartOnAppOpen = it
                                        },
                                    )
                                }
                                "app_auto_launch" -> {
                                    var autoLaunch by remember { mutableStateOf(appConfig.autoLaunchWebViewOnStart) }
                                    SettingSwitchRow(
                                        title = item.title,
                                        desc = item.description,
                                        checked = autoLaunch,
                                        onCheckedChange = {
                                            autoLaunch = it
                                            appConfig.autoLaunchWebViewOnStart = it
                                        },
                                    )
                                }
                                "app_port_fallback" -> {
                                    var portFallback by remember { mutableStateOf(appConfig.autoPortFallback) }
                                    SettingSwitchRow(
                                        title = item.title,
                                        desc = item.description,
                                        checked = portFallback,
                                        onCheckedChange = {
                                            portFallback = it
                                            appConfig.autoPortFallback = it
                                        },
                                    )
                                }
                                "app_quick_toolbar" -> {
                                    var toolbar by remember { mutableStateOf(appConfig.webViewMenuQuickToolbar) }
                                    SettingSwitchRow(
                                        title = item.title,
                                        desc = item.description,
                                        checked = toolbar,
                                        onCheckedChange = {
                                            toolbar = it
                                            appConfig.webViewMenuQuickToolbar = it
                                        },
                                    )
                                }
                                "app_reset_pill" -> {
                                    androidx.compose.material3.OutlinedButton(
                                        onClick = {
                                            appConfig.webViewMenuVerticalFraction = 0.5f
                                            Utils.toast(context, context.getString(R.string.toast_pill_position_reset))
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(stringResource(R.string.btn_reset_pill_position))
                                    }
                                }
                                "app_notify_text" -> {
                                    var notifyText by remember { mutableStateOf(appConfig.notifyOnTextCompletion) }
                                    SettingSwitchRow(
                                        title = item.title,
                                        desc = item.description,
                                        checked = notifyText,
                                        onCheckedChange = {
                                            notifyText = it
                                            appConfig.notifyOnTextCompletion = it
                                        },
                                    )
                                }
                                "app_notify_vector" -> {
                                    var notifyVector by remember { mutableStateOf(appConfig.notifyOnVectorCompletion) }
                                    SettingSwitchRow(
                                        title = item.title,
                                        desc = item.description,
                                        checked = notifyVector,
                                        onCheckedChange = {
                                            notifyVector = it
                                            appConfig.notifyOnVectorCompletion = it
                                        },
                                    )
                                }
                                "app_notify_timeout" -> {
                                    val timeoutDisplay = if (appConfig.notificationTimeoutSeconds == 0) {
                                        stringResource(R.string.settings_notification_timeout_never)
                                    } else {
                                        stringResource(R.string.settings_notification_timeout_seconds, appConfig.notificationTimeoutSeconds)
                                    }
                                    SettingPickerRow(
                                        title = item.title,
                                        currentValueText = timeoutDisplay,
                                        buttonText = stringResource(R.string.btn_change),
                                        onClick = { activeDialog = SettingsDialogType.NOTIFICATION_TIMEOUT },
                                    )
                                }
                                "st_language" -> {
                                    val effectiveStLocale = StLocales.findLocale(effectiveStLocaleCode)
                                    val displayName = effectiveStLocale?.let { "${it.nativeName} (${it.englishName})" } ?: effectiveStLocaleCode
                                    SettingPickerRow(
                                        title = item.title,
                                        currentValueText = displayName,
                                        buttonText = stringResource(R.string.btn_change),
                                        enabled = !appConfig.autoSyncLanguage,
                                        onClick = { activeDialog = SettingsDialogType.ST_LANGUAGE },
                                    )
                                }
                                "st_send_on_enter" -> {
                                    val enterText = when (userSettings.sendOnEnter) {
                                        StUserSettings.SEND_ON_ENTER_DISABLED -> stringResource(R.string.st_settings_send_disabled)
                                        StUserSettings.SEND_ON_ENTER_ENABLED -> stringResource(R.string.st_settings_send_enabled)
                                        else -> stringResource(R.string.st_settings_send_auto)
                                    }
                                    SettingPickerRow(
                                        title = item.title,
                                        currentValueText = enterText,
                                        buttonText = stringResource(R.string.btn_change),
                                        onClick = { activeDialog = SettingsDialogType.SEND_ON_ENTER },
                                    )
                                }
                                "st_avatar_style" -> {
                                    val styleLabel = when (userSettings.avatarStyle) {
                                        StUserSettings.AVATAR_STYLE_RECTANGULAR -> stringResource(R.string.st_settings_avatar_style_rectangular)
                                        StUserSettings.AVATAR_STYLE_SQUARE -> stringResource(R.string.st_settings_avatar_style_square)
                                        StUserSettings.AVATAR_STYLE_ROUNDED -> stringResource(R.string.st_settings_avatar_style_rounded)
                                        else -> stringResource(R.string.st_settings_avatar_style_round)
                                    }
                                    SettingPickerRow(
                                        title = item.title,
                                        currentValueText = styleLabel,
                                        buttonText = stringResource(R.string.btn_change),
                                        onClick = { activeDialog = SettingsDialogType.AVATAR_STYLE },
                                    )
                                }
                                "st_smooth_streaming" -> {
                                    var smooth by remember { mutableStateOf(userSettings.smoothStreaming) }
                                    SettingSwitchRow(
                                        title = item.title,
                                        desc = item.description,
                                        checked = smooth,
                                        onCheckedChange = {
                                            smooth = it
                                            userSettings.smoothStreaming = it
                                            saveUserSettings()
                                        },
                                    )
                                }
                                "st_streaming_fps" -> {
                                    var fps by remember { mutableIntStateOf(userSettings.streamingFps) }
                                    SettingSliderRow(
                                        title = stringResource(R.string.st_settings_streaming_fps, fps),
                                        desc = item.description,
                                        value = fps.toFloat(),
                                        range = 5f..100f,
                                        steps = 18,
                                        onValueChange = { fps = (round(it / 5f) * 5f).toInt() },
                                        onValueChangeFinished = {
                                            userSettings.streamingFps = fps
                                            saveUserSettings()
                                        },
                                    )
                                }
                                "st_chat_width" -> {
                                    var width by remember { mutableIntStateOf(userSettings.chatWidth) }
                                    SettingSliderRow(
                                        title = stringResource(R.string.st_settings_chat_width, width),
                                        desc = item.description,
                                        value = width.toFloat(),
                                        range = 25f..100f,
                                        steps = 14,
                                        onValueChange = { width = (round(it / 5f) * 5f).toInt() },
                                        onValueChangeFinished = {
                                            userSettings.chatWidth = width
                                            saveUserSettings()
                                        },
                                    )
                                }
                                "st_font_scale" -> {
                                    var fontScale by remember { mutableStateOf(userSettings.fontScale) }
                                    SettingSliderRow(
                                        title = stringResource(R.string.st_settings_font_scale, (fontScale * 100).roundToInt()),
                                        desc = item.description,
                                        value = fontScale,
                                        range = 0.5f..1.5f,
                                        steps = 19,
                                        onValueChange = { fontScale = it },
                                        onValueChangeFinished = {
                                            val rounded = (round(fontScale * 100) / 100f).coerceIn(0.5f, 1.5f)
                                            fontScale = rounded
                                            userSettings.fontScale = rounded
                                            saveUserSettings()
                                        },
                                    )
                                }
                                else -> {
                                    // Fallback info row with action if matched
                                    Text(
                                        text = item.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    if (item.description.isNotEmpty()) {
                                        Text(
                                            text = item.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // Standard Tab View
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                edgePadding = 16.dp,
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.primary,
                indicator = { tabPositions ->
                    if (selectedTab < tabPositions.size) {
                        SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
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
                                color = if (selectedTab == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }

            when (selectedTab) {
                0 -> AppSettingsTab(
                    appConfig = appConfig,
                    userSettings = userSettings,
                    currentAppTag = currentAppTag,
                    onPromptBattery = onPromptBattery,
                    onRequestDialog = { activeDialog = it },
                    onExportLogs = onExportLogs,
                    onCopyDiagnostics = onCopyDiagnostics,
                )
                1 -> SillyTavernSettingsTab(
                    userSettings = userSettings,
                    appConfig = appConfig,
                    onSaveUserSettings = { saveUserSettings() },
                    onRequestDialog = { activeDialog = it },
                    effectiveStLocaleCode = effectiveStLocaleCode,
                    currentAppStCode = currentAppStCode,
                )
                2 -> NodeEngineSettingsTab()
                3 -> WebViewSettingsTab(appConfig = appConfig)
            }
        }
    }

    // Hoisted Root Dialog Host
    SettingsDialogHost(
        activeDialog = activeDialog,
        onDismiss = { activeDialog = SettingsDialogType.NONE },
        appConfig = appConfig,
        userSettings = userSettings,
        onSaveUserSettings = { saveUserSettings() },
        currentAppTag = currentAppTag,
        effectiveStLocaleCode = effectiveStLocaleCode,
        onThemeChanged = onThemeChanged,
    )
}
