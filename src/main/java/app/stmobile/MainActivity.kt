package app.stmobile

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

private enum class Screen { HOME, SETTINGS, LOGS, ABOUT }

private data class AppActions(
    val restartServer: () -> Unit,
    val stopServer: () -> Unit,
    val startServer: () -> Unit,
    val exportBackup: () -> Unit,
    val importBackup: () -> Unit,
    val resetPayload: () -> Unit,
    val shareLogs: () -> Unit,
    val promptBattery: () -> Unit,
    val saveSettings: (Int, Boolean, String, String) -> Unit,
)

class MainActivity : ComponentActivity(), NodeStatusListener {

    private lateinit var webView: WebView
    private var service: NodeService? = null
    private var bound = false

    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingUrl: String? = null
    private var didInitialReload = false

    private val serverReady = mutableStateOf(false)
    private val status = mutableStateOf(NodeStatus(NodeState.STOPPED, "Starting…", NodeController.DEFAULT_PORT))

    private val fileChooser = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val cb = pendingFileCallback
        pendingFileCallback = null
        cb?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
    }
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) background { BackupManager(this).export(uri) {}.onFailure { toast("Export failed: ${it.message}") }.onSuccess { toast("Backup exported") } }
    }
    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) background {
            service?.stop()
            sleep(1500)
            BackupManager(this).import(uri) {}
                .onFailure { toast("Import failed: ${it.message}") }
                .onSuccess { toast("Backup imported"); restartServer() }
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
                    // Android WebView can cache viewport units (vh/dvh) as 0 if
                    // the page is first loaded into a not-yet-laid-out view.
                    // One reload after the WebView has a real size fixes it,
                    // with no modification to SillyTavern's page.
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
            // Only load once the WebView has a real size; loading before Compose
            // attaches it makes ST lay out against a zero-size viewport.
            addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
                val url = pendingUrl ?: return@addOnLayoutChangeListener
                if (r - l > 0 && b - t > 0) {
                    pendingUrl = null
                    loadUrl(url)
                }
            }
        }

        requestNotificationPermission()
        bindService(Intent(this, NodeService::class.java), connection, Context.BIND_AUTO_CREATE)
        NodeService.start(this, AppSettings(this).port)
        background { waitForServer() }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var screen by remember { mutableStateOf(Screen.HOME) }
                var batteryDialog by remember { mutableStateOf(false) }
                val appSettings = remember { AppSettings(this@MainActivity) }
                val actions = remember {
                    AppActions(
                        restartServer = { restartServer() },
                        stopServer = { service?.stop() },
                        startServer = { NodeService.start(this@MainActivity, AppSettings(this@MainActivity).port) },
                        exportBackup = { exportLauncher.launch("st-backup.zip") },
                        importBackup = { importLauncher.launch(arrayOf("*/*")) },
                        resetPayload = { resetPayload() },
                        shareLogs = { shareLogs() },
                        promptBattery = { promptBattery() },
                        saveSettings = { port, auth, user, pass ->
                            AppSettings(this@MainActivity).apply {
                                this.port = port
                                basicAuthEnabled = auth
                                basicAuthUsername = user
                                basicAuthPassword = pass
                            }
                            toast("Saved — restarting server")
                            restartServer()
                        },
                    )
                }

                LaunchedEffect(serverReady.value) {
                    if (serverReady.value && !appSettings.batteryPrompted) batteryDialog = true
                }

                BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }
                BackHandler(enabled = screen == Screen.HOME && webView.canGoBack()) { webView.goBack() }

                when (screen) {
                    Screen.HOME -> HomeScreen(webView, status.value, serverReady.value) { screen = Screen.SETTINGS }
                    Screen.SETTINGS -> SettingsScreen(status.value, actions) { screen = Screen.HOME }
                    Screen.LOGS -> LogsScreen(actions) { screen = Screen.HOME }
                    Screen.ABOUT -> AboutScreen { screen = Screen.HOME }
                }

                if (batteryDialog) {
                    AlertDialog(
                        onDismissRequest = { batteryDialog = false; appSettings.batteryPrompted = true },
                        title = { Text("Keep the server running") },
                        text = { Text("Allow ST Mobile to ignore battery optimizations so SillyTavern stays responsive in the background.") },
                        confirmButton = {
                            TextButton(onClick = {
                                batteryDialog = false
                                appSettings.batteryPrompted = true
                                actions.promptBattery()
                            }) { Text("Allow") }
                        },
                        dismissButton = {
                            TextButton(onClick = { batteryDialog = false; appSettings.batteryPrompted = true }) {
                                Text("Not now")
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onStatus(newStatus: NodeStatus) {
        status.value = newStatus
    }

    private fun restartServer() {
        startService(Intent(this, NodeService::class.java).setAction(NodeService.ACTION_RESTART))
    }

    private fun waitForServer() {
        repeat(240) {
            if (serverReady.value) return
            val port = AppSettings(this).port
            try {
                val conn = URL("http://127.0.0.1:$port").openConnection() as HttpURLConnection
                conn.connectTimeout = 500
                conn.readTimeout = 500
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..399) {
                    serverReady.value = true
                    val url = "http://127.0.0.1:$port"
                    runOnUiThread {
                        if (webView.width > 0 && webView.height > 0) {
                            webView.loadUrl(url)
                        } else {
                            pendingUrl = url
                        }
                    }
                    return
                }
            } catch (_: Exception) {
                // not up yet
            }
            sleep(500)
        }
    }

    private fun resetPayload() {
        background {
            service?.stop()
            sleep(1500)
            val paths = AppPaths(this)
            paths.stDir.deleteRecursively()
            getSharedPreferences("payload", Context.MODE_PRIVATE).edit().remove("installed_payload_version").apply()
            toast("SillyTavern payload reset")
            serverReady.value = false
            restartServer()
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

// ---------------------------------------------------------------------------
// Compose UI
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Chrome(title: String, onBack: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (onBack != null) TextButton(onClick = onBack) { Text("Back") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) { content() }
    }
}

@Composable
private fun HomeScreen(webView: WebView, status: NodeStatus, ready: Boolean, onMenu: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        // Attach the WebView immediately (under the splash) so it is laid out at
        // full size before we load the URL — otherwise ST caches viewport units
        // (100dvh) as 0 and the layout collapses.
        AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())

        if (!ready) {
            Column(
                Modifier.fillMaxSize().background(Color(0xFF101418)).padding(32.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text("ST Mobile", style = MaterialTheme.typography.headlineSmall, color = Color(0xFF7EC8A9))
                Spacer(Modifier.height(12.dp))
                Text("${status.state}: ${status.message}", color = Color(0xFFD8DEE6))
            }
        }

        // Always available so Settings/Logs/About are reachable over the ST UI.
        FloatingActionButton(
            onClick = onMenu,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            containerColor = Color(0xCC23303A),
            contentColor = Color(0xFF7EC8A9),
        ) {
            Text("Menu")
        }
    }
}

@Composable
private fun SettingsScreen(status: NodeStatus, actions: AppActions, onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    var port by remember { mutableStateOf(settings.port.toString()) }
    var authEnabled by remember { mutableStateOf(settings.basicAuthEnabled) }
    var authUser by remember { mutableStateOf(settings.basicAuthUsername) }
    var authPass by remember { mutableStateOf(settings.basicAuthPassword) }
    val manifest = remember { runCatching { PayloadManager(context).readManifest() }.getOrNull() }

    Chrome("Settings", onBack) {
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
            Section("Server")
            Text("Status: ${status.state} — ${status.message}", color = Color(0xFF8A94A3))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit).take(5) },
                label = { Text("Port") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { actions.saveSettings(port.toIntOrNull() ?: 8000, authEnabled, authUser, authPass) }) {
                    Text("Save & Restart")
                }
                TextButton(onClick = actions.stopServer) { Text("Stop") }
                TextButton(onClick = actions.startServer) { Text("Start") }
            }

            Section("Access")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = authEnabled, onCheckedChange = { authEnabled = it })
                Spacer(Modifier.height(0.dp))
                Text("  Require basic auth (loopback is device-wide)")
            }
            if (authEnabled) {
                OutlinedTextField(authUser, { authUser = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(authPass, { authPass = it }, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            }

            Section("Data")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = actions.exportBackup) { Text("Export backup") }
                TextButton(onClick = actions.importBackup) { Text("Import backup") }
            }
            TextButton(onClick = actions.resetPayload) { Text("Reset SillyTavern payload (keeps user data)") }

            Section("Diagnostics")
            Text("Node: ${manifest?.nodeVersion ?: "?"}   ST: ${manifest?.stVersion ?: "?"}", color = Color(0xFF8A94A3))
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = actions.shareLogs) { Text("Share logs") }

            Section("System")
            TextButton(onClick = actions.promptBattery) { Text("Battery optimization…") }

            Section("About")
            Text("Not affiliated with SillyTavern. SillyTavern is AGPL-3.0; Node.js is MIT.", color = Color(0xFF8A94A3))
        }
    }
}

