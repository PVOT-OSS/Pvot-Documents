package org.prauga.documents.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.prauga.documents.models.DocumentEntry
import org.prauga.documents.models.FolderEntry

/**
 * Reads folders and documents through the Storage Access Framework. Every call is a live query
 * against the provider, not a cache: a tree the user already granted still needs its own read on
 * every call, which is why each one is a suspend function off the main thread.
 */
class DocumentsRepository(private val context: Context) {

    /** [treeUri] as a [FolderEntry], or null if it can no longer be opened (such as a removed SD
     * card or a folder the user revoked access to outside the app). */
    suspend fun folder(treeUri: Uri): FolderEntry? = withContext(Dispatchers.IO) {
        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext null
        if (!tree.exists()) return@withContext null
        FolderEntry(treeUri = treeUri, displayName = tree.name ?: treeUri.lastPathSegment.orEmpty())
    }

    /** The files directly inside [treeUri], not its subfolders; phase 1 is one level deep. */
    suspend fun documentsIn(treeUri: Uri): List<DocumentEntry> = withContext(Dispatchers.IO) {
        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()
        tree.listFiles()
            .filter { it.isFile }
            .map {
                DocumentEntry(
                    uri = it.uri,
                    name = it.name ?: it.uri.lastPathSegment.orEmpty(),
                    mimeType = it.type ?: "application/octet-stream",
                    sizeBytes = it.length(),
                    lastModified = it.lastModified()
                )
            }
    }
}
