package app.stmobile.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.stmobile.sillytavern.BackupManager
import app.stmobile.sillytavern.PayloadManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Onboarding setup screen presenting the choice between a fresh install or migrating
 * from an existing backup archive (PLAN.md §3.1, Phase F).
 */
@Composable
fun SetupScreen(
    onSetupComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var setupStep by remember { mutableStateOf(SetupStep.WELCOME) }
    var statusText by remember { mutableStateOf("Preparing runtime…") }
    var progress by remember { mutableFloatStateOf(0f) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showDetails by remember { mutableStateOf(false) }
    val logBuffer = remember { mutableStateListOf<String>() }

    // Dialog state for encrypted restore during onboarding
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var passwordError by remember { mutableStateOf<String?>(null) }

    fun runExtraction() {
        errorMessage = null
        setupStep = SetupStep.EXTRACTING
        progress = 0f
        logBuffer.clear()
        logBuffer.add("Starting SillyTavern setup…")

        scope.launch(Dispatchers.IO) {
            try {
                val manager = PayloadManager(context)
                manager.extract { p ->
                    statusText = p.stage
                    if (p.totalEstimate > 0 && p.filesProcessed > 0) {
                        progress = (p.filesProcessed.toFloat() / p.totalEstimate.toFloat()).coerceIn(0f, 1f)
                    }
                    p.currentFile?.let { file ->
                        if (logBuffer.size > 50) logBuffer.removeAt(0)
                        logBuffer.add(file)
                    }
                }
                withContext(Dispatchers.Main) {
                    onSetupComplete()
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    errorMessage = t.message ?: "Extraction failed"
                    setupStep = SetupStep.ERROR
                }
            }
        }
    }

    fun startRestore(uri: Uri, password: String? = null) {
        errorMessage = null
        passwordError = null
        setupStep = SetupStep.RESTORING
        progress = 0f
        statusText = "Restoring backup data…"
        logBuffer.clear()
        logBuffer.add("Importing user backup…")

        scope.launch(Dispatchers.IO) {
            val backupManager = BackupManager(context)
            backupManager.import(
                uri = uri,
                password = password,
                strategy = BackupManager.Strategy.CLEAN,
                onProgress = { msg -> statusText = msg },
            ).onSuccess { count ->
                withContext(Dispatchers.Main) {
                    showPasswordDialog = false
                    pendingRestoreUri = null
                    logBuffer.add("Restored $count files. Unpacking runtime…")
                    // After user data is restored, extract the core runtime
                    runExtraction()
                }
            }.onFailure { err ->
                withContext(Dispatchers.Main) {
                    if (err.message?.contains("password", ignoreCase = true) == true) {
                        passwordError = err.message
                        showPasswordDialog = true
                        setupStep = SetupStep.WELCOME
                    } else {
                        errorMessage = "Restore failed: ${err.message}"
                        setupStep = SetupStep.ERROR
                    }
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pendingRestoreUri = uri
            scope.launch(Dispatchers.IO) {
                val backupManager = BackupManager(context)
                val inspection = backupManager.inspect(uri)
                withContext(Dispatchers.Main) {
                    if (!inspection.isValid) {
                        errorMessage = inspection.errorMessage ?: "Selected file is not a valid ZIP archive"
                        setupStep = SetupStep.ERROR
                    } else if (inspection.isEncrypted && !inspection.isUnlocked) {
                        showPasswordDialog = true
                    } else {
                        startRestore(uri)
                    }
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF101418))
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (setupStep) {
            SetupStep.WELCOME -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = "Welcome to ST Mobile",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFD8DEE6),
                    )

                    Text(
                        text = "Run SillyTavern locally on Android with no external dependencies. Choose how to set up your environment:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF8A94A3),
                    )

                    Spacer(Modifier.height(8.dp))

                    // Card 1: Fresh Installation
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2228)),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = Color(0xFF7EC8A9),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Fresh Installation",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFD8DEE6),
                                )
                            }
                            Text(
                                text = "Unpacks the bundled SillyTavern runtime and seeds default configurations.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF8A94A3),
                            )
                            Button(
                                onClick = { runExtraction() },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Text("Start Setup")
                            }
                        }
                    }

                    // Card 2: Restore from Backup
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2228)),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = null,
                                    tint = Color(0xFFE5C07B),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Restore from Backup",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFD8DEE6),
                                )
                            }
                            Text(
                                text = "Migrate your existing characters, chats, and config from a backup ZIP archive.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF8A94A3),
                            )
                            OutlinedButton(
                                onClick = { importLauncher.launch(arrayOf("*/*")) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Text("Restore from Backup")
                            }
                        }
                    }
                }
            }

            SetupStep.EXTRACTING, SetupStep.RESTORING -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = statusText,
                        color = Color(0xFFD8DEE6),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(16.dp))
                    if (progress > 0f) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp),
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp),
                        )
                    }

                    Spacer(Modifier.height(24.dp))

                    OutlinedButton(onClick = { showDetails = !showDetails }) {
                        Text(if (showDetails) "Hide Details" else "Details")
                    }

                    AnimatedVisibility(
                        visible = showDetails,
                        enter = expandVertically(),
                        exit = shrinkVertically(),
                    ) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .padding(top = 16.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2228)),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(12.dp)
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                logBuffer.forEach { line ->
                                    Text(
                                        text = line,
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        color = Color(0xFF8A94A3),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            SetupStep.ERROR -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = errorMessage ?: "Setup encountered an error",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { runExtraction() }) {
                            Text("Retry Setup")
                        }
                        OutlinedButton(onClick = {
                            errorMessage = null
                            setupStep = SetupStep.WELCOME
                        }) {
                            Text("Back to Options")
                        }
                    }
                }
            }
        }
    }

    if (showPasswordDialog && pendingRestoreUri != null) {
        ImportPasswordDialog(
            errorMessage = passwordError,
            onDismiss = {
                showPasswordDialog = false
                pendingRestoreUri = null
            },
            onConfirm = { enteredPassword ->
                pendingRestoreUri?.let { uri ->
                    startRestore(uri, enteredPassword)
                }
            },
        )
    }
}

private enum class SetupStep {
    WELCOME,
    RESTORING,
    EXTRACTING,
    ERROR,
}
