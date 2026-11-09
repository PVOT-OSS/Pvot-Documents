package org.prauga.documents.viewer

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prauga.pvot.coreui.PvotEmptyContent
import com.prauga.pvot.coreui.PvotErrorContent
import com.prauga.pvot.coreui.PvotLoadingContent
import com.prauga.pvot.coreui.UiState
import com.prauga.pvot.designsystem.components.PvotTabRow
import com.prauga.pvot.designsystem.theme.PvotSpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.prauga.documents.R
import org.prauga.documents.util.CellAlign
import org.prauga.documents.util.CellRange
import org.prauga.documents.util.CellStyle
import org.prauga.documents.util.Sheet
import org.prauga.documents.util.SheetCell
import org.prauga.documents.util.SheetRow
import org.prauga.documents.util.Workbook
import org.prauga.documents.util.columnName
import org.prauga.documents.util.parseOds
import org.prauga.documents.util.parseXlsx
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.max

/** An Excel or OpenDocument spreadsheet: its sheets as tabs, each a grid that scrolls both ways
 * with the column letters and row numbers kept in view, and pinch to zoom. */
@Composable
fun SpreadsheetView(uri: Uri, topPadding: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var state by remember(uri) { mutableStateOf<UiState<Workbook>>(UiState.Loading) }
    var retryToken by remember(uri) { mutableIntStateOf(0) }
    val readFailedMessage = stringResource(R.string.viewer_read_failed)

    LaunchedEffect(uri, retryToken) {
        state = UiState.Loading
        state = try {
            UiState.Success(withContext(Dispatchers.IO) { readWorkbook(context, uri) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UiState.Error(readFailedMessage, e)
        }
    }

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
        is UiState.Success -> WorkbookContent(current.data, topPadding, modifier)
    }
}

/** A grid of plain rows (CSV), shown the same way as a one-sheet workbook. */
@Composable
fun CsvSheetView(rows: List<List<String>>, topPadding: Dp, modifier: Modifier = Modifier) {
    val workbook = remember(rows) { csvWorkbook(rows) }
    WorkbookContent(workbook, topPadding, modifier)
}

/**
 * [uri] parsed as a workbook. Both formats are zips whose parts are read out of order, so the
 * document is copied to a private temporary file first and deleted after. The format is
 * recognized from the zip's contents rather than the name, which a sender may have got wrong.
 */
private fun readWorkbook(context: Context, uri: Uri): Workbook {
    val temp = File.createTempFile("sheet", ".zip", context.cacheDir)
    try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            temp.outputStream().use { input.copyTo(it) }
        } ?: error("Could not open $uri for reading")
        val isOds = ZipFile(temp).use { it.getEntry("content.xml") != null && it.getEntry("xl/workbook.xml") == null }
        return if (isOds) parseOds(temp) else parseXlsx(temp)
    } finally {
        temp.delete()
    }
}

private fun csvWorkbook(rows: List<List<String>>): Workbook {
    val header = CellStyle(bold = true)
    val sheetRows = rows.mapIndexed { r, cells ->
        SheetRow(
            r,
            cells.mapIndexedNotNull { c, text ->
                if (text.isEmpty()) null else SheetCell(c, text, if (r == 0) 1 else 0, text.toDoubleOrNull() != null)
            },
            null,
        )
    }
    val colCount = rows.maxOfOrNull { it.size } ?: 0
    val sheet = Sheet(
        name = "",
        rows = sheetRows.filter { it.cells.isNotEmpty() },
        rowCount = rows.size,
        colCount = colCount,
        colWidths = emptyMap(),
        defaultColWidth = CsvColumnWidthChars,
        hiddenRows = emptySet(),
        merges = emptyList(),
    )
    return Workbook(listOf(sheet), listOf(CellStyle.Default, header), truncated = false)
}

/** CSV has no widths of its own; a little wider than Excel's default suits typical text columns. */
private const val CsvColumnWidthChars = 14f

@Composable
private fun WorkbookContent(workbook: Workbook, topPadding: Dp, modifier: Modifier) {
    var selected by remember(workbook) { mutableIntStateOf(0) }
    Column(modifier.fillMaxSize().padding(top = topPadding)) {
        if (workbook.sheets.size > 1) {
            PvotTabRow(
                tabs = workbook.sheets.map { it.name },
                selectedIndex = selected,
                onSelect = { selected = it },
                scrollable = true,
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background),
            )
        }
        val sheet = workbook.sheets.getOrNull(selected)
        if (sheet == null || sheet.rowCount == 0) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                PvotEmptyContent(message = stringResource(R.string.viewer_sheet_empty))
            }
        } else {
            // Keyed on the sheet, so each tab starts at its own top-left.
            key(selected) {
                SheetGrid(sheet, workbook.styles, Modifier.fillMaxSize())
            }
        }
    }
}

