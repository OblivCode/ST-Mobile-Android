package app.stmobile.ui

import android.os.Build
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.stmobile.R
import app.stmobile.payload.PayloadManager
import app.stmobile.payload.UpdateCheckResult
import app.stmobile.payload.UpdateInfo

@Composable
fun UpdatesScreen(
    installedInfo: PayloadManager.InstalledInfo,
    isInstalling: Boolean,
    installProgress: PayloadManager.ExtractProgress?,
    installErrorMessage: String?,
    isCheckingUpdates: Boolean,
    updateCheckResult: UpdateCheckResult?,
    onCheckForUpdates: () -> Unit,
    onUpdatePayload: (UpdateInfo) -> Unit,
    onImportArchive: () -> Unit,
    onReinstallPayload: () -> Unit,
    onCancelInstall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showRepairDialog by rememberSaveable { mutableStateOf(false) }

    val manifest = remember {
        runCatching { PayloadManager(context).readManifest() }.getOrNull()
    }

    val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
    val channelText = stringResource(R.string.updates_channel_release)

    // Formatted SillyTavern version string
    val stVersionDisplay = when {
        !installedInfo.isInstalled -> stringResource(R.string.status_not_installed)
        !installedInfo.stCommit.isNullOrBlank() -> {
            val shortCommit = installedInfo.stCommit.take(8)
            "v${installedInfo.stVersion} (${stringResource(R.string.updates_commit, shortCommit)})"
        }
        installedInfo.sourceDescription?.contains("Archive", ignoreCase = true) == true ||
            installedInfo.sourceDescription?.contains("SAF", ignoreCase = true) == true -> {
            "v${installedInfo.stVersion} (${stringResource(R.string.updates_custom_archive)})"
        }
        else -> "v${installedInfo.stVersion ?: manifest?.stVersion ?: "1.19.0"}"
    }

    val nodeEngineDisplay = installedInfo.nodeVersion ?: manifest?.nodeVersion ?: "24.20.0 (libnode.so)"

    // Dynamic status text and tint
    val (statusText, statusColor) = when {
        isInstalling -> Pair(stringResource(R.string.dashboard_status_installing), MaterialTheme.colorScheme.tertiary)
        isCheckingUpdates -> Pair(stringResource(R.string.updates_status_checking), MaterialTheme.colorScheme.secondary)
        updateCheckResult is UpdateCheckResult.UpdateAvailable -> Pair(
            stringResource(R.string.updates_status_update_available, "v${updateCheckResult.update.stVersion}"),
            MaterialTheme.colorScheme.primary,
        )
        updateCheckResult is UpdateCheckResult.Error -> Pair(
            stringResource(R.string.updates_status_check_failed, updateCheckResult.message),
            MaterialTheme.colorScheme.error,
        )
        updateCheckResult is UpdateCheckResult.UpToDate -> Pair(
            stringResource(R.string.updates_status_up_to_date),
            MaterialTheme.colorScheme.primary,
        )
        installedInfo.isInstalled -> Pair(stringResource(R.string.updates_status_up_to_date), MaterialTheme.colorScheme.primary)
        else -> Pair(stringResource(R.string.status_not_installed), MaterialTheme.colorScheme.onSurfaceVariant)
    }

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
            Text(
                text = stringResource(R.string.updates_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            // Current Installation Card
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.updates_current_installation),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }

                    Spacer(Modifier.height(4.dp))

                    Text(
                        text = stringResource(R.string.updates_sillytavern_version, stVersionDisplay),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    val flavorName = app.stmobile.BuildConfig.FLAVOR.replaceFirstChar { it.uppercase() }
                    Text(
                        text = stringResource(R.string.updates_build_flavor, "$flavorName ($primaryAbi)"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Text(
                        text = stringResource(R.string.updates_node_engine, nodeEngineDisplay),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Text(
                        text = stringResource(R.string.updates_channel, channelText),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Text(
                        text = stringResource(R.string.updates_status, statusText),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = statusColor,
                    )
                }
            }

            // In-Place Installation / Update Progress Card
            if (isInstalling) {
                InstallProgressCard(
                    progress = installProgress,
                    onCancel = onCancelInstall,
                )
            }

            // Update Available Card
            val updateAvailable = (updateCheckResult as? UpdateCheckResult.UpdateAvailable)?.update
            if (updateAvailable != null && !isInstalling) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.SystemUpdate,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.updates_available_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }

                        Text(
                            text = stringResource(R.string.updates_available_version, "v${updateAvailable.stVersion}"),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )

                        if (updateAvailable.bundleSize > 0) {
                            val sizeMb = updateAvailable.bundleSize.toDouble() / (1024.0 * 1024.0)
                            Text(
                                text = stringResource(R.string.updates_available_size, "%.1f MB".format(sizeMb)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        if (updateAvailable.publishedAt.isNotBlank()) {
                            Text(
                                text = stringResource(R.string.updates_available_published, updateAvailable.publishedAt.take(10)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Spacer(Modifier.height(4.dp))

                        Button(
                            onClick = { onUpdatePayload(updateAvailable) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.SystemUpdate, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.updates_btn_update))
                            }
                        }
                    }
                }
            }

            // Actions Group (disabled during extraction)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Check for updates (or Check Again if update is already displayed)
                Button(
                    onClick = onCheckForUpdates,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    enabled = !isInstalling && !isCheckingUpdates,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isCheckingUpdates) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.updates_status_checking))
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            val btnLabel = if (updateAvailable != null) {
                                stringResource(R.string.updates_btn_check_again)
                            } else {
                                stringResource(R.string.updates_btn_check)
                            }
                            Text(btnLabel)
                        }
                    }
                }

                // Import custom archive (.zip)
                OutlinedButton(
                    onClick = onImportArchive,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    enabled = !isInstalling,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Archive, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.updates_btn_import_archive))
                    }
                }

                // Reinstall / Repair Payload
                Button(
                    onClick = { showRepairDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    enabled = !isInstalling,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.updates_btn_repair))
                    }
                }
            }
        }
    }

    if (showRepairDialog) {
        AlertDialog(
            onDismissRequest = { showRepairDialog = false },
            title = { Text(stringResource(R.string.dialog_repair_payload_title)) },
            text = { Text(stringResource(R.string.dialog_repair_payload_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    showRepairDialog = false
                    onReinstallPayload()
                }) {
                    Text(stringResource(R.string.btn_repair))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRepairDialog = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            },
        )
    }
}
