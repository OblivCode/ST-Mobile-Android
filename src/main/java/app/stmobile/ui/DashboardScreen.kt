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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.stmobile.models.NodeState
import app.stmobile.models.NodeStatus

@Composable
fun DashboardScreen(
    status: NodeStatus,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onOpenSillyTavern: () -> Unit,
    modifier: Modifier = Modifier,
) {

    val isRunning = status.state == NodeState.RUNNING
    val isStarting = status.state == NodeState.STARTING
    val isStopped = status.state == NodeState.STOPPED
    val isError = status.state == NodeState.ERROR

    val canStart = isStopped || isError
    val canStop = isRunning || isStarting
    val canOpen = isRunning

    val stateColor = when (status.state) {
        NodeState.RUNNING -> Color(0xFF7EC8A9)
        NodeState.STARTING -> Color(0xFFE5C07B)
        NodeState.STOPPING -> Color(0xFFD19A66)
        NodeState.STOPPED -> Color(0xFF8A94A3)
        NodeState.ERROR -> Color(0xFFE06C75)
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
                            text = status.state.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = stateColor,
                        )
                        if (status.port > 0) {
                            Text(
                                text = "Port: ${status.port}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFF8A94A3),
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = status.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFD8DEE6),
                    )

                    if (status.pid != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "PID: ${status.pid}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF8A94A3),
                        )
                    }
                }
            }

            // Action Buttons
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onStartServer,
                    enabled = canStart,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("Start Server")
                }

                Button(
                    onClick = onStopServer,
                    enabled = canStop,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4A2525),
                        contentColor = Color(0xFFFFB4AB),
                        disabledContainerColor = Color(0xFF2A2020),
                        disabledContentColor = Color(0xFF6B5858),
                    ),
                ) {
                    Text("Stop Server")
                }

                Button(
                    onClick = onOpenSillyTavern,
                    enabled = canOpen,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2E5A44),
                        contentColor = Color(0xFFB5EAD0),
                        disabledContainerColor = Color(0xFF1E2F26),
                        disabledContentColor = Color(0xFF4D6657),
                    ),
                ) {
                    Text("Open SillyTavern")
                }
            }
        }
    }
}
