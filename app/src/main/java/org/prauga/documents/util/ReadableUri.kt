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
 */
fun readableUri(context: Context, document: DocumentEntry): Uri =
    if (document.uri.scheme == "file") {
        FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            File(requireNotNull(document.uri.path))
        )
    } else {
        document.uri
    }
