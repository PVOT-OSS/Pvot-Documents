package org.prauga.documents.util

import android.content.Context
import android.content.Intent
import org.prauga.documents.models.DocumentEntry

/** Opens the system share sheet for [document]; nothing if the app can't read it. */
fun shareDocument(context: Context, document: DocumentEntry) {
    val uri = readableUri(context, document) ?: return
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = document.mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, document.name))
}

/** [shareDocument], for several documents at once — one share sheet, one chooser. */
fun shareDocuments(context: Context, documents: List<DocumentEntry>) {
    val readable = documents.mapNotNull { document -> readableUri(context, document)?.let { document to it } }
    if (readable.isEmpty()) return
    if (readable.size == 1) return shareDocument(context, readable.single().first)
    val uris = ArrayList(readable.map { it.second })
    // A mixed selection has no single mime type; "*/*" lets any app that takes multiple files
    // offer itself, rather than narrowing to the first document's type.
    val commonType = readable.map { it.first.mimeType }.distinct().singleOrNull() ?: "*/*"
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = commonType
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, null))
}
