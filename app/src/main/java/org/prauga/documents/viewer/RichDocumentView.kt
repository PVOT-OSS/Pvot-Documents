package org.prauga.documents.viewer

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.prauga.pvot.coreui.PvotErrorContent
import com.prauga.pvot.coreui.PvotLoadingContent
import com.prauga.pvot.coreui.UiState
import com.prauga.pvot.designsystem.theme.PvotShapes
import com.prauga.pvot.designsystem.theme.PvotSpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.prauga.documents.R
import org.prauga.documents.util.DocBlock
import org.prauga.documents.util.DocImage
import org.prauga.documents.util.DocPageBreak
import org.prauga.documents.util.DocParagraph
import org.prauga.documents.util.DocTable
import org.prauga.documents.util.ParagraphAlign
import org.prauga.documents.util.RichDocument
import org.prauga.documents.util.TextPosition
import org.prauga.documents.util.TextRun
import org.prauga.documents.util.parseDocx
import org.prauga.documents.util.parseOdt
import java.io.File
import java.util.zip.ZipFile

/** A Word (`.docx`) or OpenDocument (`.odt`) document as flowing, selectable text: headings,
 * lists, tables and pictures in reading order, styled as the document styles them, reflowed to
 * the phone's width rather than laid out as pages. */
@Composable
fun RichDocumentView(uri: Uri, topPadding: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var state by remember(uri) { mutableStateOf<UiState<RichDocument>>(UiState.Loading) }
    var retryToken by remember(uri) { mutableIntStateOf(0) }
    val readFailedMessage = stringResource(R.string.viewer_read_failed)

    LaunchedEffect(uri, retryToken) {
        state = UiState.Loading
        state = try {
            UiState.Success(withContext(Dispatchers.IO) { readRichDocument(context, uri) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UiState.Error(readFailedMessage, e)
        }
    }
    val opened = (state as? UiState.Success)?.data
    DisposableEffect(opened) { onDispose { opened?.close() } }

    when (val current = state) {
        is UiState.Loading -> Box(modifier.padding(top = topPadding), contentAlignment = Alignment.Center) {
            PvotLoadingContent()
        }
        is UiState.Error -> Box(modifier.padding(top = topPadding), contentAlignment = Alignment.Center) {
            PvotErrorContent(
                title = stringResource(R.string.viewer_read_failed),
                message = current.cause?.message.orEmpty(),
                onRetry = { retryToken++ },
            )
        }
        is UiState.Success -> DocumentContent(current.data, topPadding, modifier)
    }
}

/** [uri] parsed as a document. Both formats are zips read by random access (and images are read
 * later, as they scroll into view), so the file is copied to a private temporary file, which the
 * [RichDocument] deletes when closed. The format is recognized from the zip's contents. */
private fun readRichDocument(context: Context, uri: Uri): RichDocument {
    val temp = File.createTempFile("document", ".zip", context.cacheDir)
    try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            temp.outputStream().use { input.copyTo(it) }
        } ?: error("Could not open $uri for reading")
        val isWord = ZipFile(temp).use { it.getEntry("word/document.xml") != null }
        return if (isWord) parseDocx(temp) else parseOdt(temp)
    } catch (e: Throwable) {
        temp.delete()
        throw e
    }
}

/** What a paragraph's text is measured against: the document's own body size in points, and the
 * on-screen size it maps to, so a 16pt heading in an 11pt document is drawn 16/11 as big. */
private class TextScale(val bodyPt: Float, val bodySize: TextUnit)

private val LocalTextScale = compositionLocalOf { TextScale(DefaultBodyPt, TextUnit.Unspecified) }

/** The fill of the table cell a paragraph sits in, so its text color is checked against that. */
private val LocalCellFill = compositionLocalOf<Color?> { null }

/** Opens a link from the document, after asking. */
private val LocalOpenLink = compositionLocalOf<(String) -> Unit> { {} }

private const val DefaultBodyPt = 11f

@Composable
private fun DocumentContent(document: RichDocument, topPadding: Dp, modifier: Modifier) {
    val bodyPt = remember(document) { bodySizePt(document.blocks, document.defaultSizePt) }
    var pendingLink by remember(document) { mutableStateOf<String?>(null) }
    pendingLink?.let { OpenLinkDialog(it, onDismiss = { pendingLink = null }) }
    CompositionLocalProvider(
        LocalTextScale provides TextScale(bodyPt, MaterialTheme.typography.bodyLarge.fontSize),
        LocalOpenLink provides { link -> pendingLink = link },
    ) {
        SelectionContainer {
            LazyColumn(
                modifier = modifier,
                contentPadding = PaddingValues(
                    top = topPadding + PvotSpacing.L,
                    start = PvotSpacing.L,
                    end = PvotSpacing.L,
                    bottom = PvotSpacing.L,
                ),
            ) {
                items(document.blocks) { block -> Block(block, document) }
            }
        }
    }
}