/** Sizes at zoom 1, in dp, from the sheet's own units: Excel's widths count characters of the
 * default font, about 7px each plus 5px of padding, and heights are in points. */
private const val PxPerChar = 7f
private const val ColumnPadding = 5f
private val MinRowHeight = 24.dp
private val HeaderHeight = 24.dp
private val RowHeaderWidth = 44.dp
private val CellPadding = 4.dp
private const val MinZoom = 0.5f
private const val MaxZoom = 3f
private val CellTextSize = 12.sp

@Composable
private fun SheetGrid(sheet: Sheet, styles: List<CellStyle>, modifier: Modifier) {
    val density = LocalDensity.current
    var zoom by remember(sheet) { mutableFloatStateOf(1f) }
    var scrollX by remember(sheet) { mutableFloatStateOf(0f) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val listState = rememberLazyListState()
    val measurer = rememberTextMeasurer(cacheSize = TextCacheSize)
    val scope = rememberCoroutineScope()

    // Column edges in px at the current zoom; edges[c] is column c's left edge after the row header.
    // A few empty columns follow the used ones, as in a spreadsheet, for text to spill into.
    val columns = sheet.colCount + TrailingColumns
    val edges = remember(sheet, zoom, density) {
        FloatArray(columns + 1).also { e ->
            for (c in 0 until columns) {
                val chars = sheet.colWidth(c)
                val widthDp = if (chars <= 0f) 0f else chars * PxPerChar + ColumnPadding
                e[c + 1] = e[c] + with(density) { widthDp.dp.toPx() } * zoom
            }
        }
    }
    val rowHeaderPx = with(density) { RowHeaderWidth.toPx() } * zoom.coerceAtLeast(1f)
    val headerPx = with(density) { HeaderHeight.toPx() } * zoom.coerceAtLeast(1f)
    val maxScrollX = (edges.last() - (viewport.width - rowHeaderPx)).coerceAtLeast(0f)
    if (scrollX > maxScrollX) scrollX = maxScrollX

    val horizontal = rememberScrollableState { delta ->
        val next = (scrollX - delta).coerceIn(0f, maxScrollX)
        val consumed = scrollX - next
        scrollX = next
        consumed
    }

    val merges = remember(sheet) { MergeIndex(sheet.merges) }
    val colors = SheetColors(
        background = MaterialTheme.colorScheme.background,
        text = MaterialTheme.colorScheme.onSurface,
        headerBackground = MaterialTheme.colorScheme.surfaceContainer,
        headerText = MaterialTheme.colorScheme.onSurfaceVariant,
        grid = MaterialTheme.colorScheme.outlineVariant,
    )
    val sheetDescription = stringResource(R.string.viewer_sheet_description, sheet.rowCount, sheet.colCount)

    Box(
        modifier
            .clipToBounds()
            .onSizeChanged { viewport = it }
            .semantics { contentDescription = sheetDescription }
            // Pinch zooms the whole grid around the fingers; one finger is left to scrolling.
            .pointerInput(sheet) {
                awaitEachGesture {
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } >= 2) {
                            val factor = event.calculateZoom()
                            if (factor != 1f) {
                                val old = zoom
                                val new = (old * factor).coerceIn(MinZoom, MaxZoom)
                                val focusX = event.calculateCentroid().x - rowHeaderPx
                                scrollX = ((scrollX + focusX) * (new / old) - focusX).coerceAtLeast(0f)
                                zoom = new
                                // Keep roughly the same rows in view as they grow or shrink.
                                val first = listState.firstVisibleItemIndex
                                val offset = (listState.firstVisibleItemScrollOffset * (new / old)).toInt()
                                scope.launch { listState.scrollToItem(first, offset) }
                            }
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .scrollable(horizontal, Orientation.Horizontal),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = with(density) { headerPx.toDp() }, bottom = PvotSpacing.L),
        ) {
            items(count = sheet.rowCount, key = { it }) { r ->
                if (r in sheet.hiddenRows) {
                    Spacer(Modifier.height(0.dp))
                } else {
                    val heightPx = max(
                        with(density) { MinRowHeight.toPx() },
                        sheet.row(r)?.heightPt?.let { with(density) { (it * PtToDp).dp.toPx() } } ?: 0f,
                    ) * zoom
                    Canvas(Modifier.fillMaxWidth().height(with(density) { heightPx.toDp() })) {
                        drawSheetRow(sheet, r, styles, edges, scrollX, rowHeaderPx, zoom, measurer, colors, merges)
                    }
                }
            }
        }
        // Column letters, fixed at the top.
        Canvas(Modifier.fillMaxWidth().height(with(density) { headerPx.toDp() })) {
            drawColumnHeader(sheet, edges, scrollX, rowHeaderPx, zoom, measurer, colors)
        }
    }
}

