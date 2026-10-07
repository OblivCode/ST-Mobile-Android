package app.stmobile.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.stmobile.R
import app.stmobile.models.NodeState
import app.stmobile.models.NodeStatus
import app.stmobile.sillytavern.PayloadManager

@Composable
fun DashboardScreen(
    status: NodeStatus,
    isInstalled: Boolean,
    isInstalling: Boolean,
    installProgress: PayloadManager.ExtractProgress?,
    installErrorMessage: String?,
    onInstallPayload: () -> Unit,
    onCancelInstall: () -> Unit,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onOpenSillyTavern: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF101418))
            .padding(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when {
                // 1. In-place installation progress
                isInstalling -> {
                    val hasBytes = (installProgress?.totalBytes ?: 0L) > 0L && (installProgress?.bytesProcessed ?: 0L) > 0L
                    val pct = if (hasBytes) {
                        (installProgress!!.bytesProcessed.toFloat() / installProgress.totalBytes.toFloat()).coerceIn(0f, 1f)
                    } else if ((installProgress?.totalEstimate ?: 0) > 0 && (installProgress?.filesProcessed ?: 0) > 0) {
                        (installProgress!!.filesProcessed.toFloat() / installProgress.totalEstimate.toFloat()).coerceIn(0f, 1f)
                    } else 0f

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
                            Text(
                                text = stringResource(R.string.dashboard_status_installing),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE5C07B),
                            )

                            Text(
                                text = installProgress?.stage ?: stringResource(R.string.dashboard_status_installing),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFD8DEE6),
                            )

                            Spacer(Modifier.height(4.dp))

                            if (pct > 0f) {
                                LinearProgressIndicator(
                                    progress = { pct },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp),
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    if (hasBytes) {
                                        val currentMb = installProgress!!.bytesProcessed.toDouble() / (1024.0 * 1024.0)
                                        val totalMb = installProgress.totalBytes.toDouble() / (1024.0 * 1024.0)
                                        val speedText = if (installProgress.speedBytesPerSec > 0) {
                                            val speedMb = installProgress.speedBytesPerSec.toDouble() / (1024.0 * 1024.0)
                                            " (%.1f MB/s)".format(speedMb)
                                        } else ""
                                        Text(
                                            text = "%.1f MB / %.1f MB%s".format(currentMb, totalMb, speedText),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color(0xFF8A94A3),
                                        )
                                    } else {
                                        Text(
                                            text = "${installProgress!!.filesProcessed} / ${installProgress.totalEstimate} files",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color(0xFF8A94A3),
                                        )
                                    }
                                    Text(
                                        text = "${(pct * 100).toInt()}%",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF8A94A3),
                                    )
                                }
                            } else {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp),
                                )
                            }

                            installProgress?.currentFile?.let { currentFile ->
                                Text(
                                    text = currentFile,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF6B7685),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    // Single Action: Cancel installation
                    Button(
                        onClick = onCancelInstall,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF4A2525),
                            contentColor = Color(0xFFFFB4AB),
                        ),
                    ) {
                        Text(stringResource(R.string.btn_cancel))
                    }
                }

                // 2. Not installed state
                !isInstalled -> {
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
                            Text(
                                text = stringResource(R.string.status_not_installed),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF8A94A3),
                            )

                            Text(
                                text = stringResource(R.string.dashboard_msg_not_installed),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFD8DEE6),
                            )

                            if (installErrorMessage != null) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = installErrorMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFE06C75),
                                )
                            }
                        }
                    }

                    // Single Action: Install SillyTavern
                    Button(
                        onClick = onInstallPayload,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(stringResource(R.string.dashboard_btn_install))
                    }
                }

                // 3. Installed state with server lifecycle
                else -> {
                    val stateColor = when (status.state) {
                        NodeState.RUNNING -> Color(0xFF7EC8A9)
                        NodeState.STARTING -> Color(0xFFE5C07B)
                        NodeState.STOPPING -> Color(0xFFD19A66)
                        NodeState.STOPPED -> Color(0xFF8A94A3)
                        NodeState.ERROR -> Color(0xFFE06C75)
                    }

                    val stateLabel = when (status.state) {
                        NodeState.RUNNING -> stringResource(R.string.status_running)
                        NodeState.STARTING -> stringResource(R.string.status_starting)
                        NodeState.STOPPING -> stringResource(R.string.status_stopping)
                        NodeState.STOPPED -> stringResource(R.string.status_stopped)
                        NodeState.ERROR -> stringResource(R.string.status_error)
                    }

                    // Server Status Card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2228)),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stateLabel,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = stateColor,
                                )
                                if (status.port > 0) {
                                    Text(
                                        text = stringResource(R.string.dashboard_port, status.port),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color(0xFF8A94A3),
                                    )
                                }
                            }

                            Spacer(Modifier.height(8.dp))

                            Text(
                                text = status.message.ifBlank {
                                    if (status.state == NodeState.STOPPED) stringResource(R.string.dashboard_msg_stopped) else ""
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFD8DEE6),
                            )

                            if (status.pid != null) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = stringResource(R.string.dashboard_pid, status.pid),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF8A94A3),
                                )
                            }
                        }
                    }

                    // Dynamic Single / Contextual Actions (No disabled dummy buttons)
                    when (status.state) {
                        NodeState.STOPPED -> {
                            Button(
                                onClick = onStartServer,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Text(stringResource(R.string.dashboard_btn_start))
                            }
                        }

                        NodeState.STARTING -> {
                            Button(
                                onClick = {},
                                enabled = false,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Text(stringResource(R.string.status_starting))
                            }
                        }

                        NodeState.STOPPING -> {
                            Button(
                                onClick = {},
                                enabled = false,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Text(stringResource(R.string.status_stopping))
                            }
                        }

                        NodeState.RUNNING -> {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Button(
                                    onClick = onOpenSillyTavern,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF2E5A44),
                                        contentColor = Color(0xFFB5EAD0),
                                    ),
                                ) {
                                    Text(stringResource(R.string.dashboard_btn_open))
                                }

                                Button(
                                    onClick = onStopServer,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF4A2525),
                                        contentColor = Color(0xFFFFB4AB),
                                    ),
                                ) {
                                    Text(stringResource(R.string.dashboard_btn_stop))
                                }
                            }
                        }

                        NodeState.ERROR -> {
                            Button(
                                onClick = onStartServer,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Text(stringResource(R.string.dashboard_btn_start))
                            }
                        }
                    }
                }
            }
        }
    }
}
