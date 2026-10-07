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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.stmobile.R
import app.stmobile.Utils
import app.stmobile.sillytavern.PayloadManager

@Composable
fun UpdatesScreen(
    isInstalled: Boolean,
    onResetPayload: () -> Unit,
    onImportArchive: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showResetDialog by remember { mutableStateOf(false) }

    val manifest = remember {
        runCatching { PayloadManager(context).readManifest() }.getOrNull()
    }

    val stVersion = manifest?.stVersion ?: manifest?.payloadVersion ?: "1.12.0"
    val nodeVersion = manifest?.nodeVersion ?: "24.20.0 (libnode.so)"
    val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
    val channelText = stringResource(R.string.updates_channel_release)
    val statusText = if (isInstalled) {
        stringResource(R.string.updates_status_up_to_date)
    } else {
        stringResource(R.string.status_not_installed)
    }

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
            Text(
                text = stringResource(R.string.updates_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFD8DEE6),
            )

            // Current Installation Card
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
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = Color(0xFF7EC8A9),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.updates_current_installation),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFD8DEE6),
                        )
                    }

                    Spacer(Modifier.height(4.dp))

                    Text(
                        text = stringResource(R.string.updates_sillytavern_version, stVersion),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFD8DEE6),
                    )

                    Text(
                        text = stringResource(R.string.updates_build_flavor, primaryAbi),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF8A94A3),
                    )

                    Text(
                        text = stringResource(R.string.updates_node_engine, nodeVersion),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF8A94A3),
                    )

                    Text(
                        text = stringResource(R.string.updates_channel, channelText),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF8A94A3),
                    )

                    Text(
                        text = stringResource(R.string.updates_status, statusText),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isInstalled) Color(0xFF7EC8A9) else Color(0xFFE5C07B),
                    )
                }
            }

            // Actions
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Check for updates
                Button(
                    onClick = {
                        Utils.toast(context, context.getString(R.string.updates_phase_l4_notice))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.updates_btn_check))
                    }
                }

                // Import custom archive (.zip)
                OutlinedButton(
                    onClick = onImportArchive,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Archive, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.updates_btn_import_archive))
                    }
                }

                // Reinstall / Repair Payload
                Button(
                    onClick = { showResetDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4A2525),
                        contentColor = Color(0xFFFFB4AB),
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

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(stringResource(R.string.dialog_reset_payload_title)) },
            text = { Text(stringResource(R.string.dialog_reset_payload_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    showResetDialog = false
                    onResetPayload()
                }) {
                    Text(stringResource(R.string.btn_reset))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            },
        )
    }
}
