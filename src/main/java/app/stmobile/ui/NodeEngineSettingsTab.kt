package app.stmobile.ui

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import app.stmobile.foreground.ForegroundService
import app.stmobile.models.NodeConfig
import app.stmobile.models.NodeState
import app.stmobile.node.NodeController
import kotlin.math.roundToInt

@Composable
fun NodeEngineSettingsTab(
    modifier: Modifier = Modifier,
    context: Context = LocalContext.current,
) {
    val initialConfig = remember { NodeConfig.load(context) }
    var currentConfig by remember { mutableStateOf(initialConfig) }

    var maxOldSpace by remember { mutableIntStateOf(initialConfig.maxOldSpaceSizeMb) }
    var maxSemiSpace by remember { mutableIntStateOf(initialConfig.maxSemiSpaceSizeMb) }
    var optimizeForSize by remember { mutableStateOf(initialConfig.optimizeForSize) }
    var uvThreadPool by remember { mutableIntStateOf(initialConfig.uvThreadPoolSize) }
    var nodeEnv by remember { mutableStateOf(initialConfig.nodeEnv) }
    var extraOptionsText by remember { mutableStateOf(initialConfig.extraNodeOptions.joinToString(" ")) }

    val nodeStatus by NodeController.status.collectAsState()
    val isServerRunning = nodeStatus.isActive

    var configChangedSinceLaunch by remember { mutableStateOf(false) }

    fun persist(updated: NodeConfig) {
        currentConfig = updated
        configChangedSinceLaunch = true
        runCatching {
            NodeConfig.save(context, updated)
        }.onFailure { e ->
            Utils.toast(context, context.getString(R.string.toast_save_engine_failed, e.message ?: ""))
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Restart notification banner if modified while server is running
        if (isServerRunning && configChangedSinceLaunch) {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF2B2516),
                    contentColor = Color(0xFFE5A93C),
                ),
                border = BorderStroke(1.dp, Color(0xFFE5A93C).copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = stringResource(R.string.engine_restart_notice),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFE5A93C),
                        )
                    }
                    Button(
                        onClick = {
                            ForegroundService.restart(context)
                            configChangedSinceLaunch = false
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFE5A93C),
                            contentColor = Color(0xFF101418),
                        ),
                    ) {
                        Text(
                            text = stringResource(R.string.btn_restart_server),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }

        // =====================================================================
        // Subsection 1: Memory & V8
        // =====================================================================
        SettingsCategoryHeader(stringResource(R.string.engine_category_memory))

        // Max Heap Size
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.engine_max_heap, maxOldSpace),
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFFD8DEE6),
            )
            Slider(
                value = maxOldSpace.toFloat(),
                onValueChange = { value ->
                    maxOldSpace = value.roundToInt()
                },
                onValueChangeFinished = {
                    persist(currentConfig.copy(maxOldSpaceSizeMb = maxOldSpace))
                },
                valueRange = 256f..4096f,
                steps = 14, // in ~256MB increments
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.engine_max_heap_desc),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF8A94A3),
            )
        }

        // Young Gen Nursery Size
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.engine_young_gen, maxSemiSpace),
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFFD8DEE6),
            )
            val semiSpaceOptions = listOf(8, 16, 32, 64)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                semiSpaceOptions.forEach { size ->
                    val selected = maxSemiSpace == size
                    OutlinedButton(
                        onClick = {
                            maxSemiSpace = size
                            persist(currentConfig.copy(maxSemiSpaceSizeMb = size))
                        },
                        modifier = Modifier.weight(1f),
                        colors = if (selected) {
                            ButtonDefaults.outlinedButtonColors(
                                containerColor = Color(0xFF7EC8A9).copy(alpha = 0.2f),
                                contentColor = Color(0xFF7EC8A9),
                            )
                        } else {
                            ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFF8A94A3),
                            )
                        },
                        border = BorderStroke(
                            1.dp,
                            if (selected) Color(0xFF7EC8A9) else Color(0xFF2C353D),
                        ),
                    ) {
                        Text(
                            text = "$size MB",
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            Text(
                text = stringResource(R.string.engine_young_gen_desc),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF8A94A3),
            )
        }

        // Optimize For Size (Low Memory Mode)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text(stringResource(R.string.engine_optimize_for_size), color = Color(0xFFD8DEE6))
                Text(
                    stringResource(R.string.engine_optimize_for_size_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF8A94A3),
                )
            }
            Switch(
                checked = optimizeForSize,
                onCheckedChange = {
                    optimizeForSize = it
                    persist(currentConfig.copy(optimizeForSize = it))
                },
            )
        }

        // =====================================================================
        // Subsection 2: Concurrency & Threading
        // =====================================================================
        SettingsCategoryHeader(stringResource(R.string.engine_category_threading))

        // libuv Worker Threadpool
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.engine_threadpool, uvThreadPool),
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFFD8DEE6),
            )
            val threadpoolOptions = listOf(2, 4, 8)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                threadpoolOptions.forEach { count ->
                    val selected = uvThreadPool == count
                    OutlinedButton(
                        onClick = {
                            uvThreadPool = count
                            persist(currentConfig.copy(uvThreadPoolSize = count))
                        },
                        modifier = Modifier.weight(1f),
                        colors = if (selected) {
                            ButtonDefaults.outlinedButtonColors(
                                containerColor = Color(0xFF7EC8A9).copy(alpha = 0.2f),
                                contentColor = Color(0xFF7EC8A9),
                            )
                        } else {
                            ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFF8A94A3),
                            )
                        },
                        border = BorderStroke(
                            1.dp,
                            if (selected) Color(0xFF7EC8A9) else Color(0xFF2C353D),
                        ),
                    ) {
                        Text(
                            text = "$count",
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            Text(
                text = stringResource(R.string.engine_threadpool_desc),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF8A94A3),
            )
        }

        // =====================================================================
        // Subsection 3: Environment & Flags
        // =====================================================================
        SettingsCategoryHeader(stringResource(R.string.engine_category_environment))

        // NODE_ENV
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.engine_node_env),
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFFD8DEE6),
            )
            val envOptions = listOf("production", "development")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                envOptions.forEach { env ->
                    val selected = nodeEnv.equals(env, ignoreCase = true)
                    OutlinedButton(
                        onClick = {
                            nodeEnv = env
                            persist(currentConfig.copy(nodeEnv = env))
                        },
                        modifier = Modifier.weight(1f),
                        colors = if (selected) {
                            ButtonDefaults.outlinedButtonColors(
                                containerColor = Color(0xFF7EC8A9).copy(alpha = 0.2f),
                                contentColor = Color(0xFF7EC8A9),
                            )
                        } else {
                            ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFF8A94A3),
                            )
                        },
                        border = BorderStroke(
                            1.dp,
                            if (selected) Color(0xFF7EC8A9) else Color(0xFF2C353D),
                        ),
                    ) {
                        Text(
                            text = env.replaceFirstChar { it.uppercase() },
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            Text(
                text = stringResource(R.string.engine_node_env_desc),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF8A94A3),
            )
        }

        // Extra Node.js Options
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                value = extraOptionsText,
                onValueChange = { newText ->
                    extraOptionsText = newText
                    val parsedList = newText.split("\\s+".toRegex()).map { it.trim() }.filter { it.isNotEmpty() }
                    persist(currentConfig.copy(extraNodeOptions = parsedList))
                },
                label = { Text(stringResource(R.string.engine_extra_options)) },
                placeholder = { Text(stringResource(R.string.engine_extra_options_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Text(
                text = stringResource(R.string.engine_extra_options_desc),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF8A94A3),
            )
        }
    }
}
