package org.prauga.documents.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.prauga.pvot.designsystem.theme.PvotSpacing
import kotlinx.coroutines.launch
import org.prauga.documents.AppPreferences
import org.prauga.documents.R
import org.prauga.documents.data.DocumentsRepository
import org.prauga.documents.models.FolderEntry
import org.prauga.documents.models.ManagedDocument
import org.prauga.documents.models.TrashedDocument
import org.prauga.documents.util.documentKind
import org.prauga.documents.util.formatFileSize
import org.prauga.documents.util.shareDocument

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
    val scope = rememberCoroutineScope()
    var folders by remember { mutableStateOf<List<FolderEntry>>(emptyList()) }
    var documents by remember { mutableStateOf<List<ManagedDocument>>(emptyList()) }
    var refreshToken by remember { mutableIntStateOf(0) }
    var showTrash by remember { mutableStateOf(false) }
    val openUnsupportedMessage = stringResource(R.string.manage_open_unsupported)
    val movedToTrashMessage = stringResource(R.string.manage_moved_to_trash)
    val deleteFailedMessage = stringResource(R.string.manage_delete_failed)

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) preferences.addFolder(uri, context.contentResolver)
    }

    // Re-reads every added folder whenever the set changes, or refreshToken is bumped after a
    // delete; each folder is a handful of files, so re-listing is simpler than patching the list
    // in place and still fast enough to feel instant.
    LaunchedEffect(preferences.folderUris, refreshToken) {
        val uris = preferences.folderUris.map(Uri::parse)
        folders = uris.mapNotNull { repository.folder(it) }
        documents = folders.flatMap { folder ->
            repository.documentsIn(folder.treeUri).map { ManagedDocument(it, folder.treeUri) }
        }
    }

    if (showTrash) {
        RecentlyDeletedSheet(
            preferences = preferences,
            onDismiss = { showTrash = false },
            onRestored = { refreshToken++ },
            onMessage = onUnsupported
        )
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
                item {
                    PvotListRow(
                        title = stringResource(
                            R.string.manage_recently_deleted,
                            preferences.trashedDocuments.size
                        ),
                        leading = {
                            Icon(painterResource(R.drawable.ic_trash), contentDescription = null)
                        },
                        onClick = { showTrash = true }
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
                    documents.forEach { managed ->
                        item {
                            val document = managed.document
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
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(PvotSpacing.XS)
                                    ) {
                                        PvotIconButton(
                                            icon = painterResource(R.drawable.ic_share),
                                            contentDescription = stringResource(
                                                R.string.manage_share_document,
                                                document.name
                                            ),
                                            onClick = { shareDocument(context, document) },
                                            size = 36.dp
                                        )
                                        PvotIconButton(
                                            icon = painterResource(R.drawable.ic_trash),
                                            contentDescription = stringResource(
                                                R.string.manage_delete_document,
                                                document.name
                                            ),
                                            onClick = {
                                                scope.launch {
                                                    val trashed = repository.moveToTrash(document)
                                                    if (trashed != null) {
                                                        preferences.addTrashedDocument(
                                                            TrashedDocument(
                                                                document = trashed,
                                                                folderTreeUri = managed.folderTreeUri,
                                                                deletedAt = System.currentTimeMillis()
                                                            )
                                                        )
                                                        refreshToken++
                                                        onUnsupported(movedToTrashMessage)
                                                    } else {
                                                        onUnsupported(deleteFailedMessage)
                                                    }
                                                }
                                            },
                                            size = 36.dp
                                        )
                                    }
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
