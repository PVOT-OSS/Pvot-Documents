package org.prauga.documents.models

import android.net.Uri

/** A document found in one of the folders the user added, as listed by the Storage Access
 * Framework. Nothing here is read from the file's content; opening and parsing it is phase 2. */
data class DocumentEntry(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val lastModified: Long
)

/** A folder the user added through the system picker. */
data class FolderEntry(
    val treeUri: Uri,
    val displayName: String
)

/** A document paired with the folder it was found in, so deleting or restoring it knows where to
 * write. */
data class ManagedDocument(
    val document: DocumentEntry,
    val folderTreeUri: Uri
)

/** A document moved to Recently deleted: its own copy in Documents' trash storage, plus where to
 * put it back. Restoring is only possible while [folderTreeUri] is still a folder the user has
 * added; see [org.prauga.documents.data.DocumentsRepository.restoreFromTrash]. */
data class TrashedDocument(
    val document: DocumentEntry,
    val folderTreeUri: Uri,
    val deletedAt: Long
)
