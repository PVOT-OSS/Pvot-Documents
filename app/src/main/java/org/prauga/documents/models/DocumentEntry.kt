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