/** Points to dp: a point is 1/72 inch, a dp 1/160, so 1pt is about 1.33dp. */
private const val PtToDp = 4f / 3f

private const val TextCacheSize = 512

/** Empty columns shown past the last one in use. */
private const val TrailingColumns = 3

private class SheetColors(
    val background: Color,
    val text: Color,
    val headerBackground: Color,
    val headerText: Color,
    val grid: Color,
)

/** Merged ranges by the row they start on, and a lookup for the cells they cover. */
private class MergeIndex(merges: List<CellRange>) {
    private val byStartRow: Map<Int, List<CellRange>> = merges.groupBy { it.firstRow }

    // Every row a merge spans, so a lookup only checks the merges on that row; spans are capped,
    // since a merge down a whole column would otherwise list a million rows.
    private val byRow: Map<Int, List<CellRange>> = HashMap<Int, MutableList<CellRange>>().also { map ->
        for (m in merges) {
            for (r in m.firstRow..minOf(m.lastRow, m.firstRow + MaxMergeRows)) map.getOrPut(r) { ArrayList() }.add(m)
        }
    }

    fun startingAt(row: Int, col: Int): CellRange? = byStartRow[row]?.firstOrNull { it.firstCol == col }

    /** Whether ([row], [col]) is inside a merge but not its top-left cell. */
    fun covered(row: Int, col: Int): Boolean = byRow[row]?.any {
        col in it.firstCol..it.lastCol && !(row == it.firstRow && col == it.firstCol)
    } ?: false

    val isEmpty: Boolean = merges.isEmpty()
}

private const val MaxMergeRows = 10_000

/** First column whose right edge is past [x]. */
private fun firstColumnAt(edges: FloatArray, x: Float): Int {
    var lo = 0
    var hi = edges.size - 2
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (edges[mid + 1] <= x) lo = mid + 1 else hi = mid
    }
    return lo.coerceAtLeast(0)
}

private fun DrawScope.drawColumnHeader(
    sheet: Sheet,
    edges: FloatArray,
    scrollX: Float,
    rowHeaderPx: Float,
    zoom: Float,
    measurer: TextMeasurer,
    colors: SheetColors,
) {
    drawRect(colors.headerBackground)
    val style = TextStyle(fontSize = CellTextSize * zoom.coerceAtLeast(1f), color = colors.headerText, textAlign = TextAlign.Center)
    clipRect(left = rowHeaderPx) {
        var c = firstColumnAt(edges, scrollX)
        while (c < edges.size - 1) {
            val left = rowHeaderPx + edges[c] - scrollX
            if (left > size.width) break
            val width = edges[c + 1] - edges[c]
            if (width > 0f) {
                drawCellText(measurer, columnName(c), style, left, 0f, width, size.height, wrap = false, align = TextAlign.Center)
                drawLine(colors.grid, Offset(left + width, 0f), Offset(left + width, size.height))
            }
            c++
        }
    }
    drawLine(colors.grid, Offset(0f, size.height), Offset(size.width, size.height))
}

