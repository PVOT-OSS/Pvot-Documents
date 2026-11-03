package org.prauga.documents.ui.screens

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import com.prauga.pvot.coreui.PvotEmptyContent
import com.prauga.pvot.designsystem.components.PvotScreen
import com.prauga.pvot.designsystem.components.PvotScreenDefaults
import org.prauga.documents.R

// Phase 0 placeholder: Tools fills in with the scanner (phase 5) and PDF/conversion tools
// (phase 4). Recent has its own screen now: RecentScreen.kt. Manage has its own: ManageScreen.kt.

@Composable
fun ToolsScreen(state: LazyListState, topPadding: Dp, modifier: Modifier = Modifier) {
    EmptyTab(state, topPadding, R.drawable.ic_tools, stringResource(R.string.tools_empty), modifier)
}

@Composable
internal fun EmptyTab(
    state: LazyListState,
    topPadding: Dp,
    @DrawableRes iconRes: Int,
    message: String,
    modifier: Modifier = Modifier
) {
    PvotScreen(
        modifier = modifier,
        state = state,
        contentPadding = PvotScreenDefaults.contentPadding(top = topPadding)
    ) {
        item {
            Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                PvotEmptyContent(
                    message = message,
                    icon = { Icon(painterResource(iconRes), contentDescription = null) }
                )
            }
        }
    }
}
