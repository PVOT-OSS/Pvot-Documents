package org.prauga.documents.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.prauga.pvot.designsystem.components.PvotDialog
import com.prauga.pvot.designsystem.components.PvotDialogAction
import com.prauga.pvot.designsystem.components.PvotDialogActionStyle
import com.prauga.pvot.designsystem.components.PvotScreen
import com.prauga.pvot.designsystem.components.PvotScreenDefaults
import com.prauga.pvot.designsystem.components.PvotSegmentedControl
import com.prauga.pvot.designsystem.components.button.PvotIconButton
import com.prauga.pvot.designsystem.components.list.PvotListGroup
import com.prauga.pvot.designsystem.components.list.PvotListRow
import com.prauga.pvot.designsystem.components.textfield.PvotTextField
import com.prauga.pvot.designsystem.motion.pvotReveal
import com.prauga.pvot.designsystem.theme.PvotSpacing
import kotlinx.coroutines.launch
import org.prauga.documents.AppPreferences
import org.prauga.documents.R
import org.prauga.documents.data.DocumentsRepository
import org.prauga.documents.models.FolderEntry
import org.prauga.documents.models.ManagedDocument
import org.prauga.documents.models.TrashedDocument
import org.prauga.documents.util.DocumentSort
import org.prauga.documents.util.documentKind
import org.prauga.documents.util.formatFileSize
import org.prauga.documents.util.shareDocument
import org.prauga.documents.util.sortedByDocument

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
    var actionsFor by remember { mutableStateOf<ManagedDocument?>(null) }
    var renaming by remember { mutableStateOf<ManagedDocument?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var sortOption by remember { mutableStateOf(DocumentSort.Name) }
    val openUnsupportedMessage = stringResource(R.string.manage_open_unsupported)
    val movedToTrashMessage = stringResource(R.string.manage_moved_to_trash)
    val deleteFailedMessage = stringResource(R.string.manage_delete_failed)
    val renameFailedMessage = stringResource(R.string.manage_rename_failed)

    fun delete(managed: ManagedDocument) {
        scope.launch {
            val trashed = repository.moveToTrash(managed.document)
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
    }

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

    actionsFor?.let { managed ->
        DocumentActionsSheet(
            document = managed.document,
            onDismiss = { actionsFor = null },
            onShare = { shareDocument(context, managed.document) },
            onRename = { renaming = managed },
            onDelete = { delete(managed) }
        )
    }

    renaming?.let { managed ->
        var newName by remember(managed) { mutableStateOf(managed.document.name) }
        PvotDialog(
            visible = true,
            onDismissRequest = { renaming = null },
            title = stringResource(R.string.manage_rename_title),
            content = {
                PvotTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        ) {
            PvotDialogAction(
                label = stringResource(R.string.manage_rename_cancel),
                onClick = { renaming = null }
            )
            PvotDialogAction(
                label = stringResource(R.string.manage_rename_save),
                style = PvotDialogActionStyle.Primary,
                enabled = newName.isNotBlank(),
                onClick = {
                    scope.launch {
                        val renamed = repository.renameDocument(managed.document, newName.trim())
                        renaming = null
                        if (renamed) refreshToken++ else onUnsupported(renameFailedMessage)
                    }
                }
            )
        }
    }

    val visibleDocuments = documents
        .filter { it.document.name.contains(searchQuery, ignoreCase = true) }
        .sortedByDocument(sortOption) { it.document }

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
                PvotTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = stringResource(R.string.manage_search_placeholder),
                    leadingIcon = {
                        Icon(painterResource(R.drawable.ic_search), contentDescription = null)
                    },
                    trailingIcon = if (searchQuery.isNotEmpty()) {
                        {
                            PvotIconButton(
                                icon = painterResource(R.drawable.ic_x),
                                contentDescription = stringResource(R.string.manage_search_clear),
                                onClick = { searchQuery = "" },
                                size = 28.dp
                            )
                        }
                    } else {
                        null
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().pvotReveal(1)
                )
            }
            item {
                PvotSegmentedControl(
                    options = listOf(
                        stringResource(R.string.manage_sort_name),
                        stringResource(R.string.manage_sort_date),
                        stringResource(R.string.manage_sort_size)
                    ),
                    selectedIndex = sortOption.ordinal,
                    onSelect = { sortOption = DocumentSort.entries[it] },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = PvotSpacing.S)
                        .pvotReveal(2)
                )
            }
        }
        if (visibleDocuments.isNotEmpty()) {
            item {
                PvotListGroup(
                    modifier = Modifier.fillMaxWidth().pvotReveal(3),
                    title = stringResource(R.string.manage_section_documents)
                ) {
                    visibleDocuments.forEach { managed ->
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
                                    PvotIconButton(
                                        icon = painterResource(R.drawable.ic_more),
                                        contentDescription = stringResource(
                                            R.string.manage_document_actions,
                                            document.name
                                        ),
                                        onClick = { actionsFor = managed },
                                        size = 36.dp
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
                    text = stringResource(
                        if (documents.isEmpty()) {
                            R.string.manage_no_documents
                        } else {
                            R.string.manage_no_search_results
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().pvotReveal(3)
                )
            }
        }
    }
}
