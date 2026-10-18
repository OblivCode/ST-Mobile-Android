package app.stmobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.stmobile.R
import app.stmobile.backup.BackupManager

/**
 * Reusable dialogs for SillyTavern backup export and restore workflows.
 */

/**
 * Dialog prompting for optional AES-256 archive encryption during export.
 */
@Composable
fun ExportBackupDialog(
    onDismiss: () -> Unit,
    onConfirm: (password: String?) -> Unit,
) {
    var encryptChecked by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    val passwordsMatch = !encryptChecked || (password.isNotBlank() && password == confirmPassword)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_export_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.dialog_export_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { encryptChecked = !encryptChecked },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = encryptChecked,
                        onCheckedChange = { encryptChecked = it },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.dialog_export_encrypt),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                if (encryptChecked) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.dialog_export_password)) },
                        singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = stringResource(R.string.cd_toggle_pwd),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it },
                        label = { Text(stringResource(R.string.dialog_export_confirm_password)) },
                        singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        isError = encryptChecked && confirmPassword.isNotBlank() && password != confirmPassword,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (encryptChecked && confirmPassword.isNotBlank() && password != confirmPassword) {
                        Text(
                            text = stringResource(R.string.dialog_export_mismatch),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalPass = if (encryptChecked) password else null
                    onConfirm(finalPass)
                },
                enabled = passwordsMatch,
            ) {
                Text(stringResource(R.string.btn_export))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        },
    )
}

/**
 * Dialog prompting for password to unlock an AES-256 encrypted backup archive.
 */
@Composable
fun ImportPasswordDialog(
    errorMessage: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (password: String) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_import_pwd_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.dialog_import_pwd_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.dialog_import_pwd_label)) },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = stringResource(R.string.cd_toggle_pwd),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(password) },
                enabled = password.isNotBlank(),
            ) {
                Text(stringResource(R.string.btn_unlock))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        },
    )
}

/**
 * Dialog presenting archive metadata and Clean vs Merge restore strategies.
 */
@Composable
fun ImportStrategyDialog(
    info: BackupManager.ArchiveInfo,
    onDismiss: () -> Unit,
    onConfirm: (strategy: BackupManager.Strategy) -> Unit,
) {
    var selectedStrategy by remember { mutableStateOf(BackupManager.Strategy.CLEAN) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_restore_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Archive manifest preview
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.dialog_restore_archive_details),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        val stVer = info.manifest?.stVersion ?: stringResource(R.string.restore_legacy_external)
                        Text(
                            text = stringResource(R.string.dialog_restore_st_version, stVer),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.dialog_restore_files_count, info.fileCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        info.manifest?.createdAt?.let { created ->
                            Text(
                                text = stringResource(R.string.dialog_restore_created, created.take(19).replace('T', ' ')),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.dialog_restore_strategy_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                // Clean Restore Option
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedStrategy = BackupManager.Strategy.CLEAN },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selectedStrategy == BackupManager.Strategy.CLEAN,
                        onClick = { selectedStrategy = BackupManager.Strategy.CLEAN },
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.dialog_restore_clean_title),
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.dialog_restore_clean_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Merge Option
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedStrategy = BackupManager.Strategy.MERGE },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selectedStrategy == BackupManager.Strategy.MERGE,
                        onClick = { selectedStrategy = BackupManager.Strategy.MERGE },
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.dialog_restore_merge_title),
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.dialog_restore_merge_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(selectedStrategy) }) {
                Text(stringResource(R.string.btn_restore))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        },
    )
}
