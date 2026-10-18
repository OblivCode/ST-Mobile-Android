package app.stmobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text(stringResource(R.string.webview_offscreen_raster), color = Color(0xFFD8DEE6))
                Text(
                    stringResource(R.string.webview_offscreen_raster_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF8A94A3),
                )
            }
            Switch(
                checked = offscreenPreRaster,
                onCheckedChange = {
                    offscreenPreRaster = it
                    appConfig.webViewOffscreenPreRaster = it
                },
            )
        }

        // Network & Hardware
        SettingsCategoryHeader(stringResource(R.string.webview_category_network))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text(stringResource(R.string.webview_safe_browsing), color = Color(0xFFD8DEE6))
                Text(
                    stringResource(R.string.webview_safe_browsing_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF8A94A3),
                )
            }
            Switch(
                checked = safeBrowsing,
                onCheckedChange = {
                    safeBrowsing = it
                    appConfig.webViewSafeBrowsingEnabled = it
                },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text(stringResource(R.string.webview_geolocation), color = Color(0xFFD8DEE6))
                Text(
                    stringResource(R.string.webview_geolocation_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF8A94A3),
                )
            }
            Switch(
                checked = geolocation,
                onCheckedChange = {
                    geolocation = it
                    appConfig.webViewGeolocationEnabled = it
                },
            )
        }

        // Media & Power
        SettingsCategoryHeader(stringResource(R.string.webview_category_media))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text(stringResource(R.string.webview_media_gesture), color = Color(0xFFD8DEE6))
                Text(
                    stringResource(R.string.webview_media_gesture_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF8A94A3),
                )
            }
            Switch(
                checked = mediaGesture,
                onCheckedChange = {
                    mediaGesture = it
                    appConfig.webViewMediaGestureRequired = it
                },
            )
        }
    }
}
