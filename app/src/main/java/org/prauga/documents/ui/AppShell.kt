package org.prauga.documents.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import com.prauga.pvot.designsystem.components.PvotScreenScaffold
import com.prauga.pvot.designsystem.components.PvotSnackbarHost
import com.prauga.pvot.designsystem.navigation.PvotNavBar
import com.prauga.pvot.designsystem.navigation.PvotTabItem
import com.prauga.pvot.designsystem.navigation.PvotTopBar
import com.prauga.pvot.designsystem.navigation.rememberPvotTopBarCompact
import kotlinx.coroutines.launch
import org.prauga.documents.AppPreferences
import org.prauga.documents.R
import org.prauga.documents.ui.screens.ManageScreen
import org.prauga.documents.ui.screens.RecentScreen
import org.prauga.documents.ui.screens.SettingsScreen
import org.prauga.documents.ui.screens.ToolsScreen

/** Pixels of scroll in one event before the nav bar reacts, so a jitter doesn't flip it. */
private const val ScrollThreshold = 4f

private val Tabs = listOf(
    PvotTabItem(
        iconRes = R.drawable.ic_recent,
        labelRes = R.string.tab_recent,
        contentDescriptionRes = R.string.cd_recent
    ),
    PvotTabItem(
        iconRes = R.drawable.ic_tools,
        labelRes = R.string.tab_tools,
        contentDescriptionRes = R.string.cd_tools
    ),
    PvotTabItem(
        iconRes = R.drawable.ic_folder,
        labelRes = R.string.tab_manage,
        contentDescriptionRes = R.string.cd_manage
    ),
    PvotTabItem(
        iconRes = R.drawable.ic_settings,
        labelRes = R.string.tab_settings,
        contentDescriptionRes = R.string.cd_settings
    )
)

/**
 * The app's frame: a top bar titled after the current tab and four tabs on a glass nav bar, with
 * each screen scrolling under both.
 */
@Composable
fun AppShell(preferences: AppPreferences) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var navCompact by rememberSaveable { mutableStateOf(false) }
    // Swiping up through a list shrinks the nav bar out of the way; swiping back down restores it.
    val scrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -ScrollThreshold) navCompact = true
                else if (available.y > ScrollThreshold) navCompact = false
                return Offset.Zero
            }
        }
    }
    // One scroll state per tab, so switching tabs keeps each list where it was.
    val listStates = Tabs.map { rememberLazyListState() }
    val listState = listStates[selectedTab]
    val topBarCompact = rememberPvotTopBarCompact(listState)

    // For feedback on things that aren't built yet, such as opening a document (phase 2).
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val showMessage: (String) -> Unit = { message ->
        coroutineScope.launch { snackbarHostState.showSnackbar(message) }
    }

    PvotScreenScaffold(
        topBar = {
            PvotTopBar(
                title = stringResource(Tabs[selectedTab].labelRes),
                compact = topBarCompact
            )
        },
        bottomBar = {
            PvotNavBar(
                selectedTab = selectedTab,
                onTabClick = {
                    selectedTab = it
                    navCompact = false
                },
                tabs = Tabs,
                compact = navCompact
            )
        },
        snackbarHost = { PvotSnackbarHost(snackbarHostState) }
    ) { padding ->
        val screenModifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollConnection)
        val topPadding = padding.calculateTopPadding()

        when (selectedTab) {
            0 -> RecentScreen(preferences, listState, topPadding, showMessage, screenModifier)
            1 -> ToolsScreen(listState, topPadding, screenModifier)
            2 -> ManageScreen(preferences, listState, topPadding, showMessage, screenModifier)
            else -> SettingsScreen(preferences, listState, topPadding, screenModifier)
        }
    }
}
