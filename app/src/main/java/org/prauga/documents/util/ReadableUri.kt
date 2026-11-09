package org.prauga.documents.util

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import org.prauga.documents.BuildConfig
import org.prauga.documents.models.DocumentEntry
import java.io.File

/**
 * [document]'s URI, safe to hand to another component (an intent, a viewer). A document from an
 * added folder is already a `content://` URI Documents holds a grant on; an autosaved copy is a
 * plain `file://` one, which isn't safe to put in an Intent — [FileProvider] turns it into a
 * `content://` URI scoped to just that file instead.
 *
 * Null for a `file://` URI outside the app's own storage, such as a path another app passed
 * through "Open with": Documents has no storage permission, so it can neither read nor share it.
 */
fun readableUri(context: Context, document: DocumentEntry): Uri? {
    if (document.uri.scheme != "file") return document.uri
    val file = File(document.uri.path ?: return null)
    if (!isOwnFile(context, file)) return null
    return runCatching {
        FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
    }.getOrNull()
}

/** Whether [file] is inside the app's own `filesDir`, where its copies of documents live. */
fun isOwnFile(context: Context, file: File): Boolean {
    val root = context.filesDir.canonicalPath + File.separator
    return runCatching { file.canonicalPath.startsWith(root) }.getOrDefault(false)
}
