package org.prauga.documents.data

import android.net.Uri
import android.provider.DocumentsContract

/**
 * The two folders onboarding offers a shortcut to, by their standard name on the primary storage
 * volume. There is no API to grant SAF access to a folder without the user confirming it in the
 * system picker; what this buys is pointing that picker straight at the folder, through
 * [DocumentsContract.EXTRA_INITIAL_URI], so granting it is one tap instead of a folder hunt.
 */
enum class PublicFolder(val folderName: String) {
    Documents("Documents"),
    Downloads("Download");

    /** The document ID the external storage provider uses for this folder on primary storage. */
    val documentId: String get() = "primary:$folderName"

    /** Passed as [DocumentsContract.EXTRA_INITIAL_URI] to open the picker inside this folder. */
    val initialUri: Uri
        get() = DocumentsContract.buildDocumentUri(ExternalStorageAuthority, documentId)
}

private const val ExternalStorageAuthority = "com.android.externalstorage.documents"

/**
 * Whether [folder] is already among [folderUris]: a persisted tree URI granted through this
 * folder's own picker call ends in its [PublicFolder.documentId], URL-encoded.
 */
fun isPublicFolderAdded(folderUris: Set<String>, folder: PublicFolder): Boolean {
    val encoded = Uri.encode(folder.documentId)
    return folderUris.any { it.endsWith(encoded) || it.endsWith(folder.documentId) }
}
