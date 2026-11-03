package org.prauga.documents.viewer

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.pdf.ExperimentalPdfApi
import androidx.pdf.PdfDocument
import androidx.pdf.SandboxedPdfLoader
import androidx.pdf.compose.PdfViewer
import androidx.pdf.compose.rememberPdfViewerState
import com.prauga.pvot.coreui.PvotErrorContent
import com.prauga.pvot.coreui.PvotLoadingContent
import com.prauga.pvot.coreui.UiState
import kotlinx.coroutines.Dispatchers
import org.prauga.documents.R

/** [uri] opened and shown through `androidx.pdf` — Google's own PDF engine, which gives pinch-zoom,
 * real text selection and copy, and tappable hyperlinks, instead of the hand-rolled rasterized
 * version this screen used before the library covered the same ground. */
@OptIn(ExperimentalPdfApi::class)
@Composable
fun PdfView(uri: Uri, topPadding: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val loader = remember { SandboxedPdfLoader(context, Dispatchers.IO) }
    var state by remember(uri) { mutableStateOf<UiState<PdfDocument>>(UiState.Loading) }
    var retryToken by remember(uri) { mutableIntStateOf(0) }
    val readFailedMessage = stringResource(R.string.viewer_read_failed)

    LaunchedEffect(uri, retryToken) {
        state = UiState.Loading
        state = runCatching { loader.openDocument(uri) }
            .fold(onSuccess = { UiState.Success(it) }, onFailure = { UiState.Error(readFailedMessage, it) })
    }

    DisposableEffect(uri) {
        onDispose { (state as? UiState.Success)?.data?.close() }
    }

    when (val current = state) {
        is UiState.Loading -> Box(
            modifier = modifier,
            contentAlignment = Alignment.Center
        ) { PvotLoadingContent() }

        is UiState.Error -> Box(
            modifier = modifier,
            contentAlignment = Alignment.Center
        ) {
            PvotErrorContent(
                title = stringResource(R.string.viewer_read_failed),
                message = current.cause?.message.orEmpty(),
                onRetry = { retryToken++ }
            )
        }

        is UiState.Success -> PdfViewer(
            pdfDocument = current.data,
            state = rememberPdfViewerState(),
            modifier = modifier,
            contentPadding = PaddingValues(top = topPadding)
        )
    }
}