private fun DrawScope.drawSheetRow(
    sheet: Sheet,
    r: Int,
    styles: List<CellStyle>,
    edges: FloatArray,
    scrollX: Float,
    rowHeaderPx: Float,
    zoom: Float,
    measurer: TextMeasurer,
    colors: SheetColors,
    merges: MergeIndex,
) {
    val row = sheet.row(r)
    val textSize = CellTextSize * zoom
    val pad = CellPadding.toPx() * zoom
    clipRect(left = rowHeaderPx) {
        val firstVisible = firstColumnAt(edges, scrollX)
        // Grid lines for the visible columns.
        var c = firstVisible
        while (c < edges.size - 1) {
            val right = rowHeaderPx + edges[c + 1] - scrollX
            if (right - (edges[c + 1] - edges[c]) > size.width) break
            if (merges.isEmpty || !merges.covered(r, c + 1) || merges.covered(r, c)) {
                drawLine(colors.grid, Offset(right, 0f), Offset(right, size.height))
            }
            c++
        }
        drawLine(colors.grid, Offset(rowHeaderPx, size.height), Offset(size.width, size.height))

        val cells = row?.cells ?: return@clipRect
        // Start from a cell that may spill text into the first visible column.
        var i = cells.binarySearchBy(firstVisible) { it.col }.let { if (it < 0) -it - 1 else it }
        i = (i - 1).coerceAtLeast(0)
        while (i < cells.size) {
            val cell = cells[i]
            val left = rowHeaderPx + edges[cell.col.coerceAtMost(edges.size - 1)] - scrollX
            if (left > size.width) break
            if (cell.col >= edges.size - 1 || (!merges.isEmpty && merges.covered(r, cell.col))) { i++; continue }
            val style = styles.getOrElse(cell.style) { CellStyle.Default }
            val merge = if (merges.isEmpty) null else merges.startingAt(r, cell.col)
            val ownRight = edges[(merge?.lastCol ?: cell.col).coerceAtMost(edges.size - 2) + 1]
            val ownWidth = ownRight - edges[cell.col]
            if (ownWidth <= 0f) { i++; continue }
            style.fill?.let { drawRect(Color(it), Offset(left, 0f), Size(ownWidth, size.height)) }
            if (cell.text.isNotEmpty()) {
                val align = when (style.align) {
                    CellAlign.Start -> TextAlign.Start
                    CellAlign.Center -> TextAlign.Center
                    CellAlign.End -> TextAlign.End
                    CellAlign.Auto -> if (cell.numeric) TextAlign.End else TextAlign.Start
                }
                // Text that doesn't fit spills over empty neighbours to the right, as in a
                // spreadsheet; numbers, wrapped text and centered text stay in their cell.
                val spills = !cell.numeric && !style.wrap && align == TextAlign.Start && merge == null
                val nextCol = cells.getOrNull(i + 1)?.col ?: (edges.size - 1)
                val drawWidth = if (spills) edges[nextCol.coerceAtMost(edges.size - 1)] - edges[cell.col] else ownWidth
                val textStyle = TextStyle(
                    fontSize = textSize,
                    color = readableTextColor(style, colors),
                    fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
                    fontStyle = if (style.italic) FontStyle.Italic else FontStyle.Normal,
                    textDecoration = when {
                        style.underline && style.strike -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
                        style.underline -> TextDecoration.Underline
                        style.strike -> TextDecoration.LineThrough
                        else -> null
                    },
                    textAlign = align,
                )
                clipRect(left = left, right = left + drawWidth) {
                    drawCellText(measurer, cell.text, textStyle, left + pad, 0f, drawWidth - pad * 2, size.height, style.wrap, align)
                }
            }
            i++
        }
    }
    // Row number, fixed at the start.
    drawRect(colors.headerBackground, size = Size(rowHeaderPx, size.height))
    drawCellText(
        measurer,
        (r + 1).toString(),
        TextStyle(fontSize = CellTextSize * zoom.coerceAtLeast(1f), color = colors.headerText, textAlign = TextAlign.Center),
        0f, 0f, rowHeaderPx, size.height, wrap = false, align = TextAlign.Center,
    )
    drawLine(colors.grid, Offset(rowHeaderPx, 0f), Offset(rowHeaderPx, size.height))
    drawLine(colors.grid, Offset(0f, size.height), Offset(rowHeaderPx, size.height))
}

/** The cell's own text color when it reads against what's behind it; otherwise the theme's text
 * color, or black or white against a fill. A sheet made on white paper often spells out black
 * text, which would vanish on a dark theme. */
private fun readableTextColor(style: CellStyle, colors: SheetColors): Color {
    val background = style.fill?.let { Color(it) } ?: colors.background
    val own = style.textColor?.let { Color(it) }
    if (own != null && contrast(own, background) >= MinContrast) return own
    if (style.fill == null) return colors.text
    return if (background.luminance() > 0.5f) Color.Black else Color.White
}

private fun contrast(a: Color, b: Color): Float {
    val la = a.luminance() + 0.05f
    val lb = b.luminance() + 0.05f
    return if (la > lb) la / lb else lb / la
}

private const val MinContrast = 2.5f

private fun DrawScope.drawCellText(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    wrap: Boolean,
    align: TextAlign,
) {
    if (width <= 1f || height <= 1f) return
    val layout: TextLayoutResult = measurer.measure(
        text = if (wrap) text else text.replace('\n', ' '),
        style = style.copy(textAlign = align),
        overflow = TextOverflow.Clip,
        softWrap = wrap,
        maxLines = if (wrap) Int.MAX_VALUE else 1,
        constraints = Constraints(minWidth = width.toInt(), maxWidth = width.toInt()),
    )
    // Single lines sit centered in the row, as a spreadsheet draws them; wrapped text starts at
    // the top.
    val y = if (wrap) top else top + (height - layout.size.height) / 2f
    drawText(layout, topLeft = Offset(left, y))
}
