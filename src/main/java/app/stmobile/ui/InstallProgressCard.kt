package app.stmobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.stmobile.R
import app.stmobile.payload.PayloadManager

/**
 * Shared extraction/installation progress card displaying progress percentage,
 * bytes or files processed, transfer speeds, current file name, and an optional cancel button.
 */
@Composable
fun InstallProgressCard(
    progress: PayloadManager.ExtractProgress?,
    onCancel: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val hasBytes = (progress?.totalBytes ?: 0L) > 0L && (progress?.bytesProcessed ?: 0L) > 0L
    val pct = if (hasBytes) {
        (progress!!.bytesProcessed.toFloat() / progress.totalBytes.toFloat()).coerceIn(0f, 1f)
    } else if ((progress?.totalEstimate ?: 0) > 0 && (progress?.filesProcessed ?: 0) > 0) {
        (progress!!.filesProcessed.toFloat() / progress.totalEstimate.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Card(
        modifier = modifier.fillMaxWidth(),
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
                text = stringResource(R.string.dashboard_status_installing),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.tertiary,
            )

            Text(
                text = progress?.stage ?: stringResource(R.string.dashboard_status_installing),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
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
                        val currentMb = progress!!.bytesProcessed.toDouble() / (1024.0 * 1024.0)
                        val totalMb = progress.totalBytes.toDouble() / (1024.0 * 1024.0)
                        val speedText = if (progress.speedBytesPerSec > 0) {
                            val speedMb = progress.speedBytesPerSec.toDouble() / (1024.0 * 1024.0)
                            " (%.1f MB/s)".format(speedMb)
                        } else ""
                        Text(
                            text = "%.1f MB / %.1f MB%s".format(currentMb, totalMb, speedText),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            text = "${progress!!.filesProcessed} / ${progress.totalEstimate} files",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "${(pct * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                )
            }

            progress?.currentFile?.let { currentFile ->
                Text(
                    text = currentFile,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (onCancel != null) {
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        }
    }
}
