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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.stmobile.sillytavern.BackupManager

/**
 * Reusable dialogs for SillyTavern backup export and restore workflows (Phase F).
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
        title = { Text("Export Backup") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Archive all characters, chats, settings, and config.yaml into a ZIP file.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFD8DEE6),
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
                        text = "Encrypt with password (AES-256)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFD8DEE6),
                    )
                }

                if (encryptChecked) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "Toggle password visibility",
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it },
                        label = { Text("Confirm Password") },
                        singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        isError = encryptChecked && confirmPassword.isNotBlank() && password != confirmPassword,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (encryptChecked && confirmPassword.isNotBlank() && password != confirmPassword) {
                        Text(
                            text = "Passwords do not match",
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
                Text("Export")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
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
        title = { Text("Encrypted Backup") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "This archive is protected with AES-256 encryption. Enter the password to unlock:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFD8DEE6),
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Archive Password") },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "Toggle password visibility",
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
                Text("Unlock")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
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
        title = { Text("Restore Backup") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Archive manifest preview
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2228)),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "Archive Details",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF7EC8A9),
                        )
                        val stVer = info.manifest?.stVersion ?: "Legacy / External"
                        Text(
                            text = "SillyTavern Version: $stVer",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFD8DEE6),
                        )
                        Text(
                            text = "Files to restore: ${info.fileCount}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF8A94A3),
                        )
                        info.manifest?.createdAt?.let { created ->
                            Text(
                                text = "Created: ${created.take(19).replace('T', ' ')}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF8A94A3),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Select Restore Strategy:",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color(0xFFD8DEE6),
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
                            text = "Clean Restore (Replace)",
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFD8DEE6),
                        )
                        Text(
                            text = "Atomically replaces user data. Removes stale/orphaned files.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF8A94A3),
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
                            text = "Merge",
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFD8DEE6),
                        )
                        Text(
                            text = "Overwrites conflicting files, keeping local files not in archive.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF8A94A3),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(selectedStrategy) }) {
                Text("Restore")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
