package app.stmobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.stmobile.sillytavern.PayloadManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SetupScreen(
    onSetupComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var statusText by remember { mutableStateOf("Preparing runtime…") }
    var progress by remember { mutableFloatStateOf(0f) }
    var isExtracting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showDetails by remember { mutableStateOf(false) }
    val logBuffer = remember { mutableStateListOf<String>() }

    fun runExtraction() {
        errorMessage = null
        isExtracting = true
        progress = 0f
        logBuffer.clear()
        logBuffer.add("Starting SillyTavern setup…")
    }

    LaunchedEffect(isExtracting) {
        if (!isExtracting) return@LaunchedEffect
        withContext(Dispatchers.IO) {
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
                    isExtracting = false
                    onSetupComplete()
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    isExtracting = false
                    errorMessage = t.message ?: "Extraction failed"
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        runExtraction()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF101418))
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (errorMessage != null) {
                Text(
                    text = errorMessage ?: "Setup encountered an error",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { runExtraction() }) {
                    Text("Retry")
                }
            } else {
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
            }

            Spacer(Modifier.height(24.dp))

            OutlinedButton(
                onClick = { showDetails = !showDetails },
            ) {
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
}
