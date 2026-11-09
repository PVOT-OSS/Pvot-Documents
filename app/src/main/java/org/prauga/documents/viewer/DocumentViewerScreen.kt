package org.prauga.documents.viewer

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import com.prauga.pvot.coreui.PvotErrorContent
import com.prauga.pvot.coreui.PvotLoadingContent
import com.prauga.pvot.coreui.UiState
import com.prauga.pvot.designsystem.components.PvotScreenScaffold
import com.prauga.pvot.designsystem.navigation.PvotTopBar
import com.prauga.pvot.designsystem.theme.PvotSpacing
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.prauga.documents.R
import org.prauga.documents.data.DocumentsRepository
import org.prauga.documents.util.ViewerFormat
import org.prauga.documents.util.parseCsv

/** Reads [uri] as [format] and shows it: plain text, code and JSON as scrollable text, CSV as a
 * table, Markdown and HTML as rendered styled text, PDF page by page, DOCX as styled paragraphs.
 * [name] is just the title, read once from the caller rather than re-resolved here. PDF and DOCX
 * read [uri] directly — a file descriptor and a zip, respectively — rather than as decoded text,
 * so each gets its own content branch below, skipping the text-loading state the other formats
 * share. */
@Composable
fun DocumentViewerScreen(
    uri: Uri,
    name: String,
    format: ViewerFormat,
    onBack: () -> Unit
) {
    PvotScreenScaffold(
        topBar = { PvotTopBar(title = name, onBack = onBack, compact = true) }
    ) { padding ->
        if (format == ViewerFormat.Pdf) {
            PdfView(
                uri = uri,
                topPadding = padding.calculateTopPadding(),
                modifier = Modifier.fillMaxSize()
            )
        } else if (format == ViewerFormat.Docx) {
            DocxView(
                uri = uri,
                topPadding = padding.calculateTopPadding(),
                modifier = Modifier.fillMaxSize()
            )
        } else {
            TextDocumentContent(uri = uri, format = format, topPadding = padding.calculateTopPadding())
        }
    }
}

@Composable
private fun TextDocumentContent(uri: Uri, format: ViewerFormat, topPadding: Dp) {
    val context = LocalContext.current
    val repository = remember { DocumentsRepository(context) }
    val scope = rememberCoroutineScope()
    var state by remember(uri) { mutableStateOf<UiState<String>>(UiState.Loading) }
    val readFailedMessage = stringResource(R.string.viewer_read_failed)

    suspend fun load() {
        state = UiState.Loading
        state = runCatching { repository.readText(uri) }
            .map { text -> if (format == ViewerFormat.Json) prettyPrintJson(text) else text }
            .fold(
                onSuccess = { UiState.Success(it) },
                onFailure = { UiState.Error(readFailedMessage, it) }
            )
    }

    LaunchedEffect(uri) { load() }

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
                onRetry = { scope.launch { load() } }
            )
        }

        is UiState.Success -> if (format == ViewerFormat.Csv) {
            CsvTable(
                rows = remember(current.data) { parseCsv(current.data) },
                topPadding = topPadding,
                modifier = Modifier.fillMaxSize()
            )
        } else if (format == ViewerFormat.Markdown) {
            MarkdownView(
                text = current.data,
                topPadding = topPadding,
                modifier = Modifier.fillMaxSize()
            )
        } else if (format == ViewerFormat.Html) {
            HtmlView(
                html = current.data,
                topPadding = topPadding,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            SelectionContainer {
                Text(
                    text = current.data,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontFamily = if (format == ViewerFormat.Json || format == ViewerFormat.Code) {
                        FontFamily.Monospace
                    } else {
                        FontFamily.Default
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = topPadding)
                        .verticalScroll(rememberScrollState())
                        .padding(PvotSpacing.L)
                )
            }
        }
    }
}

/** [text] reformatted with 2-space indentation if it parses as JSON, unchanged otherwise — a
 * malformed file should still open, just without the pretty-printing. */
private fun prettyPrintJson(text: String): String {
    val trimmed = text.trim()
    return runCatching {
        when {
            trimmed.startsWith("{") -> JSONObject(trimmed).toString(2)
            trimmed.startsWith("[") -> JSONArray(trimmed).toString(2)
            else -> text
        }
    }.getOrDefault(text)
}
