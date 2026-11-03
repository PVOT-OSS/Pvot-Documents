package org.prauga.documents.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.prauga.documents.models.DocumentEntry
import org.prauga.documents.models.FolderEntry
import java.io.File

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

    /**
     * [uri]'s content, decoded as UTF-8 text. Throws if the document can't be read or isn't
     * actually text — the viewer turns that into [com.prauga.pvot.coreui.UiState.Error].
     */
    suspend fun readText(uri: Uri): String = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openInputStream(uri)
            ?: error("This document can't be opened right now.")
        stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /**
     * [uri] as a single document (not a tree), such as one received through "Open with" or
     * "Share". Null if it no longer resolves — a one-time grant from the sending app that has
     * since expired, for example.
     */
    suspend fun documentInfo(uri: Uri): DocumentEntry? = withContext(Dispatchers.IO) {
        val doc = runCatching { DocumentFile.fromSingleUri(context, uri) }.getOrNull()
            ?: return@withContext null
        if (!doc.exists()) return@withContext null
        DocumentEntry(
            uri = uri,
            name = doc.name ?: uri.lastPathSegment.orEmpty(),
            mimeType = doc.type ?: "application/octet-stream",
            sizeBytes = doc.length(),
            lastModified = doc.lastModified()
        )
    }

    /**
     * Copies [uri] into the app's own storage, under `filesDir/opened/`, so it survives after a
     * transient grant from the sending app expires. Returns a [DocumentEntry] pointing at the
     * copy, named [displayName].
     */
    suspend fun copyToAppStorage(uri: Uri, displayName: String): DocumentEntry? =
        copyIntoOwnStorage(uri, displayName, OpenedDirName)

    /**
     * Moves [document] out of its folder and into `filesDir/trash/`: copies it there, then
     * deletes the original. Null if either step fails, in which case nothing changed — a partial
     * copy is removed, and a copy whose original couldn't be deleted is rolled back rather than
     * left as a duplicate.
     */
    suspend fun moveToTrash(document: DocumentEntry): DocumentEntry? {
        val copy = copyIntoOwnStorage(document.uri, document.name, TrashDirName) ?: return null
        val originalDeleted = withContext(Dispatchers.IO) {
            runCatching { DocumentFile.fromSingleUri(context, document.uri)?.delete() }
                .getOrDefault(false) == true
        }
        if (!originalDeleted) {
            withContext(Dispatchers.IO) { deleteOwnCopy(copy.uri) }
            return null
        }
        return copy
    }

    /**
     * Writes [trashed]'s copy back into [folderTreeUri] as a new document, then removes it from
     * trash. False if the folder no longer resolves (removed, or access revoked outside the app)
     * or the write fails; the trash copy is left in place either way, so nothing is lost.
     */
    suspend fun restoreFromTrash(trashed: DocumentEntry, folderTreeUri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            val tree = DocumentFile.fromTreeUri(context, folderTreeUri) ?: return@withContext false
            val source = File(requireNotNull(trashed.uri.path))
            if (!source.exists()) return@withContext false
            val restored = tree.createFile(trashed.mimeType, trashed.name)
            val written = restored?.let {
                runCatching {
                    context.contentResolver.openOutputStream(it.uri)?.use { output ->
                        source.inputStream().use { input -> input.copyTo(output) }
                    } != null
                }.getOrDefault(false)
            } ?: false
            if (written) {
                deleteOwnCopy(trashed.uri)
            } else {
                restored?.delete()
            }
            written
        }

    /** Deletes [trashed]'s copy for good. */
    suspend fun deleteForever(trashed: DocumentEntry) =
        withContext(Dispatchers.IO) { deleteOwnCopy(trashed.uri) }

    /**
     * Renames [document] to [newName]. Some providers change a document's URI on rename rather
     * than keeping it, so this reports success only — the caller re-lists its folder afterward
     * instead of trying to track the new URI itself.
     */
    suspend fun renameDocument(document: DocumentEntry, newName: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                DocumentsContract.renameDocument(
                    context.contentResolver,
                    document.uri,
                    newName
                ) != null
            }.getOrDefault(false)
        }

    /**
     * Creates a new, empty document named [name] (with [extension] appended if [name] doesn't
     * already end with it) directly inside [folderTreeUri]. Only plain-text formats — what Phase 1
     * can honestly create without a real DOCX/XLSX writer, which is Phase 3/4 work.
     */
    suspend fun createTextDocument(
        folderTreeUri: Uri,
        name: String,
        extension: String,
        mimeType: String
    ): DocumentEntry? = withContext(Dispatchers.IO) {
        val tree = DocumentFile.fromTreeUri(context, folderTreeUri) ?: return@withContext null
        val fileName = if (name.endsWith(".$extension", ignoreCase = true)) {
            name
        } else {
            "$name.$extension"
        }
        val created = tree.createFile(mimeType, fileName) ?: return@withContext null
        val written = runCatching {
            context.contentResolver.openOutputStream(created.uri)?.use { it.flush() } != null
        }.getOrDefault(false)
        if (!written) {
            created.delete()
            return@withContext null
        }
        DocumentEntry(
            uri = created.uri,
            name = created.name ?: fileName,
            mimeType = mimeType,
            sizeBytes = created.length(),
            lastModified = created.lastModified()
        )
    }

    /** Copies [uri] into its own timestamped subfolder under `filesDir/$baseDirName/`, so the file
     * on disk keeps [displayName] exactly rather than a deduplicating prefix — the name shared
     * back out through `FileProvider` reads from the file, not from [DocumentEntry.name]. */
    private suspend fun copyIntoOwnStorage(
        uri: Uri,
        displayName: String,
        baseDirName: String
    ): DocumentEntry? = withContext(Dispatchers.IO) {
        val safeName = displayName.ifBlank { "document" }
        val copyDir = File(
            File(context.filesDir, baseDirName),
            System.currentTimeMillis().toString()
        ).apply { mkdirs() }
        val target = File(copyDir, safeName)
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } != null
        }.getOrDefault(false)
        if (!copied) return@withContext null
        DocumentEntry(
            uri = Uri.fromFile(target),
            name = displayName,
            mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream",
            sizeBytes = target.length(),
            lastModified = target.lastModified()
        )
    }

    /** Deletes a `file://` URI from [copyIntoOwnStorage], and its now-empty subfolder. */
    private fun deleteOwnCopy(uri: Uri) {
        val path = uri.path ?: return
        val file = File(path)
        file.delete()
        file.parentFile?.delete()
    }

    private companion object {
        const val OpenedDirName = "opened"
        const val TrashDirName = "trash"
    }
}
