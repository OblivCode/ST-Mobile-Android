package app.stmobile.ui

import android.net.Uri
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
    url: String?,
    onNavigateToDashboard: () -> Unit,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
) {
    var isIdle by remember { mutableStateOf(false) }
    var pendingFileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var didInitialReload by remember { mutableStateOf(false) }
    var pendingUrl by remember { mutableStateOf<String?>(null) }
    var lastLoadedBaseUrl by remember { mutableStateOf<String?>(null) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }

    val fileChooser = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val cb = pendingFileCallback
        pendingFileCallback = null
        cb?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
    }

    LaunchedEffect(visible) {
        if (visible) {
            isIdle = false
            delay(3500)
            isIdle = true
        }
    }

    val fabAlpha by animateFloatAsState(
        targetValue = if (isIdle) 0.2f else 1.0f,
        animationSpec = tween(durationMillis = 300),
        label = "menu_alpha",
    )

    BackHandler(enabled = visible) {
        val wv = webViewInstance
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else {
            onNavigateToDashboard()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewInstance?.destroy()
            webViewInstance = null
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                            super.onPageFinished(view, finishedUrl)
                            if (!didInitialReload) {
                                didInitialReload = true
                                view?.postDelayed({ view.loadUrl(finishedUrl ?: "about:blank") }, 250)
                            }
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                            Log.i("st-web", "${message?.lineNumber()}: ${message?.message()}")
                            return true
                        }

                        override fun onShowFileChooser(
                            view: WebView?,
                            callback: ValueCallback<Array<Uri>>?,
                            params: FileChooserParams?,
                        ): Boolean {
                            pendingFileCallback?.onReceiveValue(null)
                            pendingFileCallback = callback
                            val mime = params?.acceptTypes?.firstOrNull { it.isNotBlank() } ?: "*/*"
                            return try {
                                fileChooser.launch(mime)
                                true
                            } catch (_: Exception) {
                                pendingFileCallback?.onReceiveValue(null)
                                pendingFileCallback = null
                                false
                            }
                        }
                    }

                    addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
                        val queued = pendingUrl ?: return@addOnLayoutChangeListener
                        if (r - l > 0 && b - t > 0) {
                            pendingUrl = null
                            loadUrl(queued)
                        }
                    }

                    webViewInstance = this
                }
            },
            update = { wv ->
                // Use INVISIBLE instead of GONE to preserve full viewport dimensions without layout reflows
                wv.visibility = if (visible) View.VISIBLE else View.INVISIBLE
                if (url != null && url != lastLoadedBaseUrl) {
                    lastLoadedBaseUrl = url
                    if (wv.width > 0 && wv.height > 0) {
                        wv.loadUrl(url)
                    } else {
                        pendingUrl = url
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (visible) {
            FloatingActionButton(
                onClick = onNavigateToDashboard,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .alpha(fabAlpha),
                containerColor = Color(0xCC23303A),
                contentColor = Color(0xFF7EC8A9),
            ) {
                Text("Menu")
            }
        }
    }
}
