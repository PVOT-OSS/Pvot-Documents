package org.prauga.documents.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prauga.pvot.coreui.PvotEmptyContent
import com.prauga.pvot.designsystem.components.PvotBottomSheet
import com.prauga.pvot.designsystem.components.PvotCard
import com.prauga.pvot.designsystem.components.PvotCardDefaults
import com.prauga.pvot.designsystem.components.PvotDialog
import com.prauga.pvot.designsystem.components.PvotDialogAction
import com.prauga.pvot.designsystem.components.PvotDialogActionStyle
import com.prauga.pvot.designsystem.components.PvotScreen
import com.prauga.pvot.designsystem.components.PvotScreenDefaults
import com.prauga.pvot.designsystem.components.PvotSegmentedControl
import com.prauga.pvot.designsystem.components.PvotSheetItem
import com.prauga.pvot.designsystem.components.button.PvotFab
import com.prauga.pvot.designsystem.components.button.PvotIconButton
import com.prauga.pvot.designsystem.components.list.PvotListGroup
import com.prauga.pvot.designsystem.components.list.PvotListRow
import com.prauga.pvot.designsystem.components.textfield.PvotTextField
import com.prauga.pvot.designsystem.motion.pvotReveal
import com.prauga.pvot.designsystem.navigation.LocalPvotNavBarSizes
import com.prauga.pvot.designsystem.theme.PvotSpacing
import kotlinx.coroutines.launch
import org.prauga.documents.AppPreferences
import org.prauga.documents.R
import org.prauga.documents.data.DocumentsRepository
import org.prauga.documents.models.DocumentEntry
import org.prauga.documents.models.FolderEntry
import org.prauga.documents.models.ManagedDocument
import org.prauga.documents.models.TrashedDocument
import org.prauga.documents.util.DocumentSort
import org.prauga.documents.util.documentKind
import org.prauga.documents.util.formatFileSize
import org.prauga.documents.util.readableUri
import org.prauga.documents.util.shareDocument
import org.prauga.documents.util.shareDocuments
import org.prauga.documents.util.sortedByDocument
import org.prauga.documents.util.viewerFormat
import org.prauga.documents.viewer.DocumentViewerActivity

