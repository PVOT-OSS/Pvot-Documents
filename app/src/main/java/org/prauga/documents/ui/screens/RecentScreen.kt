package org.prauga.documents.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prauga.pvot.designsystem.components.PvotScreen
import com.prauga.pvot.designsystem.components.PvotScreenDefaults
import com.prauga.pvot.designsystem.components.button.PvotIconButton
import com.prauga.pvot.designsystem.components.list.PvotListGroup
import com.prauga.pvot.designsystem.components.list.PvotListRow
import com.prauga.pvot.designsystem.motion.pvotReveal
import org.prauga.documents.AppPreferences
import org.prauga.documents.R
import org.prauga.documents.util.documentKind
import org.prauga.documents.util.formatFileSize
import org.prauga.documents.util.shareDocument

/**
 * Documents the app was opened or shared with, most recent first — see
 * [org.prauga.documents.ui.screens.IncomingDocumentHandler]. Opening one is phase 2; tapping it
 * for now only reports that through [onUnsupported].
 */
@Composable
fun RecentScreen(
    preferences: AppPreferences,
    state: LazyListState,
    topPadding: Dp,
    onUnsupported: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val documents = preferences.recentDocuments
    if (documents.isEmpty()) {
        EmptyTab(state, topPadding, R.drawable.ic_recent, stringResource(R.string.recent_empty), modifier)
        return
    }

    val context = LocalContext.current
    val openUnsupportedMessage = stringResource(R.string.manage_open_unsupported)
    PvotScreen(
        modifier = modifier,
        state = state,
        contentPadding = PvotScreenDefaults.contentPadding(top = topPadding)
    ) {
        item {
            PvotListGroup(modifier = Modifier.fillMaxWidth().pvotReveal(0)) {
                documents.forEach { document ->
                    item {
                        PvotListRow(
                            title = document.name,
                            supportingText = formatFileSize(document.sizeBytes),
                            leading = {
                                Icon(
                                    painterResource(R.drawable.ic_file_text),
                                    contentDescription = null,
                                    tint = documentKind(document.name, document.mimeType).color()
                                )
                            },
                            trailing = {
                                PvotIconButton(
                                    icon = painterResource(R.drawable.ic_share),
                                    contentDescription = stringResource(
                                        R.string.manage_share_document,
                                        document.name
                                    ),
                                    onClick = { shareDocument(context, document) },
                                    size = 36.dp
                                )
                            },
                            onClick = { onUnsupported(openUnsupportedMessage) }
                        )
                    }
                }
            }
        }
    }
}
