package app.stmobile.ui

import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

@Composable
fun StWebView(
    webView: WebView,
    onNavigateToDashboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isIdle by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // Auto-dim button after 3.5 seconds of idle
        delay(3500)
        isIdle = true
    }

    val alpha by animateFloatAsState(
        targetValue = if (isIdle) 0.2f else 1.0f,
        animationSpec = tween(durationMillis = 300),
        label = "menu_alpha",
    )

    BackHandler {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            onNavigateToDashboard()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { webView },
            modifier = Modifier.fillMaxSize(),
        )

        // Floating auto-dimming menu button that directly returns to the Dashboard
        FloatingActionButton(
            onClick = {
                onNavigateToDashboard()
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .alpha(alpha),
            containerColor = Color(0xCC23303A),
            contentColor = Color(0xFF7EC8A9),
        ) {
            Text("Menu")
        }
    }
}
