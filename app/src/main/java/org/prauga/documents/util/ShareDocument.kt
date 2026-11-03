package org.prauga.documents.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import org.prauga.documents.BuildConfig
import org.prauga.documents.models.DocumentEntry
import java.io.File

/**
 * Opens the system share sheet for [document]. A document from an added folder is already a
 * `content://` URI Documents holds a grant on, so it can be shared as-is; an autosaved copy is a
 * plain `file://` URI, which [FileProvider] turns into a `content://` one scoped to just that file.
 */
fun shareDocument(context: Context, document: DocumentEntry) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = document.mimeType
        putExtra(Intent.EXTRA_STREAM, shareableUri(context, document))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, document.name))
}

/** [shareDocument], for several documents at once — one share sheet, one chooser. */
fun shareDocuments(context: Context, documents: List<DocumentEntry>) {
    if (documents.isEmpty()) return
    if (documents.size == 1) return shareDocument(context, documents.single())
    val uris = ArrayList(documents.map { shareableUri(context, it) })
    // A mixed selection has no single mime type; "*/*" lets any app that takes multiple files
    // offer itself, rather than narrowing to the first document's type.
    val commonType = documents.map { it.mimeType }.distinct().singleOrNull() ?: "*/*"
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = commonType
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, null))
}

private fun shareableUri(context: Context, document: DocumentEntry): Uri =
    if (document.uri.scheme == "file") {
        FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            File(requireNotNull(document.uri.path))
        )
    } else {
        document.uri
    }
