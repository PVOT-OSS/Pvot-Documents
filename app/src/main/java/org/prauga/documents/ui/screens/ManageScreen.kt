package org.prauga.documents.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prauga.pvot.coreui.PvotEmptyContent
import com.prauga.pvot.designsystem.components.PvotScreen
import com.prauga.pvot.designsystem.components.PvotScreenDefaults
import com.prauga.pvot.designsystem.components.button.PvotIconButton
import com.prauga.pvot.designsystem.components.list.PvotListGroup
import com.prauga.pvot.designsystem.components.list.PvotListRow
import com.prauga.pvot.designsystem.motion.pvotReveal
import org.prauga.documents.AppPreferences
import org.prauga.documents.R
import org.prauga.documents.data.DocumentsRepository
import org.prauga.documents.models.DocumentEntry
import org.prauga.documents.models.FolderEntry

/**
 * Folders the user added, and the documents found directly inside them. Opening a document is
 * phase 2; tapping one for now only reports that through [onUnsupported].
 */
@Composable
fun ManageScreen(
    preferences: AppPreferences,
    state: LazyListState,
    topPadding: Dp,
    onUnsupported: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val repository = remember { DocumentsRepository(context) }
    var folders by remember { mutableStateOf<List<FolderEntry>>(emptyList()) }
    var documents by remember { mutableStateOf<List<DocumentEntry>>(emptyList()) }
    val openUnsupportedMessage = stringResource(R.string.manage_open_unsupported)

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) preferences.addFolder(uri, context.contentResolver)
    }

    // Re-reads every added folder whenever the set changes; each folder is a handful of files, so
    // re-listing on every change is simpler than diffing and still fast enough to feel instant.
    LaunchedEffect(preferences.folderUris) {
        val uris = preferences.folderUris.map(Uri::parse)
        folders = uris.mapNotNull { repository.folder(it) }
        documents = folders.flatMap { repository.documentsIn(it.treeUri) }
    }

    if (folders.isEmpty()) {
        PvotScreen(
            modifier = modifier,
            state = state,
            contentPadding = PvotScreenDefaults.contentPadding(top = topPadding)
        ) {
            item {
                Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                    PvotEmptyContent(
                        message = stringResource(R.string.manage_empty),
                        icon = {
                            Icon(painterResource(R.drawable.ic_folder), contentDescription = null)
                        },
                        actionLabel = stringResource(R.string.manage_add_folder),
                        onAction = { pickFolder.launch(null) }
                    )
                }
            }
        }
        return
    }

    PvotScreen(
        modifier = modifier,
        state = state,
        contentPadding = PvotScreenDefaults.contentPadding(top = topPadding)
    ) {
        item {
            PvotListGroup(
                modifier = Modifier.fillMaxWidth().pvotReveal(0),
                title = stringResource(R.string.manage_section_folders)
            ) {
                folders.forEach { folder ->
                    item {
                        PvotListRow(
                            title = folder.displayName,
                            leading = {
                                Icon(
                                    painterResource(R.drawable.ic_folder),
                                    contentDescription = null
                                )
                            },
                            trailing = {
                                PvotIconButton(
                                    icon = painterResource(R.drawable.ic_x),
                                    contentDescription = stringResource(
                                        R.string.manage_remove_folder,
                                        folder.displayName
                                    ),
                                    onClick = {
                                        preferences.removeFolder(
                                            folder.treeUri,
                                            context.contentResolver
                                        )
                                    },
                                    size = 36.dp
                                )
                            }
                        )
                    }
                }
                item {
                    PvotListRow(
                        title = stringResource(R.string.manage_add_folder),
                        leading = {
                            Icon(
                                painterResource(R.drawable.ic_folder_plus),
                                contentDescription = null
                            )
                        },
                        onClick = { pickFolder.launch(null) }
                    )
                }
            }
        }
        if (documents.isNotEmpty()) {
            item {
                PvotListGroup(
                    modifier = Modifier.fillMaxWidth().pvotReveal(1),
                    title = stringResource(R.string.manage_section_documents)
                ) {
                    documents.forEach { document ->
                        item {
                            PvotListRow(
                                title = document.name,
                                supportingText = formatFileSize(document.sizeBytes),
                                leading = {
                                    Icon(
                                        painterResource(R.drawable.ic_file_text),
                                        contentDescription = null
                                    )
                                },
                                onClick = { onUnsupported(openUnsupportedMessage) }
                            )
                        }
                    }
                }
            }
        } else {
            item {
                Text(
                    text = stringResource(R.string.manage_no_documents),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().pvotReveal(1)
                )
            }
        }
    }
}

/** A byte count as "12 KB", "3.4 MB" and so on, to the nearest common unit. */
private fun formatFileSize(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return if (unitIndex == 0) "${bytes} ${units[0]}" else "%.1f %s".format(value, units[unitIndex])
}
