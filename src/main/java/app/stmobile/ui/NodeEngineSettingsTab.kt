package app.stmobile.ui

import android.app.ActivityManager
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.stmobile.R
import app.stmobile.Utils
import app.stmobile.foreground.ForegroundService
import app.stmobile.models.NodeConfig
import app.stmobile.node.NodeController
import kotlin.math.round

@Composable
fun NodeEngineSettingsTab(
    modifier: Modifier = Modifier,
    context: Context = LocalContext.current,
    nodeConfig: NodeConfig = remember { NodeConfig.load(context) },
    onSaveNodeConfig: (NodeConfig) -> Unit = { NodeConfig.save(context, it) },
    onRequestDialog: (SettingsDialogType) -> Unit = {},
) {
    var maxOldSpace by remember(nodeConfig.maxOldSpaceSizeMb) { mutableIntStateOf(nodeConfig.maxOldSpaceSizeMb) }
    var maxSemiSpace by remember(nodeConfig.maxSemiSpaceSizeMb) { mutableIntStateOf(nodeConfig.maxSemiSpaceSizeMb) }
    var optimizeForSize by remember(nodeConfig.optimizeForSize) { mutableStateOf(nodeConfig.optimizeForSize) }
    var uvThreadPool by remember(nodeConfig.uvThreadPoolSize) { mutableIntStateOf(nodeConfig.uvThreadPoolSize) }
    var extraOptionsText by remember(nodeConfig.extraNodeOptions) { mutableStateOf(nodeConfig.extraNodeOptions.joinToString(" ")) }

    val nodeStatus by NodeController.status.collectAsState()
    val isServerRunning = nodeStatus.isActive

    var configChangedSinceLaunch by remember { mutableStateOf(false) }

    val deviceCores = remember { Runtime.getRuntime().availableProcessors().coerceIn(2, 16) }
    val totalRamMb = remember {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)
        (memInfo.totalMem / (1024L * 1024L)).toInt()
    }
    val safeMaxHeapMb = remember(totalRamMb) {
        (totalRamMb * 0.5f).toInt().coerceIn(512, NodeConfig.MAX_OLD_SPACE_SIZE_MB)
    }

    fun persist(updated: NodeConfig) {
        configChangedSinceLaunch = true
        runCatching {
            onSaveNodeConfig(updated)
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
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f)),
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
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                    Button(
                        onClick = {
                            ForegroundService.restart(context)
                            configChangedSinceLaunch = false
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary,
                            contentColor = MaterialTheme.colorScheme.onTertiary,
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

        // V8 Maximum Heap Size
        SettingSliderRow(
            title = stringResource(R.string.engine_max_heap, maxOldSpace),
            desc = stringResource(R.string.engine_max_heap_desc),
            value = maxOldSpace.toFloat(),
            range = 256f..safeMaxHeapMb.toFloat(),
            steps = ((safeMaxHeapMb - 256) / 256).coerceAtLeast(0),
            onValueChange = { value ->
                maxOldSpace = (round(value / 256f) * 256f).toInt().coerceIn(256, safeMaxHeapMb)
            },
            onValueChangeFinished = {
                persist(nodeConfig.copy(maxOldSpaceSizeMb = maxOldSpace))
            },
        )

        // Young Gen Nursery Size
        SettingSliderRow(
            title = stringResource(R.string.engine_young_gen, maxSemiSpace),
            desc = stringResource(R.string.engine_young_gen_desc),
            value = maxSemiSpace.toFloat(),
            range = 8f..64f,
            steps = 6,
            onValueChange = { value ->
                maxSemiSpace = (round(value / 8f) * 8f).toInt().coerceIn(8, 64)
            },
            onValueChangeFinished = {
                persist(nodeConfig.copy(maxSemiSpaceSizeMb = maxSemiSpace))
            },
        )

        // Low Memory Footprint Mode (Optimize For Size)
        SettingSwitchRow(
            title = stringResource(R.string.engine_optimize_for_size),
            desc = stringResource(R.string.engine_optimize_for_size_desc),
            checked = optimizeForSize,
            onCheckedChange = {
                optimizeForSize = it
                persist(nodeConfig.copy(optimizeForSize = it))
            },
        )

        // =====================================================================
        // Subsection 2: Concurrency & Threading
        // =====================================================================
        SettingsCategoryHeader(stringResource(R.string.engine_category_threading))

        // libuv Worker Threadpool
        SettingSliderRow(
            title = stringResource(R.string.engine_threadpool, uvThreadPool),
            desc = stringResource(R.string.engine_threadpool_desc),
            value = uvThreadPool.toFloat(),
            range = 1f..deviceCores.toFloat(),
            steps = (deviceCores - 2).coerceAtLeast(0),
            onValueChange = { value ->
                uvThreadPool = round(value).toInt().coerceIn(1, deviceCores)
            },
            onValueChangeFinished = {
                persist(nodeConfig.copy(uvThreadPoolSize = uvThreadPool))
            },
        )

        // =====================================================================
        // Subsection 3: Environment & Flags
        // =====================================================================
        SettingsCategoryHeader(stringResource(R.string.engine_category_environment))

        // Runtime Environment (NODE_ENV)
        val envLabel = if (nodeConfig.nodeEnv.equals("production", ignoreCase = true)) {
            stringResource(R.string.engine_node_env_prod)
        } else {
            stringResource(R.string.engine_node_env_dev)
        }
        SettingPickerRow(
            title = stringResource(R.string.engine_node_env),
            desc = stringResource(R.string.engine_node_env_desc),
            currentValueText = envLabel,
            buttonText = stringResource(R.string.btn_change),
            onClick = { onRequestDialog(SettingsDialogType.NODE_ENV) },
        )

        // Extra Node.js Options
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(
                value = extraOptionsText,
                onValueChange = { newText ->
                    extraOptionsText = newText
                    val parsedList = newText.split("\\s+".toRegex()).map { it.trim() }.filter { it.isNotEmpty() }
                    persist(nodeConfig.copy(extraNodeOptions = parsedList))
                },
                label = { Text(stringResource(R.string.engine_extra_options)) },
                placeholder = { Text(stringResource(R.string.engine_extra_options_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Text(
                text = stringResource(R.string.engine_extra_options_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
