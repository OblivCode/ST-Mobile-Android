package app.stmobile.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import app.stmobile.foreground.ForegroundService
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
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.LocaleManagerCompat
import app.stmobile.R
import app.stmobile.models.AppConfig
import app.stmobile.models.StLocales
import app.stmobile.models.StUserSettings
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import org.json.JSONObject

@Composable
fun StWebView(
    url: String?,
    onNavigateToDashboard: () -> Unit,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    onNavigateToSettings: () -> Unit = {},
    onBackToDashboard: () -> Unit = onNavigateToDashboard,
    onStopServer: () -> Unit = {},
) {
    val context = LocalContext.current
    val appConfig = remember(context) { AppConfig(context) }
    val userSettings = remember(context) { StUserSettings.load(context) }
    val currentLocales = LocaleManagerCompat.getApplicationLocales(context)
    val currentAppLocale = if (!currentLocales.isEmpty) currentLocales[0] ?: Locale.getDefault() else Locale.getDefault()
    val effectiveStLanguage = StLocales.getEffectiveLocale(userSettings.uiLanguage, appConfig.autoSyncLanguage, currentAppLocale)

    var pendingFileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var didInitialReload by remember { mutableStateOf(false) }
    val urlHolder = remember {
        object {
            var lastLoadedBaseUrl: String? = null
            var pendingUrl: String? = null
        }
    }
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
            ForegroundService.onGenerationEnded("webview")
            pendingFileCallback?.onReceiveValue(null)
            pendingFileCallback = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF101418))
            .safeDrawingPadding(),
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

                    addJavascriptInterface(GenerationJavascriptBridge(), "StMobileBridge")

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val uri = request?.url ?: return false
                            val host = uri.host
                            if (host == "127.0.0.1" || host == "localhost") {
                                return false
                            }
                            return try {
                                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                view?.context?.startActivity(intent)
                                true
                            } catch (e: Exception) {
                                Log.w("st-web", "Failed to launch external browser for $uri", e)
                                true
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                            val uri = url?.let { Uri.parse(it) } ?: return false
                            val host = uri.host
                            if (host == "127.0.0.1" || host == "localhost") {
                                return false
                            }
                            return try {
                                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                view?.context?.startActivity(intent)
                                true
                            } catch (e: Exception) {
                                Log.w("st-web", "Failed to launch external browser for $uri", e)
                                true
                            }
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            ForegroundService.onGenerationEnded("webview")
                        }

                        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                            super.onPageFinished(view, finishedUrl)
                            if (!didInitialReload) {
                                didInitialReload = true
                                view?.postDelayed({
                                    if (view.isAttachedToWindow && webViewInstance == view) {
                                        view.loadUrl(finishedUrl ?: "about:blank")
                                    }
                                }, 250)
                            }
                            // Synchronize SillyTavern UI language with the effective locale
                            val quotedLanguage = JSONObject.quote(effectiveStLanguage)
                            val syncJs = """
                                (function() {
                                    try {
                                        var desired = $quotedLanguage;
                                        var current = localStorage.getItem('language');
                                        if (current !== desired) {
                                            localStorage.setItem('language', desired);
                                            location.reload();
                                        }
                                    } catch(e) {}
                                })();
                            """.trimIndent()
                            view?.evaluateJavascript(syncJs, null)

                            // Subscribe to SillyTavern client-side generation event bus
                            val bridgeJs = """
                                (async function() {
                                    if (window.__stBridgeSubscribed) return;
                                    try {
                                        const { eventSource, event_types } = await import('/scripts/events.js');
                                        if (window.StMobileBridge && eventSource && event_types) {
                                            window.__stBridgeSubscribed = true;
                                            eventSource.on(event_types.GENERATION_STARTED, function() {
                                                window.StMobileBridge.onGenerationStarted();
                                            });
                                            eventSource.on(event_types.GENERATION_ENDED, function() {
                                                window.StMobileBridge.onGenerationEnded();
                                            });
                                            eventSource.on(event_types.GENERATION_STOPPED, function() {
                                                window.StMobileBridge.onGenerationEnded();
                                            });
                                        }
                                    } catch(e) {}
                                })();
                            """.trimIndent()
                            view?.evaluateJavascript(bridgeJs, null)
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
                        val queued = urlHolder.pendingUrl ?: return@addOnLayoutChangeListener
                        if (isAttachedToWindow && r - l > 0 && b - t > 0) {
                            urlHolder.pendingUrl = null
                            loadUrl(queued)
                        }
                    }

                    webViewInstance = this
                }
            },
            update = { wv ->
                // Use INVISIBLE instead of GONE to preserve full viewport dimensions without layout reflows
                wv.visibility = if (visible) View.VISIBLE else View.INVISIBLE
                if (url != null && url != urlHolder.lastLoadedBaseUrl) {
                    urlHolder.lastLoadedBaseUrl = url
                    if (wv.isAttachedToWindow && wv.width > 0 && wv.height > 0) {
                        wv.loadUrl(url)
                    } else {
                        urlHolder.pendingUrl = url
                    }
                }
            },
            onRelease = { wv ->
                pendingFileCallback?.onReceiveValue(null)
                pendingFileCallback = null
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.stopLoading()
                wv.removeJavascriptInterface("StMobileBridge")
                wv.clearHistory()
                wv.destroy()
                webViewInstance = null
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (visible) {
            EdgeDockedMenuHandle(
                webViewInstance = webViewInstance,
                onNavigateToDashboard = onNavigateToDashboard,
                onNavigateToSettings = onNavigateToSettings,
                onStopServer = onStopServer,
            )
        }
    }
}

