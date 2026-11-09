package org.prauga.documents.ui.screens

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.prauga.pvot.designsystem.components.PvotDialog
import com.prauga.pvot.designsystem.components.PvotDialogAction
import com.prauga.pvot.designsystem.components.PvotDialogActionStyle
import org.prauga.documents.AppPreferences
import org.prauga.documents.R
import org.prauga.documents.data.DocumentsRepository
import org.prauga.documents.util.openDocument
import org.prauga.documents.util.viewerFormat
import org.prauga.documents.viewer.DocumentViewerActivity

/**
 * Handles a document the app was opened or shared with, from [MainActivity][org.prauga.documents.MainActivity].
 * The first time, asks once whether to copy it into Documents' own storage; after that the choice
 * is remembered (and changeable from Settings). Either way the document ends up in
 * [AppPreferences.recentDocuments], so it shows up on the Recent tab, and opens in the viewer. A
 * document the app can't read isn't recorded.
 */
@Composable
fun IncomingDocumentHandler(uri: Uri, preferences: AppPreferences, onHandled: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { DocumentsRepository(context) }
    val defaultName = stringResource(R.string.incoming_default_name)
    var name by remember(uri) { mutableStateOf<String?>(null) }

    LaunchedEffect(uri) {
        name = repository.documentInfo(uri)?.name
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: defaultName
    }

    val resolvedName = name ?: return
    val autosave = preferences.autosaveOpenedDocuments

    if (autosave == null) {
        AutosavePromptDialog(
            documentName = resolvedName,
            onChoose = preferences::setAutosaveOpenedDocuments
        )
        return
    }

    LaunchedEffect(uri, autosave) {
        val entry = (if (autosave) repository.copyToAppStorage(uri, resolvedName) else null)
            ?: repository.documentInfo(uri)?.takeIf { repository.canRead(uri) }
        if (entry != null) {
            openDocument(context, preferences, entry)
        } else {
            // Nothing to record: a path Documents has no permission for, or a grant that has
            // already expired. The viewer still opens, to say it couldn't read the document,
            // rather than the app doing nothing at all.
            viewerFormat(resolvedName, context.contentResolver.getType(uri).orEmpty())?.let { format ->
                DocumentViewerActivity.start(context, uri, resolvedName, format)
            }
        }
        onHandled()
    }
}

@Composable
private fun AutosavePromptDialog(documentName: String, onChoose: (Boolean) -> Unit) {
    PvotDialog(
        visible = true,
        // The choice is required to proceed; dismissing without one would leave the document
        // half-handled. A default lives in Settings for anyone who skips this by force-closing.
        onDismissRequest = {},
        dismissOnOutsideTap = false,
        title = stringResource(R.string.incoming_autosave_title),
        message = stringResource(R.string.incoming_autosave_message, documentName)
    ) {
        PvotDialogAction(
            label = stringResource(R.string.incoming_autosave_save),
            onClick = { onChoose(true) },
            style = PvotDialogActionStyle.Primary
        )
        PvotDialogAction(
            label = stringResource(R.string.incoming_autosave_dont_save),
            onClick = { onChoose(false) }
        )
    }
}
