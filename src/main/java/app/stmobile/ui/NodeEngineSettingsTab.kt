package app.stmobile.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.stmobile.R
import app.stmobile.Utils
import app.stmobile.models.NodeConfig
import kotlin.math.roundToInt

@Composable
fun NodeEngineSettingsTab(
    modifier: Modifier = Modifier,
    context: Context = LocalContext.current,
) {
    val initialConfig = remember { NodeConfig.load(context) }
    var maxOldSpace by remember { mutableIntStateOf(initialConfig.maxOldSpaceSizeMb) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsCategoryHeader(stringResource(R.string.engine_category_memory))

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
                val updated = initialConfig.copy(maxOldSpaceSizeMb = maxOldSpace)
                runCatching {
                    NodeConfig.save(context, updated)
                }.onFailure { e ->
                    Utils.toast(context, context.getString(R.string.toast_save_engine_failed, e.message ?: ""))
                }
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
}
