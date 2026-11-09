package org.prauga.documents.viewer

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import com.prauga.pvot.coreui.PvotErrorContent
import com.prauga.pvot.coreui.PvotLoadingContent
import com.prauga.pvot.coreui.UiState
import com.prauga.pvot.designsystem.theme.PvotSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.prauga.documents.R
import org.prauga.documents.util.DocxParagraph
import org.prauga.documents.util.parseDocx

/** [uri]'s paragraphs, each run styled with the bold/italic/underline Word actually applied to it,
 * headings shown larger and bold. Reads [uri] as a zip directly, same reason [PdfView] reads its
 * own file descriptor rather than going through the shared decoded-text path. */
@Composable
fun DocxView(uri: Uri, topPadding: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var state by remember(uri) { mutableStateOf<UiState<List<DocxParagraph>>>(UiState.Loading) }
    var retryToken by remember(uri) { mutableIntStateOf(0) }
    val readFailedMessage = stringResource(R.string.viewer_read_failed)

    LaunchedEffect(uri, retryToken) {
        state = UiState.Loading
        state = withContext(Dispatchers.IO) {
            runCatching { parseDocx(context, uri) }
                .fold(onSuccess = { UiState.Success(it) }, onFailure = { UiState.Error(readFailedMessage, it) })
        }
    }

    when (val current = state) {
        is UiState.Loading -> Box(
            modifier = modifier.padding(top = topPadding),
            contentAlignment = Alignment.Center
        ) { PvotLoadingContent() }

        is UiState.Error -> Box(
            modifier = modifier.padding(top = topPadding),
            contentAlignment = Alignment.Center
        ) {
            PvotErrorContent(
                title = stringResource(R.string.viewer_read_failed),
                message = current.cause?.message.orEmpty(),
                onRetry = { retryToken++ }
            )
        }

        is UiState.Success -> SelectionContainer {
            LazyColumn(
                modifier = modifier,
                contentPadding = PaddingValues(
                    top = topPadding + PvotSpacing.L,
                    start = PvotSpacing.L,
                    end = PvotSpacing.L,
                    bottom = PvotSpacing.L
                )
            ) {
                items(current.data) { paragraph -> DocxParagraphView(paragraph) }
            }
        }
    }
}

@Composable
private fun DocxParagraphView(paragraph: DocxParagraph) {
    if (paragraph.runs.isEmpty()) {
        Box(modifier = Modifier.height(PvotSpacing.M))
        return
    }
    Text(
        text = buildAnnotatedString {
            for (run in paragraph.runs) {
                withStyle(
                    SpanStyle(
                        fontWeight = if (run.bold) FontWeight.Bold else FontWeight.Normal,
                        fontStyle = if (run.italic) FontStyle.Italic else FontStyle.Normal,
                        textDecoration = if (run.underline) TextDecoration.Underline else TextDecoration.None
                    )
                ) { append(run.text) }
            }
        },
        style = headingStyleOrBody(paragraph.headingLevel),
        fontWeight = if (paragraph.headingLevel != null) FontWeight.Bold else null,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(vertical = PvotSpacing.XS)
    )
}

@Composable
private fun headingStyleOrBody(level: Int?) = when (level) {
    null -> MaterialTheme.typography.bodyMedium
    1 -> MaterialTheme.typography.headlineMedium
    2 -> MaterialTheme.typography.headlineSmall
    3 -> MaterialTheme.typography.titleLarge
    4 -> MaterialTheme.typography.titleMedium
    5 -> MaterialTheme.typography.titleSmall
    else -> MaterialTheme.typography.labelLarge
}