/**
 * Folders the user added, and the documents found directly inside them. Tapping one opens it if
 * its format has a viewer yet; [onUnsupported] reports when it doesn't.
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
    var selectedUris by remember { mutableStateOf<Set<Uri>>(emptySet()) }
    var showNewSheet by remember { mutableStateOf(false) }
    var creatingType by remember { mutableStateOf<NewFileType?>(null) }
    val openUnsupportedMessage = stringResource(R.string.manage_open_unsupported)
    val movedToTrashMessage = stringResource(R.string.manage_moved_to_trash)
    val deleteFailedMessage = stringResource(R.string.manage_delete_failed)
    val renameFailedMessage = stringResource(R.string.manage_rename_failed)
    val scanUnsupportedMessage = stringResource(R.string.manage_scan_unsupported)
    val createdMessage = stringResource(R.string.manage_new_created)
    val createFailedMessage = stringResource(R.string.manage_new_create_failed)

    fun openDocument(document: DocumentEntry) {
        val format = viewerFormat(document.name, document.mimeType)
        if (format != null) {
            DocumentViewerActivity.start(context, readableUri(context, document), document.name, format)
        } else {
            onUnsupported(openUnsupportedMessage)
        }
    }

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

    fun toggleSelection(uri: Uri) {
        selectedUris = if (uri in selectedUris) selectedUris - uri else selectedUris + uri
    }

    fun deleteSelected(selected: List<ManagedDocument>) {
        scope.launch {
            var failures = 0
            selected.forEach { managed ->
                val trashed = repository.moveToTrash(managed.document)
                if (trashed != null) {
                    preferences.addTrashedDocument(
                        TrashedDocument(
                            document = trashed,
                            folderTreeUri = managed.folderTreeUri,
                            deletedAt = System.currentTimeMillis()
                        )
                    )
                } else {
                    failures++
                }
            }
            selectedUris = emptySet()
            refreshToken++
            onUnsupported(if (failures == 0) movedToTrashMessage else deleteFailedMessage)
        }
    }

    fun createDocument(type: NewFileType, name: String) {
        val folderUri = folders.first().treeUri
        scope.launch {
            val created = repository.createTextDocument(
                folderUri,
                name.ifBlank { "Untitled" },
                type.extension,
                type.mimeType
            )
            creatingType = null
            if (created != null) {
                refreshToken++
                onUnsupported(createdMessage)
            } else {
                onUnsupported(createFailedMessage)
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
            onSelect = { selectedUris = setOf(managed.document.uri) },
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

    if (showNewSheet) {
        NewDocumentSheet(
            onDismiss = { showNewSheet = false },
            onPickType = { type -> creatingType = type },
            onScan = { onUnsupported(scanUnsupportedMessage) }
        )
    }

    creatingType?.let { type ->
        var name by remember(type) { mutableStateOf("") }
        PvotDialog(
            visible = true,
            onDismissRequest = { creatingType = null },
            title = stringResource(type.titleRes),
            content = {
                PvotTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = stringResource(R.string.manage_new_filename_placeholder),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        ) {
            PvotDialogAction(
                label = stringResource(R.string.manage_new_cancel),
                onClick = { creatingType = null }
            )
            PvotDialogAction(
                label = stringResource(R.string.manage_new_create),
                style = PvotDialogActionStyle.Primary,
                onClick = { createDocument(type, name.trim()) }
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

    Box(modifier = modifier) {
    PvotScreen(
        modifier = Modifier.fillMaxSize(),
        state = state,
        contentPadding = PvotScreenDefaults.contentPadding(top = topPadding)
    ) {
        if (selectedUris.isNotEmpty()) {
            item {
                SelectionToolbar(
                    count = selectedUris.size,
                    onSelectAll = {
                        selectedUris = visibleDocuments.map { it.document.uri }.toSet()
                    },
                    onShare = {
                        shareDocuments(
                            context,
                            visibleDocuments
                                .filter { it.document.uri in selectedUris }
                                .map { it.document }
                        )
                    },
                    onDelete = {
                        deleteSelected(visibleDocuments.filter { it.document.uri in selectedUris })
                    },
                    onCancel = { selectedUris = emptySet() },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
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
            if (preferences.gridView) {
                item {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().pvotReveal(3),
                        horizontalArrangement = Arrangement.spacedBy(PvotSpacing.M),
                        verticalArrangement = Arrangement.spacedBy(PvotSpacing.M)
                    ) {
                        visibleDocuments.forEach { managed ->
                            val selected = managed.document.uri in selectedUris
                            DocumentGridCard(
                                managed = managed,
                                selected = selected,
                                inSelectionMode = selectedUris.isNotEmpty(),
                                onClick = {
                                    if (selectedUris.isNotEmpty()) {
                                        toggleSelection(managed.document.uri)
                                    } else {
                                        openDocument(managed.document)
                                    }
                                },
                                onMore = { actionsFor = managed }
                            )
                        }
                    }
                }
            } else {
                item {
                    PvotListGroup(
                        modifier = Modifier.fillMaxWidth().pvotReveal(3),
                        title = stringResource(R.string.manage_section_documents)
                    ) {
                        visibleDocuments.forEach { managed ->
                            item {
                                val document = managed.document
                                val selected = document.uri in selectedUris
                                PvotListRow(
                                    title = document.name,
                                    supportingText = formatFileSize(document.sizeBytes),
                                    selected = selected,
                                    leading = {
                                        Icon(
                                            painterResource(
                                                if (selected) {
                                                    R.drawable.ic_check_circle_filled
                                                } else {
                                                    R.drawable.ic_file_text
                                                }
                                            ),
                                            contentDescription = null,
                                            tint = if (selected) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                documentKind(document.name, document.mimeType).color()
                                            }
                                        )
                                    },
                                    trailing = if (selectedUris.isEmpty()) {
                                        {
                                            PvotIconButton(
                                                icon = painterResource(R.drawable.ic_more),
                                                contentDescription = stringResource(
                                                    R.string.manage_document_actions,
                                                    document.name
                                                ),
                                                onClick = { actionsFor = managed },
                                                size = 36.dp
                                            )
                                        }
                                    } else {
                                        null
                                    },
                                    onClick = {
                                        if (selectedUris.isNotEmpty()) {
                                            toggleSelection(document.uri)
                                        } else {
                                            openDocument(document)
                                        }
                                    },
                                    onLongClick = { toggleSelection(document.uri) }
                                )
                            }
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
        PvotFab(
            icon = painterResource(R.drawable.ic_plus),
            onClick = { showNewSheet = true },
            contentDescription = stringResource(R.string.manage_new_document),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(
                    end = PvotSpacing.L,
                    bottom = PvotSpacing.L + LocalPvotNavBarSizes.current.barHeight
                )
        )
    }
}

/**
 * A plain-text format the "+" can create directly — a real DOCX/XLSX/PPTX writer is Phase 3/4
 * work, so the choices here are honest about what Phase 1 can actually produce empty.
 */
