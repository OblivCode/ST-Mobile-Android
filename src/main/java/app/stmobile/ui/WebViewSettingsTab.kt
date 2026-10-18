package app.stmobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.stmobile.R
import app.stmobile.models.AppConfig

@Composable
fun WebViewSettingsTab(
    appConfig: AppConfig,
    modifier: Modifier = Modifier,
) {
    var offscreenPreRaster by remember { mutableStateOf(appConfig.webViewOffscreenPreRaster) }
    var safeBrowsing by remember { mutableStateOf(appConfig.webViewSafeBrowsingEnabled) }
    var geolocation by remember { mutableStateOf(appConfig.webViewGeolocationEnabled) }
    var mediaGesture by remember { mutableStateOf(appConfig.webViewMediaGestureRequired) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Display & Rendering
        SettingsCategoryHeader(stringResource(R.string.webview_category_rendering))

        SettingSwitchRow(
            title = stringResource(R.string.webview_offscreen_raster),
            desc = stringResource(R.string.webview_offscreen_raster_desc),
            checked = offscreenPreRaster,
            onCheckedChange = {
                offscreenPreRaster = it
                appConfig.webViewOffscreenPreRaster = it
            },
        )

        // Network & Hardware
        SettingsCategoryHeader(stringResource(R.string.webview_category_network))

        SettingSwitchRow(
            title = stringResource(R.string.webview_safe_browsing),
            desc = stringResource(R.string.webview_safe_browsing_desc),
            checked = safeBrowsing,
            onCheckedChange = {
                safeBrowsing = it
                appConfig.webViewSafeBrowsingEnabled = it
            },
        )

        SettingSwitchRow(
            title = stringResource(R.string.webview_geolocation),
            desc = stringResource(R.string.webview_geolocation_desc),
            checked = geolocation,
            onCheckedChange = {
                geolocation = it
                appConfig.webViewGeolocationEnabled = it
            },
        )

        // Media & Power
        SettingsCategoryHeader(stringResource(R.string.webview_category_media))

        SettingSwitchRow(
            title = stringResource(R.string.webview_media_gesture),
            desc = stringResource(R.string.webview_media_gesture_desc),
            checked = mediaGesture,
            onCheckedChange = {
                mediaGesture = it
                appConfig.webViewMediaGestureRequired = it
            },
        )
    }
}