@Composable
private fun LogsScreen(actions: AppActions, onBack: () -> Unit) {
    val context = LocalContext.current
    val text = remember {
        val dir = AppPaths(context).logsDir
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
        if (files.isEmpty()) "No logs yet."
        else files.joinToString("\n\n") { f ->
            "===== ${f.name} =====\n" + runCatching { f.readText().takeLast(20_000) }.getOrDefault("(unreadable)")
        }
    }
    Chrome("Logs", onBack) {
        Column(Modifier.padding(16.dp)) {
            TextButton(onClick = actions.shareLogs) { Text("Share logs") }
            Spacer(Modifier.height(8.dp))
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(text, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun AboutScreen(onBack: () -> Unit) {
    Chrome("About", onBack) {
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
            Text("ST Mobile", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text("A standalone Android shell that runs SillyTavern locally on your device.")
            Spacer(Modifier.height(12.dp))
            Text("Not affiliated with or endorsed by SillyTavern.")
            Spacer(Modifier.height(12.dp))
            Text("SillyTavern is licensed AGPL-3.0. Node.js is MIT. See the project LICENSE and THIRD-PARTY notices.", color = Color(0xFF8A94A3))
            Spacer(Modifier.height(12.dp))
            Text("Source: replace with your repository URL before release.", color = Color(0xFF8A94A3))
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(20.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, color = Color(0xFF7EC8A9))
    Spacer(Modifier.height(4.dp))
    HorizontalDivider()
    Spacer(Modifier.height(8.dp))
}