@Composable
fun EdgeDockedMenuHandle(
    webViewInstance: WebView?,
    onNavigateToDashboard: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onStopServer: () -> Unit = {},
    modifier: Modifier = Modifier,
    initialOffsetY: Dp? = null,
    onOffsetYChange: ((Dp) -> Unit)? = null,
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
        val minBoundDp = 16.dp
        val maxBoundDp = (maxHeight - handleHeight - 16.dp).coerceAtLeast(minBoundDp)

        val minBoundPx = with(density) { minBoundDp.toPx() }
        val maxBoundPx = with(density) { maxBoundDp.toPx() }
        val defaultYPx = with(density) { (initialOffsetY ?: ((maxHeight - handleHeight) / 2)).toPx() }

        var offsetYPx by rememberSaveable {
            mutableFloatStateOf(defaultYPx.coerceIn(minBoundPx, maxBoundPx))
        }

        val clampedYPx = offsetYPx.coerceIn(minBoundPx, maxBoundPx)
        if (clampedYPx != offsetYPx) {
            offsetYPx = clampedYPx
        }

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
                .offset { IntOffset(0, offsetYPx.roundToInt()) },
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
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.88f),
                        shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
                    )
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { deltaPx ->
                            offsetYPx = (offsetYPx + deltaPx).coerceIn(minBoundPx, maxBoundPx)
                        },
                        onDragStarted = { isDragging = true },
                        onDragStopped = {
                            isDragging = false
                            onOffsetYChange?.invoke(with(density) { offsetYPx.toDp() })
                        },
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
                    contentDescription = stringResource(R.string.cd_menu),
                    tint = MaterialTheme.colorScheme.primary,
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
                .offset { IntOffset(0, offsetYPx.roundToInt()) },
        ) {
            Surface(
                modifier = Modifier.systemGestureExclusion(),
                shape = RoundedCornerShape(
                    topStart = 24.dp,
                    bottomStart = 24.dp,
                    topEnd = 0.dp,
                    bottomEnd = 0.dp,
                ),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
                contentColor = MaterialTheme.colorScheme.primary,
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
                            contentDescription = stringResource(R.string.cd_dashboard),
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
                            contentDescription = stringResource(R.string.cd_reload),
                        )
                    }
                    IconButton(
                        onClick = {
                            isExpanded = false
                            onStopServer()
                            onNavigateToDashboard()
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = stringResource(R.string.dashboard_btn_stop),
                            tint = MaterialTheme.colorScheme.error,
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
                            contentDescription = stringResource(R.string.cd_settings),
                        )
                    }
                    IconButton(
                        onClick = {
                            isExpanded = false
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.cd_close),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