/** The size most of the document's body text is set in (runs without a size are at
 * [defaultPt]): the base that other sizes are scaled from. */
private fun bodySizePt(blocks: List<DocBlock>, defaultPt: Float): Float {
    val counts = HashMap<Float, Int>()
    for (block in blocks) {
        if (block !is DocParagraph || block.heading != null) continue
        for (run in block.runs) {
            val size = run.style.sizePt ?: defaultPt
            counts[size] = (counts[size] ?: 0) + run.text.length
        }
    }
    return counts.maxByOrNull { it.value }?.key ?: defaultPt
}

@Composable
private fun Block(block: DocBlock, document: RichDocument) {
    when (block) {
        is DocParagraph -> Paragraph(block)
        is DocTable -> Table(block, document)
        is DocImage -> Picture(block, document)
        DocPageBreak -> Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = PvotSpacing.M)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
    }
}

@Composable
private fun Paragraph(paragraph: DocParagraph) {
    if (paragraph.isEmpty && paragraph.list == null) {
        Box(Modifier.height(PvotSpacing.S))
        return
    }
    val scale = LocalTextScale.current
    val openLink = LocalOpenLink.current
    val background = LocalCellFill.current ?: MaterialTheme.colorScheme.background
    val defaultColor = MaterialTheme.colorScheme.onBackground
    val linkColor = MaterialTheme.colorScheme.primary
    val baseStyle = paragraph.heading?.let { headingStyle(it) } ?: MaterialTheme.typography.bodyLarge
    val text = remember(paragraph, scale, background, defaultColor, linkColor) {
        buildParagraphText(paragraph.runs, scale, background, defaultColor, linkColor, openLink)
    }
    val style = baseStyle.copy(
        color = defaultColor,
        fontWeight = if (paragraph.heading != null) FontWeight.Bold else baseStyle.fontWeight,
        textAlign = when (paragraph.align) {
            ParagraphAlign.Start -> TextAlign.Start
            ParagraphAlign.Center -> TextAlign.Center
            ParagraphAlign.End -> TextAlign.End
            ParagraphAlign.Justify -> TextAlign.Justify
        },
    )
    val verticalSpace = if (paragraph.heading != null) PvotSpacing.S else PvotSpacing.XS
    val list = paragraph.list
    if (list == null) {
        Text(
            text = text,
            style = style,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = PvotSpacing.L * paragraph.indentLevel, top = verticalSpace, bottom = verticalSpace),
        )
    } else {
        // The marker hangs in its own column, so wrapped lines align with the item's text.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = PvotSpacing.L * list.level, top = PvotSpacing.XS / 2, bottom = PvotSpacing.XS / 2),
        ) {
            Text(text = list.label, style = style.copy(textAlign = TextAlign.End), modifier = Modifier.width(ListMarkerWidth))
            Text(text = text, style = style, modifier = Modifier.weight(1f).padding(start = PvotSpacing.S))
        }
    }
}

private val ListMarkerWidth = 32.dp

@Composable
private fun headingStyle(level: Int): TextStyle = when (level) {
    1 -> MaterialTheme.typography.headlineMedium
    2 -> MaterialTheme.typography.headlineSmall
    3 -> MaterialTheme.typography.titleLarge
    4 -> MaterialTheme.typography.titleMedium
    else -> MaterialTheme.typography.titleSmall
}

