package org.prauga.documents.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.prauga.pvot.designsystem.theme.PvotSpacing
import org.prauga.documents.AppPreferences
import org.prauga.documents.R
import org.prauga.documents.util.DocumentKind
import org.prauga.documents.util.documentKind
import org.prauga.documents.util.formatFileSize
import org.prauga.documents.util.shareDocument

/**
 * Documents the app was opened or shared with, most recent first, filterable by type — see
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

    // Only the kinds actually present get a chip, so "All" is never alone on the row.
    val kindsPresent = remember(documents) {
        documents.map { documentKind(it.name, it.mimeType) }.distinct()
    }
    var selectedKind by remember { mutableStateOf<DocumentKind?>(null) }
    val visibleDocuments = selectedKind?.let { kind ->
        documents.filter { documentKind(it.name, it.mimeType) == kind }
    } ?: documents

    PvotScreen(
        modifier = modifier,
        state = state,
        contentPadding = PvotScreenDefaults.contentPadding(top = topPadding)
    ) {
        if (kindsPresent.size > 1) {
            item {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().pvotReveal(0),
                    horizontalArrangement = Arrangement.spacedBy(PvotSpacing.S)
                ) {
                    item {
                        FilterChip(
                            selected = selectedKind == null,
                            onClick = { selectedKind = null },
                            label = { Text(stringResource(R.string.recent_filter_all)) }
                        )
                    }
                    items(kindsPresent) { kind ->
                        FilterChip(
                            selected = selectedKind == kind,
                            onClick = {
                                selectedKind = if (selectedKind == kind) null else kind
                            },
                            label = { Text(stringResource(kind.labelRes)) }
                        )
                    }
                }
            }
        }
        if (visibleDocuments.isEmpty()) {
            item {
                Text(
                    text = stringResource(
                        R.string.recent_no_filter_results,
                        selectedKind?.let { stringResource(it.labelRes) }.orEmpty()
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().pvotReveal(1)
                )
            }
        } else {
            item {
                PvotListGroup(modifier = Modifier.fillMaxWidth().pvotReveal(1)) {
                    visibleDocuments.forEach { document ->
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
}
