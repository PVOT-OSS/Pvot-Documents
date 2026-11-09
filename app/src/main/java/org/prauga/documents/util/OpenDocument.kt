package org.prauga.documents.util

import android.content.Context
import org.prauga.documents.AppPreferences
import org.prauga.documents.models.DocumentEntry
import org.prauga.documents.viewer.DocumentViewerActivity

/** What [openDocument] did, for the caller to report. */
enum class OpenResult { Opened, Unsupported, Unreadable }

/**
 * Opens [document] in [DocumentViewerActivity] and records it at the top of
 * [AppPreferences.recentDocuments], wherever it was opened from. A format without a viewer, or a
 * document the app can't read, is neither opened nor recorded.
 */
fun openDocument(
    context: Context,
    preferences: AppPreferences,
    document: DocumentEntry
): OpenResult {
    val format = viewerFormat(document.name, document.mimeType) ?: return OpenResult.Unsupported
    val uri = readableUri(context, document) ?: return OpenResult.Unreadable
    preferences.addRecentDocument(document)
    DocumentViewerActivity.start(context, uri, document.name, format)
    return OpenResult.Opened
}