private fun buildParagraphText(
    runs: List<TextRun>,
    scale: TextScale,
    background: Color,
    defaultColor: Color,
    linkColor: Color,
    openLink: (String) -> Unit,
): AnnotatedString = buildAnnotatedString {
    for (run in runs) {
        val s = run.style
        val highlight = s.highlight?.let { Color(it) }
        val span = SpanStyle(
            fontWeight = if (s.bold) FontWeight.Bold else null,
            fontStyle = if (s.italic) FontStyle.Italic else null,
            textDecoration = when {
                s.underline && s.strike -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
                s.underline -> TextDecoration.Underline
                s.strike -> TextDecoration.LineThrough
                else -> null
            },
            color = when {
                s.link != null && s.color == null -> linkColor
                else -> readableColor(s.color?.let { Color(it) }, highlight ?: background, defaultColor)
            },
            background = highlight ?: Color.Unspecified,
            fontSize = s.sizePt?.takeIf { scale.bodySize != TextUnit.Unspecified }?.let {
                scale.bodySize * (it / scale.bodyPt).coerceIn(MinScale, MaxScale)
            } ?: TextUnit.Unspecified,
            baselineShift = when (s.position) {
                TextPosition.Superscript -> BaselineShift.Superscript
                TextPosition.Subscript -> BaselineShift.Subscript
                TextPosition.Normal -> null
            },
            fontFamily = if (s.monospace) FontFamily.Monospace else null,
        )
        val link = s.link?.takeIf(::isOpenableLink)
        if (link != null) {
            withLink(LinkAnnotation.Clickable(link, TextLinkStyles(span)) { openLink(link) }) { append(run.text) }
        } else if (s.position != TextPosition.Normal) {
            withStyle(span.copy(fontSize = SmallPositionSize)) { append(run.text) }
        } else {
            withStyle(span) { append(run.text) }
        }
    }
}

private const val MinScale = 0.6f
private const val MaxScale = 3f
private val SmallPositionSize = 0.75.em

/** The document's own text color when it reads against what's behind it; otherwise the theme's
 * text color, or black or white over a highlight. Documents written on white paper often spell
 * out black text, which would vanish on a dark theme. */
private fun readableColor(own: Color?, behind: Color, default: Color): Color {
    if (own != null && contrast(own, behind) >= MinContrast) return own
    if (behind == Color.Unspecified || contrast(default, behind) >= MinContrast) return default
    return if (behind.luminance() > 0.5f) Color.Black else Color.White
}

private fun contrast(a: Color, b: Color): Float {
    val la = a.luminance() + 0.05f
    val lb = b.luminance() + 0.05f
    return if (la > lb) la / lb else lb / la
}

private const val MinContrast = 2.5f

@Composable
private fun Table(table: DocTable, document: RichDocument) {
    val columns = table.columnCount.coerceAtLeast(1)
    val grid = MaterialTheme.colorScheme.outlineVariant
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = PvotSpacing.S)) {
        // Columns share the width, but never get narrower than a readable minimum; a wider
        // table scrolls sideways.
        val columnWidth = maxOf(maxWidth / columns, MinColumnWidth)
        Column(
            Modifier
                .horizontalScroll(rememberScrollState())
                .border(1.dp, grid),
        ) {
            for (row in table.rows) {
                Row(Modifier.height(IntrinsicSize.Min)) {
                    for (cell in row) {
                        Box(
                            Modifier
                                .width(columnWidth * cell.colSpan)
                                .fillMaxHeight()
                                .then(if (cell.fill != null && !cell.covered) Modifier.background(Color(cell.fill)) else Modifier)
                                .border(0.5.dp, grid)
                                .padding(PvotSpacing.S),
                        ) {
                            CompositionLocalProvider(LocalCellFill provides cell.fill?.let { Color(it) }) {
                                Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                                    for (block in cell.blocks) Block(block, document)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val MinColumnWidth = 96.dp

/** A picture, read from the document when it comes into view and decoded at screen width. Shown
 * at its intended size when that fits, otherwise scaled down to the width. */
@Composable
private fun Picture(image: DocImage, document: RichDocument) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = PvotSpacing.S), contentAlignment = Alignment.Center) {
        val maxWidthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        val bitmap by produceState<ImageBitmap?>(null, image, document) {
            value = withContext(Dispatchers.IO) { document.image(image.path, maxWidthPx)?.asImageBitmap() }
        }
        val intendedWidth = image.widthPt?.let { (it * PtToDp).dp }
        val width = intendedWidth?.coerceAtMost(maxWidth) ?: maxWidth
        val aspect = image.widthPt?.let { w -> image.heightPt?.takeIf { it > 0f }?.let { w / it } }
            ?: bitmap?.let { it.width.toFloat() / it.height }
            ?: DefaultImageAspect
        val description = image.description
        val current = bitmap
        val box = Modifier
            .widthIn(max = width)
            .fillMaxWidth()
            .aspectRatio(aspect)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
        if (current == null) {
            Box(box.background(MaterialTheme.colorScheme.surfaceVariant, PvotShapes.Small))
        } else {
            Image(bitmap = current, contentDescription = description, contentScale = ContentScale.Fit, modifier = box)
        }
    }
}

/** Points to dp: a point is 1/72 inch, a dp 1/160. */
private const val PtToDp = 160f / 72f

private const val DefaultImageAspect = 4f / 3f
