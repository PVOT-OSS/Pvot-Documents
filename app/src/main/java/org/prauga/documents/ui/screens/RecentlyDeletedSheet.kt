package org.prauga.documents.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.prauga.pvot.designsystem.components.PvotBottomSheet
import com.prauga.pvot.designsystem.components.button.PvotIconButton
import com.prauga.pvot.designsystem.components.list.PvotListRow
import com.prauga.pvot.designsystem.theme.PvotSpacing
import kotlinx.coroutines.launch
import org.prauga.documents.AppPreferences
import org.prauga.documents.R
import org.prauga.documents.data.DocumentsRepository
import org.prauga.documents.models.TrashedDocument
import org.prauga.documents.util.documentKind
import org.prauga.documents.util.formatFileSize

/**
 * Documents moved out of their folder by the trash button in Manage. Restoring writes a copy back
 * into the original folder, which only works while that folder is still one the user has added;
 * deleting here is for good, with no further undo.
 *
 * @param onRestored Called after a successful restore, so Manage re-reads its folders
 * @param onMessage Shows a brief message, such as why a restore failed
 */
@Composable
fun RecentlyDeletedSheet(
    preferences: AppPreferences,
    onDismiss: () -> Unit,
    onRestored: () -> Unit,
    onMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val repository = remember { DocumentsRepository(context) }
    val scope = rememberCoroutineScope()
    val restoreFailedMessage = stringResource(R.string.trash_restore_failed)

    PvotBottomSheet(
        visible = true,
        onDismissRequest = onDismiss,
        title = stringResource(R.string.manage_recently_deleted_title)
    ) {
        if (preferences.trashedDocuments.isEmpty()) {
            Text(
                text = stringResource(R.string.trash_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(PvotSpacing.L)
            )
            return@PvotBottomSheet
        }
        preferences.trashedDocuments.forEach { trashed ->
            val document = trashed.document
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
                    Row(horizontalArrangement = Arrangement.spacedBy(PvotSpacing.XS)) {
                        PvotIconButton(
                            icon = painterResource(R.drawable.ic_restore),
                            contentDescription = stringResource(
                                R.string.trash_restore_document,
                                document.name
                            ),
                            onClick = {
                                scope.launch {
                                    val restored = repository.restoreFromTrash(
                                        document,
                                        trashed.folderTreeUri
                                    )
                                    if (restored) {
                                        preferences.removeTrashedDocument(trashed)
                                        onRestored()
                                    } else {
                                        onMessage(restoreFailedMessage)
                                    }
                                }
                            },
                            size = 36.dp
                        )
                        PvotIconButton(
                            icon = painterResource(R.drawable.ic_x),
                            contentDescription = stringResource(
                                R.string.trash_delete_forever,
                                document.name
                            ),
                            onClick = {
                                scope.launch {
                                    repository.deleteForever(document)
                                    preferences.removeTrashedDocument(trashed)
                                }
                            },
                            size = 36.dp
                        )
                    }
                }
            )
        }
    }
}
