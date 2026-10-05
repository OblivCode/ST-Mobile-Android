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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.stmobile.models.AppConfig
import kotlinx.coroutines.delay

@Composable
fun StWebView(
    url: String?,
    onNavigateToDashboard: () -> Unit,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    onNavigateToSettings: () -> Unit = {},
    onBackToDashboard: () -> Unit = onNavigateToDashboard,
) {
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

    BackHandler(enabled = visible) {
        val wv = webViewInstance
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else {
            onBackToDashboard()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewInstance?.destroy()
            webViewInstance = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
    ) {
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

        var handleOffsetY by remember { mutableStateOf<Dp?>(null) }

        if (visible) {
            EdgeDockedMenuHandle(
                webViewInstance = webViewInstance,
                onNavigateToDashboard = onNavigateToDashboard,
                onNavigateToSettings = onNavigateToSettings,
                initialOffsetY = handleOffsetY,
                onOffsetYChange = { handleOffsetY = it },
            )
        }
    }
}

@Composable
fun EdgeDockedMenuHandle(
    webViewInstance: WebView?,
    onNavigateToDashboard: () -> Unit,
    onNavigateToSettings: () -> Unit,
    initialOffsetY: Dp?,
    onOffsetYChange: (Dp) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appConfig = remember(context) { AppConfig(context) }
    var isIdle by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }
    var isExpanded by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    LaunchedEffect(isPressed, isDragging, isExpanded) {
        if (isPressed || isDragging || isExpanded) {
            isIdle = false
        } else {
            delay(3000)
            isIdle = true
        }
    }

    val handleAlpha by animateFloatAsState(
        targetValue = if (isIdle) 0.35f else 0.95f,
        animationSpec = tween(durationMillis = 300),
        label = "edge_handle_alpha",
    )

    val translationX by animateDpAsState(
        targetValue = if (isIdle) 14.dp else 0.dp,
        animationSpec = tween(durationMillis = 300),
        label = "edge_handle_translation_x",
    )

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val handleHeight = 56.dp
        val minBound = 16.dp
        val maxBound = (maxHeight - handleHeight - 16.dp).coerceAtLeast(minBound)
        val currentY = (initialOffsetY ?: ((maxHeight - handleHeight) / 2)).coerceIn(minBound, maxBound)

        if (isExpanded) {
            BackHandler {
                isExpanded = false
            }

            // Full-screen tap-outside scrim to dismiss the toolbar
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { isExpanded = false },
                    ),
            )
        }

        AnimatedVisibility(
            visible = !isExpanded,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(150)),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(y = currentY),
        ) {
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        this.translationX = translationX.toPx()
                        this.alpha = handleAlpha
                    }
                    .systemGestureExclusion()
                    .width(32.dp)
                    .height(handleHeight)
                    .background(
                        color = Color(0xDD23303A),
                        shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
                    )
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { deltaPx ->
                            val deltaDp = with(density) { deltaPx.toDp() }
                            onOffsetYChange((currentY + deltaDp).coerceIn(minBound, maxBound))
                        },
                        onDragStarted = { isDragging = true },
                        onDragStopped = { isDragging = false },
                    )
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = {
                            if (appConfig.webViewMenuQuickToolbar) {
                                isExpanded = true
                            } else {
                                onNavigateToDashboard()
                            }
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = "Menu",
                    tint = Color(0xFF7EC8A9),
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = fadeIn(tween(150)) + slideInHorizontally(tween(200)) { it },
            exit = fadeOut(tween(150)) + slideOutHorizontally(tween(200)) { it },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(y = currentY),
        ) {
            Surface(
                modifier = Modifier.systemGestureExclusion(),
                shape = RoundedCornerShape(
                    topStart = 24.dp,
                    bottomStart = 24.dp,
                    topEnd = 0.dp,
                    bottomEnd = 0.dp,
                ),
                color = Color(0xF023303A),
                contentColor = Color(0xFF7EC8A9),
                tonalElevation = 8.dp,
                shadowElevation = 8.dp,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    IconButton(
                        onClick = {
                            isExpanded = false
                            onNavigateToDashboard()
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Dashboard,
                            contentDescription = "Dashboard",
                            tint = Color(0xFF7EC8A9),
                        )
                    }
                    IconButton(
                        onClick = {
                            isExpanded = false
                            webViewInstance?.reload()
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Reload",
                            tint = Color(0xFF7EC8A9),
                        )
                    }
                    IconButton(
                        onClick = {
                            isExpanded = false
                            onNavigateToSettings()
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = Color(0xFF7EC8A9),
                        )
                    }
                    IconButton(
                        onClick = {
                            isExpanded = false
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color(0xFFB0BEC5),
                        )
                    }
                }
            }
        }
    }
}
