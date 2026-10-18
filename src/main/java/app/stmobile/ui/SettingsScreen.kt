package app.stmobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults.SecondaryIndicator
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import app.stmobile.R
import app.stmobile.models.AppConfig

@Composable
fun SettingsScreen(
    onPromptBattery: () -> Unit,
    modifier: Modifier = Modifier,
    onExportLogs: () -> Unit = {},
    onCopyDiagnostics: () -> Unit = {},
) {
    val context = LocalContext.current
    val appConfig = remember { AppConfig(context) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val tabTitles = listOf(
        stringResource(R.string.tab_app),
        stringResource(R.string.tab_sillytavern),
        stringResource(R.string.tab_node_engine),
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF101418)),
    ) {
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color(0xFF1A2228),
            contentColor = Color(0xFF7EC8A9),
            indicator = { tabPositions ->
                SecondaryIndicator(
                    Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                    color = Color(0xFF7EC8A9),
                )
            },
        ) {
            tabTitles.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            text = title,
                            fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                            color = if (selectedTab == index) Color(0xFF7EC8A9) else Color(0xFF8A94A3),
                        )
                    },
                )
            }
        }

        when (selectedTab) {
            0 -> AppSettingsTab(
                appConfig = appConfig,
                onPromptBattery = onPromptBattery,
                onExportLogs = onExportLogs,
                onCopyDiagnostics = onCopyDiagnostics,
            )
            1 -> SillyTavernSettingsTab()
            2 -> NodeEngineSettingsTab()
        }
    }
}
