package org.prauga.documents.util

import android.content.Context
import android.content.Intent
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
    val shareableUri = if (document.uri.scheme == "file") {
        FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            File(requireNotNull(document.uri.path))
        )
    } else {
        document.uri
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = document.mimeType
        putExtra(Intent.EXTRA_STREAM, shareableUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, document.name))
}
