package org.prauga.documents.viewer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.res.painterResource
import com.prauga.pvot.designsystem.motion.PvotDurations
import com.prauga.pvot.designsystem.motion.PvotMotion
import com.prauga.pvot.designsystem.navigation.PvotTopBarAction
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import com.prauga.pvot.coreui.PvotErrorContent
import com.prauga.pvot.coreui.PvotLoadingContent
import com.prauga.pvot.coreui.UiState
import androidx.compose.material3.SnackbarHostState
import com.prauga.pvot.designsystem.components.PvotScreenScaffold
import com.prauga.pvot.designsystem.components.PvotSnackbarHost
import com.prauga.pvot.designsystem.navigation.PvotTopBar
import com.prauga.pvot.designsystem.theme.PvotSpacing
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.prauga.documents.AppPreferences
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
    val context = LocalContext.current
    val preferences = remember { AppPreferences(context) }
    val pdfSearch = remember { PdfSearchState() }
    val pdfNavigation = remember { PdfNavigationState() }
    val pdfSelection = remember { PdfSelectionState() }
    val snackbar = remember { SnackbarHostState() }
    // Collected rather than keyed on the notice: clearing it would change the key and cancel the
    // effect before the snackbar had shown.
    LaunchedEffect(pdfSelection) {
        snapshotFlow { pdfSelection.notice }.filterNotNull().collect { notice ->
            pdfSelection.notice = null
            snackbar.showSnackbar(notice)
        }
    }
    val readingKey = remember(uri, name) { readingKey(context, uri, name) }
    val pdfReading = remember(readingKey, preferences.darkPdfPages) {
        PdfReadingSettings(
            initialPosition = preferences.readingPosition(readingKey),
            onPositionChange = { preferences.saveReadingPosition(readingKey, it) },
            pageFilter = if (preferences.darkPdfPages) DarkPageFilter else null,
        )
    }
    var showGoToPage by remember { mutableStateOf(false) }
    var showContents by remember { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }
    if (showGoToPage) GoToPageDialog(pdfNavigation, onDismiss = { showGoToPage = false })
    if (showContents) PdfContentsSheet(pdfNavigation, onDismiss = { showContents = false })
    if (showOptions) {
        PdfViewOptionsSheet(
            darkPages = preferences.darkPdfPages,
            onDarkPagesChange = preferences::updateDarkPdfPages,
            onPages = { pdfNavigation.overviewOpen = true },
            onGoToPage = { showGoToPage = true },
            onDismiss = { showOptions = false },
        )
    }
    // The top bar's back arrow leaves the page overview first, like the back gesture does.
    val back = { if (pdfNavigation.overviewOpen) pdfNavigation.overviewOpen = false else onBack() }
    val pdfToolsShown = pdfSearch.available && !pdfSearch.active && !pdfNavigation.overviewOpen

    PvotScreenScaffold(
        topBar = {
            PvotTopBar(title = name, onBack = back, compact = true) {
                if (pdfNavigation.outline.isNotEmpty() && pdfToolsShown) {
                    PvotTopBarAction(
                        icon = painterResource(R.drawable.ic_list),
                        contentDescription = stringResource(R.string.viewer_pdf_contents),
                        onClick = { showContents = true }
                    )
                }
                if (pdfToolsShown) {
                    PvotTopBarAction(
                        icon = painterResource(R.drawable.ic_search),
                        contentDescription = stringResource(R.string.viewer_pdf_search),
                        onClick = pdfSearch::open
                    )
                    PvotTopBarAction(
                        icon = painterResource(R.drawable.ic_more),
                        contentDescription = stringResource(R.string.viewer_pdf_more),
                        onClick = { showOptions = true }
                    )
                }
            }
        },
        snackbarHost = { PvotSnackbarHost(snackbar) },
        bottomBar = {
            // The page indicator, the selection actions and the search bar share the bottom:
            // search wins, then a selection, then the indicator.
            AnimatedVisibility(
                visible = !pdfSearch.active && !pdfSelection.active && !pdfNavigation.overviewOpen &&
                    pdfNavigation.indicatorVisible && pdfNavigation.pageCount > 1,
                enter = fadeIn(tween(PvotDurations.Short)),
                exit = fadeOut(tween(PvotDurations.Medium))
            ) {
                PdfPageIndicator(pdfNavigation, onClick = { showGoToPage = true })
            }
            AnimatedVisibility(
                visible = pdfSelection.active && !pdfSearch.active && !pdfNavigation.overviewOpen,
                enter = fadeIn(tween(PvotDurations.Medium, easing = PvotMotion.Settle)) +
                    slideInVertically(tween(PvotDurations.Medium, easing = PvotMotion.Settle)) { it / 2 },
                exit = fadeOut(tween(PvotDurations.Short)) +
                    slideOutVertically(tween(PvotDurations.Short)) { it / 2 }
            ) {
                PdfSelectionBar(pdfSelection)
            }
            AnimatedVisibility(
                visible = pdfSearch.active,
                enter = fadeIn(tween(PvotDurations.Medium, easing = PvotMotion.Settle)) +
                    slideInVertically(tween(PvotDurations.Medium, easing = PvotMotion.Settle)) { it / 2 },
                exit = fadeOut(tween(PvotDurations.Short)) +
                    slideOutVertically(tween(PvotDurations.Short)) { it / 2 }
            ) {
                PdfSearchBar(pdfSearch)
            }
        }
    ) { padding ->
        if (format == ViewerFormat.Pdf) {
            PdfView(
                uri = uri,
                topPadding = padding.calculateTopPadding(),
                search = pdfSearch,
                navigation = pdfNavigation,
                reading = pdfReading,
                selection = pdfSelection,
                modifier = Modifier.fillMaxSize()
            )
        } else if (format == ViewerFormat.Spreadsheet) {
            SpreadsheetView(
                uri = uri,
                topPadding = padding.calculateTopPadding(),
                modifier = Modifier.fillMaxSize()
            )
        } else if (format == ViewerFormat.Slides) {
            SlidesView(
                uri = uri,
                topPadding = padding.calculateTopPadding(),
                navigation = pdfNavigation,
                modifier = Modifier.fillMaxSize()
            )
        } else if (format == ViewerFormat.Docx || format == ViewerFormat.Odt) {
            RichDocumentView(
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
            CsvSheetView(
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

/** What a document's reading position is remembered under: its name and size, rather than its
 * URI. The same file reaches the viewer under different URIs — each "Open with" saves a fresh
 * copy, and a folder can be re-added — but it keeps its name and size. Falls back to the URI when
 * the size can't be read. */
private fun readingKey(context: Context, uri: Uri, name: String): String {
    val size = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
    }.getOrNull()
    return if (size != null && size > 0) "$name|$size" else uri.toString()
}
