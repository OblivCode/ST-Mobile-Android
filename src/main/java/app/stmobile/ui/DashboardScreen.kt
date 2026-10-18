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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.stmobile.R
import app.stmobile.models.NodeState
import app.stmobile.models.NodeStatus
import app.stmobile.payload.PayloadManager

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
    onCopyDiagnostics: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
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
                    InstallProgressCard(
                        progress = installProgress,
                        onCancel = onCancelInstall,
                    )
                }

                // 2. Not installed state
                !isInstalled -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
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
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            Text(
                                text = stringResource(R.string.dashboard_msg_not_installed),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )

                            if (installErrorMessage != null) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = installErrorMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
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
                        NodeState.RUNNING -> MaterialTheme.colorScheme.primary
                        NodeState.STARTING -> MaterialTheme.colorScheme.tertiary
                        NodeState.STOPPING -> MaterialTheme.colorScheme.secondary
                        NodeState.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant
                        NodeState.ERROR -> MaterialTheme.colorScheme.error
                    }

                    val stateLabel = when {
                        status.isGenerating -> stringResource(R.string.status_generating)
                        status.state == NodeState.RUNNING -> stringResource(R.string.status_running)
                        status.state == NodeState.STARTING -> stringResource(R.string.status_starting)
                        status.state == NodeState.STOPPING -> stringResource(R.string.status_stopping)
                        status.state == NodeState.STOPPED -> stringResource(R.string.status_stopped)
                        status.state == NodeState.ERROR -> stringResource(R.string.status_error)
                        else -> ""
                    }

                    // Server Status Card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
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
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }

                            Spacer(Modifier.height(8.dp))

                            Text(
                                text = status.message.ifBlank {
                                    if (status.state == NodeState.STOPPED) stringResource(R.string.dashboard_msg_stopped) else ""
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )

                            if (status.pid != null) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = stringResource(R.string.dashboard_pid, status.pid),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    ),
                                ) {
                                    Text(stringResource(R.string.dashboard_btn_open))
                                }

                                Button(
                                    onClick = onStopServer,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
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

                    OutlinedButton(
                        onClick = onCopyDiagnostics,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(stringResource(R.string.btn_copy_diagnostics))
                    }
                }
            }
        }
    }
}
