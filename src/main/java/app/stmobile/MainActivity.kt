package app.stmobile

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.FileProvider
import app.stmobile.models.AppConfig
import app.stmobile.models.NodeState
import app.stmobile.models.NodeStatus
import app.stmobile.models.NodeStatusListener
import app.stmobile.node.NodeController
import app.stmobile.node.NodeService
import app.stmobile.sillytavern.BackupManager
import app.stmobile.sillytavern.PayloadManager
import app.stmobile.ui.DashboardScreen
import app.stmobile.ui.SettingsScreen
import app.stmobile.ui.SetupScreen
import app.stmobile.ui.StWebView

enum class ActiveScreen { SETUP, DASHBOARD, SETTINGS, WEBVIEW }

class MainActivity : ComponentActivity(), NodeStatusListener {

    private lateinit var webView: WebView
    private var service: NodeService? = null
    private var bound = false

    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingUrl: String? = null
    private var didInitialReload = false

    private val status = mutableStateOf(NodeStatus(NodeState.STOPPED, "Idle", NodeController.DEFAULT_PORT))
    private lateinit var activeScreen: MutableState<ActiveScreen>

    private val fileChooser = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val cb = pendingFileCallback
        pendingFileCallback = null
        cb?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
    }

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            background {
                BackupManager(this).export(uri) {}
                    .onFailure { toast("Export failed: ${it.message}") }
                    .onSuccess { toast("Backup exported") }
            }
        }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            background {
                service?.stop()
                sleep(1500)
                BackupManager(this).import(uri) {}
                    .onFailure { toast("Import failed: ${it.message}") }
                    .onSuccess {
                        toast("Backup imported")
                        NodeService.start(this@MainActivity)
                    }
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as? NodeService.LocalBinder)?.getService() ?: return
            service = s
            bound = true
            s.registerListener(this@MainActivity)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appConfig = AppConfig(this)
        val payloadManager = PayloadManager(this)
        val needsExtraction = payloadManager.isExtractionNeeded()

        activeScreen = mutableStateOf(if (needsExtraction) ActiveScreen.SETUP else ActiveScreen.DASHBOARD)

        initWebView()
        requestNotificationPermission()

        bindService(Intent(this, NodeService::class.java), connection, Context.BIND_AUTO_CREATE)

        if (!needsExtraction && appConfig.autoStartOnAppOpen) {
            NodeService.start(this)
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val currentScreen = activeScreen.value

                BackHandler(enabled = currentScreen == ActiveScreen.SETTINGS) {
                    activeScreen.value = ActiveScreen.DASHBOARD
                }

                Scaffold(
                    bottomBar = {
                        if (currentScreen == ActiveScreen.DASHBOARD || currentScreen == ActiveScreen.SETTINGS) {
                            BottomNavDock(
                                currentScreen = currentScreen,
                                onSelectScreen = { screen -> activeScreen.value = screen },
                            )
                        }
                    },
                ) { padding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                    ) {
                        when (currentScreen) {
                            ActiveScreen.SETUP -> {
                                SetupScreen(
                                    onSetupComplete = {
                                        activeScreen.value = ActiveScreen.DASHBOARD
                                        if (appConfig.autoStartOnAppOpen) {
                                            NodeService.start(this@MainActivity)
                                        }
                                    },
                                )
                            }
                            ActiveScreen.DASHBOARD -> {
                                DashboardScreen(
                                    status = status.value,
                                    onStartServer = { NodeService.start(this@MainActivity) },
                                    onStopServer = { service?.stop() },
                                    onOpenSillyTavern = { activeScreen.value = ActiveScreen.WEBVIEW },
                                    onExportBackup = { exportLauncher.launch("st-backup.zip") },
                                    onImportBackup = { importLauncher.launch(arrayOf("*/*")) },
                                    onExportLogs = { shareLogs() },
                                )
                            }
                            ActiveScreen.SETTINGS -> {
                                SettingsScreen(
                                    onPromptBattery = { promptBattery() },
                                    onResetPayload = { resetPayload() },
                                )
                            }
                            ActiveScreen.WEBVIEW -> {
                                StWebView(
                                    webView = webView,
                                    onNavigateToDashboard = { activeScreen.value = ActiveScreen.DASHBOARD },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun initWebView() {
        webView = WebView(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (!didInitialReload) {
                        didInitialReload = true
                        view?.postDelayed({ view.loadUrl(url ?: "about:blank") }, 250)
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
                        pendingFileCallback = null
                        false
                    }
                }
            }
            addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
                val url = pendingUrl ?: return@addOnLayoutChangeListener
                if (r - l > 0 && b - t > 0) {
                    pendingUrl = null
                    loadUrl(url)
                }
            }
        }
    }

    override fun onStatus(newStatus: NodeStatus) {
        runOnUiThread {
            status.value = newStatus
            if (newStatus.state == NodeState.RUNNING) {
                val url = "http://127.0.0.1:${newStatus.port}"
                if (webView.width > 0 && webView.height > 0) {
                    webView.loadUrl(url)
                } else {
                    pendingUrl = url
                }
                val appConfig = AppConfig(this)
                if (appConfig.autoLaunchWebViewOnStart && activeScreen.value == ActiveScreen.DASHBOARD) {
                    activeScreen.value = ActiveScreen.WEBVIEW
                }
            }
        }
    }

    private fun resetPayload() {
        background {
            service?.stop()
            sleep(1500)
            val paths = AppPaths(this)
            paths.stDir.deleteRecursively()
            getSharedPreferences("payload", Context.MODE_PRIVATE).edit().remove("installed_payload_version").apply()
            runOnUiThread {
                activeScreen.value = ActiveScreen.SETUP
                toast("SillyTavern payload reset")
            }
        }
    }

    private fun shareLogs() {
        background {
            val logs = AppPaths(this).logsDir.listFiles()?.filter { it.isFile } ?: return@background
            val latest = logs.maxByOrNull { it.lastModified() }
            if (latest == null) {
                toast("No logs yet")
                return@background
            }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", latest)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("logs", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Share logs"))
        }
    }

    private fun promptBattery() {
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (pm?.isIgnoringBatteryOptimizations(packageName) == true) return
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            // Non-fatal.
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun background(block: () -> Unit) = Thread(block).start()

    private fun toast(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    override fun onDestroy() {
        if (bound) {
            service?.unregisterListener(this)
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }
}

@Composable
private fun BottomNavDock(
    currentScreen: ActiveScreen,
    onSelectScreen: (ActiveScreen) -> Unit,
) {
    NavigationBar(
        containerColor = Color(0xFF1A2228),
        contentColor = Color(0xFF7EC8A9),
    ) {
        NavigationBarItem(
            selected = currentScreen == ActiveScreen.DASHBOARD,
            onClick = { onSelectScreen(ActiveScreen.DASHBOARD) },
            icon = { Icon(Icons.Default.Dashboard, contentDescription = "Dashboard") },
            label = { Text("Dashboard") },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color(0xFF7EC8A9),
                selectedTextColor = Color(0xFF7EC8A9),
                unselectedIconColor = Color(0xFF8A94A3),
                unselectedTextColor = Color(0xFF8A94A3),
                indicatorColor = Color(0xFF23303A),
            ),
        )
        NavigationBarItem(
            selected = currentScreen == ActiveScreen.SETTINGS,
            onClick = { onSelectScreen(ActiveScreen.SETTINGS) },
            icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
            label = { Text("Settings") },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color(0xFF7EC8A9),
                selectedTextColor = Color(0xFF7EC8A9),
                unselectedIconColor = Color(0xFF8A94A3),
                unselectedTextColor = Color(0xFF8A94A3),
                indicatorColor = Color(0xFF23303A),
            ),
        )
    }
}