private data class NewFileType(val extension: String, val mimeType: String, val titleRes: Int)

private val NewFileTypes = listOf(
    NewFileType("txt", "text/plain", R.string.manage_new_text),
    NewFileType("md", "text/markdown", R.string.manage_new_markdown),
    NewFileType("csv", "text/csv", R.string.manage_new_csv)
)

/** The "+" menu: a plain-text format to create now, or scanning, which isn't built until Phase 5. */
@Composable
private fun NewDocumentSheet(
    onDismiss: () -> Unit,
    onPickType: (NewFileType) -> Unit,
    onScan: () -> Unit
) {
    PvotBottomSheet(
        visible = true,
        onDismissRequest = onDismiss,
        title = stringResource(R.string.manage_new_document)
    ) {
        NewFileTypes.forEach { type ->
            PvotSheetItem(
                title = stringResource(type.titleRes),
                icon = {
                    Icon(
                        painterResource(R.drawable.ic_file_text),
                        contentDescription = null
                    )
                },
                onClick = { onDismiss(); onPickType(type) }
            )
        }
        PvotSheetItem(
            title = stringResource(R.string.manage_new_scan),
            icon = { Icon(painterResource(R.drawable.ic_camera), contentDescription = null) },
            onClick = { onDismiss(); onScan() }
        )
    }
}

/** A document in Manage's grid view: icon, name and size, with the same overflow menu as the
 * list row, just smaller — grid mode trades the size reading room for an at-a-glance layout, it
 * doesn't drop an action. In selection mode the corner button becomes a selected/unselected mark
 * instead, matching the list row's own swap to [com.prauga.pvot.designsystem.components.list.PvotListRow]'s
 * tinted [selected] state. */
@Composable
private fun DocumentGridCard(
    managed: ManagedDocument,
    selected: Boolean,
    inSelectionMode: Boolean,
    onClick: () -> Unit,
    onMore: () -> Unit
) {
    val document = managed.document
    PvotCard(
        onClick = onClick,
        modifier = Modifier.width(108.dp),
        colors = if (selected) {
            PvotCardDefaults.colors(
                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
            )
        } else {
            PvotCardDefaults.colors()
        }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(PvotSpacing.S),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (inSelectionMode) {
                    Icon(
                        painterResource(
                            if (selected) R.drawable.ic_check_circle_filled else R.drawable.ic_checkbox
                        ),
                        contentDescription = null,
                        tint = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(22.dp)
                    )
                } else {
                    PvotIconButton(
                        icon = painterResource(R.drawable.ic_more),
                        contentDescription = stringResource(
                            R.string.manage_document_actions,
                            document.name
                        ),
                        onClick = onMore,
                        size = 28.dp
                    )
                }
            }
            Icon(
                painterResource(R.drawable.ic_file_text),
                contentDescription = null,
                tint = documentKind(document.name, document.mimeType).color(),
                modifier = Modifier.size(36.dp)
            )
            Text(
                text = document.name,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = PvotSpacing.XS)
            )
            Text(
                text = formatFileSize(document.sizeBytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** The bar that replaces the normal screen top while documents are selected: a count and the
 * actions that work on all of them at once. */
@Composable
private fun SelectionToolbar(
    count: Int,
    onSelectAll: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    PvotListGroup(modifier = modifier) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(PvotSpacing.L),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PvotSpacing.XS)
            ) {
                Text(
                    text = pluralStringResource(R.plurals.manage_selected_count, count, count),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                PvotIconButton(
                    icon = painterResource(R.drawable.ic_checkbox),
                    contentDescription = stringResource(R.string.manage_select_all),
                    onClick = onSelectAll,
                    size = 36.dp
                )
                PvotIconButton(
                    icon = painterResource(R.drawable.ic_share),
                    contentDescription = stringResource(R.string.manage_share_selected),
                    onClick = onShare,
                    size = 36.dp
                )
                PvotIconButton(
                    icon = painterResource(R.drawable.ic_trash),
                    contentDescription = stringResource(R.string.manage_delete_selected),
                    onClick = onDelete,
                    size = 36.dp
                )
                PvotIconButton(
                    icon = painterResource(R.drawable.ic_x),
                    contentDescription = stringResource(R.string.manage_cancel_selection),
                    onClick = onCancel,
                    size = 36.dp
                )
            }
        }
    }
}
