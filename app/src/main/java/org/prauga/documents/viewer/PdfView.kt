package org.prauga.documents.viewer

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.fragment.compose.AndroidFragment
import androidx.fragment.compose.FragmentState
import androidx.pdf.PdfDocument
import androidx.pdf.viewer.fragment.PdfViewerFragment
import com.prauga.pvot.coreui.PvotErrorContent
import com.prauga.pvot.coreui.PvotLoadingContent
import com.prauga.pvot.coreui.UiState
import org.prauga.documents.R

/** [uri] opened and shown through `androidx.pdf`'s [PdfViewerFragment] — the library's older,
 * more-iterated View surface, used over the newer `PdfViewer` composable because this surface
 * actually renders selection drag handles, and brings find-in-document for free. Embedded via
 * [AndroidFragment], which is why [org.prauga.documents.viewer.DocumentViewerActivity] extends
 * `FragmentActivity` rather than `PvotBaseActivity`. */
@Composable
fun PdfView(uri: Uri, topPadding: Dp, modifier: Modifier = Modifier) {
    var retryToken by remember(uri) { mutableIntStateOf(0) }
    var state by remember(uri, retryToken) { mutableStateOf<UiState<Unit>>(UiState.Loading) }
    val readFailedMessage = stringResource(R.string.viewer_read_failed)
    // A fresh FragmentState per retry forces AndroidFragment to create a brand new fragment
    // instance — setting documentUri to the uri it already failed on is a no-op, per its own docs.
    val fragmentState = remember(uri, retryToken) { FragmentState() }

    Box(modifier = modifier) {
        AndroidFragment<PvotPdfViewerFragment>(
            fragmentState = fragmentState,
            modifier = Modifier.fillMaxSize().padding(top = topPadding)
        ) { fragment ->
            fragment.onDocumentLoaded = { state = UiState.Success(Unit) }
            fragment.onDocumentLoadFailed = { state = UiState.Error(readFailedMessage, it) }
            if (fragment.documentUri != uri) {
                fragment.documentUri = uri
            }
        }

        when (val current = state) {
            is UiState.Loading -> Box(
                modifier = Modifier.fillMaxSize().padding(top = topPadding),
                contentAlignment = Alignment.Center
            ) { PvotLoadingContent() }

            is UiState.Error -> Box(
                modifier = Modifier.fillMaxSize().padding(top = topPadding),
                contentAlignment = Alignment.Center
            ) {
                PvotErrorContent(
                    title = stringResource(R.string.viewer_read_failed),
                    message = current.cause?.message.orEmpty(),
                    onRetry = { retryToken++ }
                )
            }

            is UiState.Success -> Unit
        }
    }
}

/** A plain [PdfViewerFragment] subclass that forwards its load callbacks out to Compose —
 * [PdfViewerFragment] only exposes them as protected overrides, there's no listener API. */
class PvotPdfViewerFragment : PdfViewerFragment() {
    var onDocumentLoaded: (() -> Unit)? = null
    var onDocumentLoadFailed: ((Throwable) -> Unit)? = null

    override fun onLoadDocumentSuccess(document: PdfDocument) {
        onDocumentLoaded?.invoke()
    }

    override fun onLoadDocumentError(error: Throwable) {
        onDocumentLoadFailed?.invoke(error)
    }
}
