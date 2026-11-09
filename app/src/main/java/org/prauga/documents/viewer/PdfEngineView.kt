package org.prauga.documents.viewer

import android.content.Context
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.graphics.Typeface
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.prauga.pvot.coreui.PvotErrorContent
import com.prauga.pvot.coreui.PvotLoadingContent
import com.prauga.pvot.coreui.UiState
import com.prauga.pvot.designsystem.components.button.PvotIconButton
import com.prauga.pvot.designsystem.components.textfield.PvotTextField
import com.prauga.pvot.designsystem.theme.PvotSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.prauga.documents.R
import org.prauga.pdfengine.PdfDocument
import org.prauga.pdfengine.PdfDrawOp
import org.prauga.pdfengine.PdfPage
import org.prauga.pdfengine.PdfPageSize
import org.prauga.pdfengine.PdfRect

/** [uri]'s pages, parsed and drawn by our own from-scratch Rust PDF engine (`:pdf-engine`) —
 * vector primitives interpreted from the content stream, drawn straight onto a Compose [Canvas]
 * rather than rasterized to a bitmap per page. No sandboxed render service (the `androidx.pdf`
 * problem) and no whole-document up-front rasterization (the PdfBox-Android memory/fidelity
 * problem this replaces) — pages decode lazily as they scroll into view. See `CLAUDE.md`. */
@Composable
fun PdfEngineView(uri: Uri, topPadding: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var state by remember(uri) { mutableStateOf<UiState<PdfDocument>>(UiState.Loading) }
    var retryToken by remember(uri) { mutableIntStateOf(0) }
    val readFailedMessage = stringResource(R.string.viewer_read_failed)

    LaunchedEffect(uri, retryToken) {
        state = UiState.Loading
        state = withContext(Dispatchers.IO) {
            runCatching { openPdf(context, uri) }
                .fold(onSuccess = { UiState.Success(it) }, onFailure = { UiState.Error(readFailedMessage, it) })
        }
    }

    DisposableEffect(uri, retryToken) {
        onDispose { (state as? UiState.Success)?.data?.close() }
    }

    when (val current = state) {
        is UiState.Loading -> Box(modifier = modifier, contentAlignment = Alignment.Center) { PvotLoadingContent() }
        is UiState.Error -> Box(modifier = modifier, contentAlignment = Alignment.Center) {
            PvotErrorContent(
                title = stringResource(R.string.viewer_read_failed),
                message = current.cause?.message.orEmpty(),
                onRetry = { retryToken++ }
            )
        }
        is UiState.Success -> PdfEngineContent(document = current.data, topPadding = topPadding, modifier = modifier)
    }
}

private fun openPdf(context: Context, uri: Uri): PdfDocument {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: error("Could not open $uri for reading")
    return PdfDocument.open(bytes) ?: error("Not a PDF this engine can parse")
}

private data class SearchMatch(val pageIndex: Int, val rect: PdfRect)

private data class PageSelection(val startGlyph: Int, val endGlyph: Int) {
    val lo get() = minOf(startGlyph, endGlyph)
    val hi get() = maxOf(startGlyph, endGlyph)
}

@Composable
private fun PdfEngineContent(document: PdfDocument, topPadding: Dp, modifier: Modifier) {
    val density = LocalDensity.current
    val widthPx = with(density) { PdfPageWidth.roundToPx() }
    val zoom = remember(document) { PdfEngineZoomState() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val pageCache = remember(document) { HashMap<Int, PdfPage>() }
    val clipboard = LocalClipboardManager.current

    var searchActive by remember(document) { mutableStateOf(false) }
    var searchQuery by remember(document) { mutableStateOf("") }
    var matches by remember(document) { mutableStateOf<List<SearchMatch>>(emptyList()) }
    var currentMatch by remember(document) { mutableIntStateOf(0) }

    var selectionPage by remember(document) { mutableIntStateOf(-1) }
    var selection by remember(document) { mutableStateOf<PageSelection?>(null) }

    LaunchedEffect(searchQuery, document) {
        if (searchQuery.isBlank()) {
            matches = emptyList()
            return@LaunchedEffect
        }
        delay(250)
        val found = withContext(Dispatchers.Default) {
            (0 until document.pageCount).flatMap { p ->
                document.searchPage(p, searchQuery).map { SearchMatch(p, it) }
            }
        }
        matches = found
        currentMatch = 0
        found.firstOrNull()?.let { scope.launch { listState.animateScrollToItem(it.pageIndex) } }
    }

    fun clearSelection() {
        selectionPage = -1
        selection = null
    }

    Box(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                // The block form of graphicsLayer defers reading zoom.scale/offsetX/offsetY to
                // the draw phase instead of composition — the (scaleX, scaleY, ...) parameter
                // overload reads them eagerly in the composable body, which means every single
                // pixel of a drag gesture was recomposing (and relaying out) the whole LazyColumn
                // and every visible page under it. That's what made one-finger pan after
                // pinch-zoom feel slow and "stuck".
                .graphicsLayer {
                    scaleX = zoom.scale
                    scaleY = zoom.scale
                    translationX = zoom.offsetX
                    translationY = zoom.offsetY
                }
                .pinchZoomEngine(zoom),
            contentPadding = PaddingValues(
                top = topPadding + (if (searchActive) SearchBarHeight else PvotSpacing.M),
                start = PvotSpacing.L,
                end = PvotSpacing.L,
                bottom = PvotSpacing.M,
            ),
        ) {
            items(document.pageCount) { index ->
                PdfPageItem(
                    document = document,
                    pageIndex = index,
                    widthPx = widthPx,
                    pageCache = pageCache,
                    searchHighlights = matches.filter { it.pageIndex == index }.map { it.rect },
                    currentMatchRect = matches.getOrNull(currentMatch)
                        ?.takeIf { it.pageIndex == index }?.rect,
                    selection = selection.takeIf { selectionPage == index },
                    onSelectionChange = { sel ->
                        selectionPage = index
                        selection = sel
                    },
                    onCopySelection = { text ->
                        clipboard.setText(AnnotatedString(text))
                        clearSelection()
                    },
                )
            }
        }

        if (searchActive) {
            PdfSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                matchCount = matches.size,
                currentMatch = currentMatch,
                onPrevious = {
                    if (matches.isNotEmpty()) {
                        currentMatch = (currentMatch - 1 + matches.size) % matches.size
                        scope.launch { listState.animateScrollToItem(matches[currentMatch].pageIndex) }
                    }
                },
                onNext = {
                    if (matches.isNotEmpty()) {
                        currentMatch = (currentMatch + 1) % matches.size
                        scope.launch { listState.animateScrollToItem(matches[currentMatch].pageIndex) }
                    }
                },
                onClose = {
                    searchActive = false
                    searchQuery = ""
                    matches = emptyList()
                },
                topPadding = topPadding,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            PvotIconButton(
                icon = androidx.compose.ui.res.painterResource(R.drawable.ic_search),
                contentDescription = stringResource(R.string.viewer_pdf_search),
                onClick = { searchActive = true },
                modifier = Modifier
                    .padding(top = topPadding + PvotSpacing.S, end = PvotSpacing.L)
                    .align(Alignment.TopEnd),
            )
        }
    }
}

private val PdfPageWidth = 480.dp
private val SearchBarHeight = 64.dp

@Composable
private fun PdfSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    matchCount: Int,
    currentMatch: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    topPadding: Dp,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.Row(
        modifier = modifier
            .padding(top = topPadding + PvotSpacing.S, start = PvotSpacing.L, end = PvotSpacing.L)
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                MaterialTheme.shapes.large,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PvotTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f).padding(horizontal = PvotSpacing.S),
            placeholder = stringResource(R.string.viewer_pdf_search_placeholder),
            singleLine = true,
        )
        if (query.isNotBlank()) {
            Text(
                text = if (matchCount > 0) {
                    stringResource(R.string.viewer_pdf_search_count, currentMatch + 1, matchCount)
                } else {
                    stringResource(R.string.viewer_pdf_search_none)
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = PvotSpacing.XS),
            )
        }
        PvotIconButton(
            icon = androidx.compose.ui.res.painterResource(R.drawable.ic_chevron_up),
            contentDescription = stringResource(R.string.viewer_pdf_search_previous),
            onClick = onPrevious,
            size = 36.dp,
        )
        PvotIconButton(
            icon = androidx.compose.ui.res.painterResource(R.drawable.ic_chevron_down),
            contentDescription = stringResource(R.string.viewer_pdf_search_next),
            onClick = onNext,
            size = 36.dp,
        )
        PvotIconButton(
            icon = androidx.compose.ui.res.painterResource(R.drawable.ic_x),
            contentDescription = stringResource(R.string.viewer_pdf_search_clear),
            onClick = onClose,
            size = 36.dp,
        )
    }
}

/** The whole scrolling PDF's shared zoom level and pan offset — pinch-zoom scales the entire
 * document in place, the way a normal PDF viewer does, rather than one page inside its own
 * container. Reset whenever [remember]'s key (the document) changes. */
private class PdfEngineZoomState {
    var scale by mutableFloatStateOf(1f)
    var offsetX by mutableFloatStateOf(0f)
    var offsetY by mutableFloatStateOf(0f)
}

private const val MaxPdfEngineZoom = 5f

private fun Modifier.pinchZoomEngine(zoom: PdfEngineZoomState): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        do {
            val event = awaitPointerEvent()
            // A plain one-finger drag only needs handling here once already zoomed in — at 1x it
            // must fall through untouched so the LazyColumn's own vertical scroll still works.
            // Without this, panning only ever worked mid-pinch (two fingers down); as soon as the
            // second finger lifted, a zoomed-in page became unpannable.
            if (event.changes.size >= 2 || zoom.scale > 1f) {
                val zoomChange = event.calculateZoom()
                val panChange = event.calculatePan()
                zoom.scale = (zoom.scale * zoomChange).coerceIn(1f, MaxPdfEngineZoom)
                if (zoom.scale > 1f) {
                    val maxOffsetX = size.width * (zoom.scale - 1f) / 2f
                    val maxOffsetY = size.height * (zoom.scale - 1f) / 2f
                    zoom.offsetX = (zoom.offsetX + panChange.x).coerceIn(-maxOffsetX, maxOffsetX)
                    zoom.offsetY = (zoom.offsetY + panChange.y).coerceIn(-maxOffsetY, maxOffsetY)
                } else {
                    zoom.offsetX = 0f
                    zoom.offsetY = 0f
                }
                event.changes.forEach { it.consume() }
            }
        } while (event.changes.any { it.pressed })
    }
}

@Composable
private fun PdfPageItem(
    document: PdfDocument,
    pageIndex: Int,
    widthPx: Int,
    pageCache: HashMap<Int, PdfPage>,
    searchHighlights: List<PdfRect>,
    currentMatchRect: PdfRect?,
    selection: PageSelection?,
    onSelectionChange: (PageSelection?) -> Unit,
    onCopySelection: (String) -> Unit,
) {
    val pageSize = remember(document, pageIndex) { document.pageSize(pageIndex) }
    var page by remember(pageIndex) { mutableStateOf(pageCache[pageIndex]) }

    LaunchedEffect(pageIndex) {
        if (page == null) {
            val decoded = withContext(Dispatchers.Default) {
                val rendered = document.renderPage(pageIndex)
                // Canvas draw calls always run on the UI thread, and PdfPage.imageBitmap's first
                // call per image crosses the JNI boundary and builds a full-resolution Bitmap from
                // raw pixel bytes — for a scanned page (a single multi-megapixel JPEG) that's
                // multiple megabytes of work. Doing that lazily on first draw turned "a new page
                // scrolls into view" into a dropped frame every time; decoding every image here,
                // off the main thread, means the Canvas only ever blits an already-built Bitmap.
                rendered.ops.filterIsInstance<PdfDrawOp.DrawImage>()
                    .map { it.imageIndex }
                    .distinct()
                    .forEach { rendered.imageBitmap(it) }
                rendered
            }
            pageCache[pageIndex] = decoded
            page = decoded
        }
    }

    // /Rotate (common on phone-scanned pages: the photo is landscape but the page is portrait
    // with a 90/270 rotation) swaps which axis is "width" for display purposes. The glyph/path
    // coordinates we get from the engine are still in the page's own unrotated space — drawPdfPage
    // applies the actual rotation transform when painting — but the on-screen aspect ratio (and
    // thus how big a card this page gets in the list) has to account for it up front.
    val rotated90 = pageSize.rotateDegrees == 90 || pageSize.rotateDegrees == 270
    val aspect = (if (rotated90) pageSize.height / pageSize.width else pageSize.width / pageSize.height)
        .let { if (it.isFinite() && it > 0f) it else 0.77f }
    val highlightColor = MaterialTheme.colorScheme.tertiary
    val currentHighlightColor = MaterialTheme.colorScheme.primary
    val selectionColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    val handleColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .padding(bottom = PvotSpacing.S)
            // A PDF page with no content op covering its full area (plain text-only pages are
            // common — no explicit background fill) is implicitly white per the PDF spec; without
            // this, pages like that show whatever is behind the Canvas (this app's dark theme)
            // instead, and any black text on them becomes invisible against it.
            .background(Color.White),
    ) {
        val currentPage = page
        if (currentPage != null) {
            val glyphs = remember(currentPage) { currentPage.ops.filterIsInstance<PdfDrawOp.DrawGlyph>() }
            // pointerInput below is keyed on currentPage, so its gesture coroutine outlives any
            // single recomposition: a lambda that closed over `selection` directly would keep
            // seeing the value from when the coroutine was launched (null), never the live one.
            // rememberUpdatedState gives the running coroutine a way to read the current value.
            val currentSelection = rememberUpdatedState(selection)

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspect)
                    .pointerInput(currentPage) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { pos ->
                                val scale = size.width / pageSize.width
                                val idx = nearestGlyphIndex(glyphs, pos, size.height.toFloat(), scale)
                                if (idx >= 0) onSelectionChange(PageSelection(idx, idx))
                            },
                            onDrag = { change, _ ->
                                val scale = size.width / pageSize.width
                                val idx = nearestGlyphIndex(glyphs, change.position, size.height.toFloat(), scale)
                                val sel = currentSelection.value
                                if (idx >= 0 && sel != null) {
                                    onSelectionChange(sel.copy(endGlyph = idx))
                                }
                            },
                        )
                    }
                    .pointerInput(currentPage) {
                        // A plain tap clears an active selection. Uses detectTapGestures rather
                        // than detectDragGestures: a sibling pointerInput that claims drag deltas
                        // (even just to ignore them) races the long-press-drag detector above on
                        // every move and starves its onDrag, freezing the selection at one glyph.
                        detectTapGestures(
                            onTap = { if (currentSelection.value != null) onSelectionChange(null) },
                        )
                    },
            ) {
                // The canvas is already sized to the ROTATED aspect ratio (see `aspect` above);
                // scale maps unrotated page-space units into that rotated frame along whichever
                // axis corresponds to the page's own width, then drawPdfPage rotates the canvas
                // to lay the unrotated content out correctly before painting it.
                val scale = if (rotated90) size.width / pageSize.height else size.width / pageSize.width
                drawPdfPage(
                    page = currentPage,
                    scale = scale,
                    heightPx = size.height,
                    widthPx = size.width,
                    rotateDegrees = pageSize.rotateDegrees,
                    pageWidthPx = pageSize.width * scale,
                    pageHeightPx = pageSize.height * scale,
                    searchHighlights = searchHighlights,
                    highlightColor = highlightColor,
                    currentMatchRect = currentMatchRect,
                    currentHighlightColor = currentHighlightColor,
                    selection = selection,
                    glyphs = glyphs,
                    selectionColor = selectionColor,
                )
            }

            // Known gap: unlike drawPdfPage, this block (selection handles/copy chip) and the
            // long-press-drag hit-testing above don't account for pageSize.rotateDegrees — a
            // rotated page with real selectable text would show handles in the wrong place. Not
            // fixed here since every rotated PDF seen in practice so far has been a scanned image
            // (no glyphs at all, so this block never runs for them).
            if (selection != null && glyphs.isNotEmpty()) {
                val scale = with(LocalDensity.current) { widthPx.toFloat() }.let { widthPx.toFloat() / pageSize.width }
                val lo = selection.lo.coerceIn(0, glyphs.size - 1)
                val hi = selection.hi.coerceIn(0, glyphs.size - 1)
                val startGlyph = glyphs[lo]
                val endGlyph = glyphs[hi]
                val heightPx = widthPx.toFloat() / aspect
                val startPx = Offset(startGlyph.x * scale, heightPx - startGlyph.y * scale)
                val endPx = Offset((endGlyph.x + endGlyph.advance) * scale, heightPx - endGlyph.y * scale)

                SelectionHandle(
                    color = handleColor,
                    offsetPx = startPx,
                    onDrag = { newPos ->
                        val idx = nearestGlyphIndex(glyphs, newPos, heightPx, scale)
                        if (idx >= 0) onSelectionChange(selection.copy(startGlyph = idx))
                    },
                )
                SelectionHandle(
                    color = handleColor,
                    offsetPx = endPx,
                    onDrag = { newPos ->
                        val idx = nearestGlyphIndex(glyphs, newPos, heightPx, scale)
                        if (idx >= 0) onSelectionChange(selection.copy(endGlyph = idx))
                    },
                )

                CopySelectionChip(
                    offsetPx = Offset((startPx.x + endPx.x) / 2f, minOf(startPx.y, endPx.y) - 48f),
                    onClick = {
                        val text = glyphs.subList(lo, hi + 1).joinToString("") { it.text }
                        onCopySelection(text)
                    },
                )
            }
        }
    }
}

/** Finds the glyph whose baseline-anchored box is closest to [posPx] (a canvas-space tap/drag
 * point), used both for starting/extending a drag selection and for dragging its handles. */
private fun nearestGlyphIndex(glyphs: List<PdfDrawOp.DrawGlyph>, posPx: Offset, heightPx: Float, scale: Float): Int {
    if (glyphs.isEmpty()) return -1
    var best = -1
    var bestDist = Float.MAX_VALUE
    for (i in glyphs.indices) {
        val g = glyphs[i]
        val cx = (g.x + g.advance / 2f) * scale
        val cy = heightPx - g.y * scale - (g.fontSize * scale) * 0.3f
        val d = (posPx.x - cx) * (posPx.x - cx) + (posPx.y - cy) * (posPx.y - cy)
        if (d < bestDist) {
            bestDist = d
            best = i
        }
    }
    return best
}

@Composable
private fun SelectionHandle(color: Color, offsetPx: Offset, onDrag: (Offset) -> Unit) {
    Box(
        modifier = Modifier
            .offset { IntOffset(offsetPx.x.toInt() - 12, offsetPx.y.toInt() - 4) }
            .size(24.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, _ -> onDrag(change.position + offsetPx) }
            }
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
private fun CopySelectionChip(offsetPx: Offset, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .offset { IntOffset(offsetPx.x.toInt() - 32, offsetPx.y.toInt().coerceAtLeast(0)) }
            .background(MaterialTheme.colorScheme.inverseSurface, MaterialTheme.shapes.small)
            .clip(MaterialTheme.shapes.small)
            .pointerInput(Unit) { detectDragGestures(onDragStart = { onClick() }) { _, _ -> } },
    ) {
        Text(
            text = stringResource(R.string.viewer_pdf_copy_selection),
            color = MaterialTheme.colorScheme.inverseOnSurface,
            modifier = Modifier.padding(horizontal = PvotSpacing.S, vertical = PvotSpacing.XS),
        )
    }
}

private fun DrawScope.drawPdfPage(
    page: PdfPage,
    scale: Float,
    heightPx: Float,
    widthPx: Float,
    rotateDegrees: Int,
    pageWidthPx: Float,
    pageHeightPx: Float,
    searchHighlights: List<PdfRect>,
    highlightColor: Color,
    currentMatchRect: PdfRect?,
    currentHighlightColor: Color,
    selection: PageSelection?,
    glyphs: List<PdfDrawOp.DrawGlyph>,
    selectionColor: Color,
) {
    fun toPxX(x: Float) = x * scale
    fun toPxY(y: Float) = pageHeightPx - y * scale

    drawIntoCanvas { canvas ->
        val nc = canvas.nativeCanvas
        var saveDepth = 0

        // Ops below are laid out in the page's own unrotated local frame (pageWidthPx x
        // pageHeightPx); /Rotate is a display-only transform the content stream knows nothing
        // about, so it's applied once here as a canvas rotation around the real canvas center,
        // translating the unrotated frame's center to line up with it. For rotateDegrees == 0
        // this is a no-op translate (pageWidthPx/pageHeightPx already equal widthPx/heightPx).
        nc.save()
        nc.translate(widthPx / 2f, heightPx / 2f)
        nc.rotate(rotateDegrees.toFloat())
        nc.translate(-pageWidthPx / 2f, -pageHeightPx / 2f)

        val fillPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
        val strokePaint = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE }
        val textPaint = Paint().apply { isAntiAlias = true }
        val imagePaint = Paint().apply { isAntiAlias = true; isFilterBitmap = true }
        val typefaceRegular = Typeface.DEFAULT
        val typefaceBold = Typeface.DEFAULT_BOLD
        val typefaceItalic = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
        val typefaceBoldItalic = Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
        var path = AndroidPath()
        var fillColor = android.graphics.Color.BLACK
        var strokeColor = android.graphics.Color.BLACK
        var lineWidthPx = 1f

        for (op in page.ops) {
            when (op) {
                is PdfDrawOp.SetFillColor -> fillColor = op.color.toArgb()
                is PdfDrawOp.SetStrokeColor -> strokeColor = op.color.toArgb()
                is PdfDrawOp.SetLineWidth -> lineWidthPx = op.width * scale
                is PdfDrawOp.MoveTo -> path.moveTo(toPxX(op.x), toPxY(op.y))
                is PdfDrawOp.LineTo -> path.lineTo(toPxX(op.x), toPxY(op.y))
                is PdfDrawOp.CurveTo -> path.cubicTo(
                    toPxX(op.x1), toPxY(op.y1), toPxX(op.x2), toPxY(op.y2), toPxX(op.x3), toPxY(op.y3),
                )
                is PdfDrawOp.CloseSubpath -> path.close()
                is PdfDrawOp.FillPath -> {
                    path.fillType = if (op.evenOdd) AndroidPath.FillType.EVEN_ODD else AndroidPath.FillType.WINDING
                    fillPaint.color = fillColor
                    nc.drawPath(path, fillPaint)
                    path = AndroidPath()
                }
                is PdfDrawOp.StrokePath -> {
                    strokePaint.color = strokeColor
                    strokePaint.strokeWidth = lineWidthPx.coerceAtLeast(1f)
                    nc.drawPath(path, strokePaint)
                    path = AndroidPath()
                }
                is PdfDrawOp.FillAndStroke -> {
                    path.fillType = if (op.evenOdd) AndroidPath.FillType.EVEN_ODD else AndroidPath.FillType.WINDING
                    fillPaint.color = fillColor
                    nc.drawPath(path, fillPaint)
                    strokePaint.color = strokeColor
                    strokePaint.strokeWidth = lineWidthPx.coerceAtLeast(1f)
                    nc.drawPath(path, strokePaint)
                    path = AndroidPath()
                }
                is PdfDrawOp.SetClipRect -> {
                    val left = toPxX(op.x)
                    val right = toPxX(op.x + op.w)
                    val top = toPxY(op.y + op.h)
                    val bottom = toPxY(op.y)
                    nc.clipRect(minOf(left, right), minOf(top, bottom), maxOf(left, right), maxOf(top, bottom))
                }
                is PdfDrawOp.EndPath -> path = AndroidPath()
                is PdfDrawOp.SaveState -> { nc.save(); saveDepth++ }
                is PdfDrawOp.RestoreState -> if (saveDepth > 0) { nc.restore(); saveDepth-- }
                is PdfDrawOp.DrawGlyph -> {
                    textPaint.color = fillColor
                    textPaint.textSize = (op.fontSize * scale).coerceAtLeast(1f)
                    textPaint.typeface = when {
                        op.bold && op.italic -> typefaceBoldItalic
                        op.bold -> typefaceBold
                        op.italic -> typefaceItalic
                        else -> typefaceRegular
                    }
                    // The PDF's own per-glyph advance (how far the *next* glyph starts) is based
                    // on the document's real, usually-unembedded font — it rarely matches the
                    // natural width of the same character in whatever system typeface we
                    // substitute. LaTeX's Computer Modern is a common, noticeably narrower
                    // example. Positioning each glyph correctly per PDF metrics but rendering it
                    // at the substitute font's own natural width left a visible gap after every
                    // letter; stretch/compress horizontally so the glyph actually fills the
                    // advance the PDF specified.
                    textPaint.textScaleX = 1f
                    val naturalWidth = textPaint.measureText(op.text)
                    val expectedWidth = op.advance * scale
                    if (naturalWidth > 0.5f && expectedWidth > 0f) {
                        textPaint.textScaleX = (expectedWidth / naturalWidth).coerceIn(0.5f, 2f)
                    }
                    nc.drawText(op.text, toPxX(op.x), toPxY(op.y), textPaint)
                }
                is PdfDrawOp.DrawImage -> {
                    val bitmap = page.imageBitmap(op.imageIndex)
                    val bw = bitmap?.width?.toFloat() ?: 0f
                    val bh = bitmap?.height?.toFloat() ?: 0f
                    if (bitmap != null && bw > 0f && bh > 0f) {
                        // Compose the content stream's own CTM (unit square -> page space) with
                        // our page-space -> device transform (scale + y-flip done by toPxX/toPxY
                        // elsewhere), then with bitmap-pixel -> unit-square, as one matrix. A
                        // rotated/sheared `cm` (routine for phone-scanned pages placing a
                        // landscape capture on a portrait page) needs that real transform —
                        // reducing it to an axis-aligned destination rect (as a plain
                        // src/dst-rect drawBitmap would) comes out an unrotated, squashed mess.
                        val bigA = scale * op.a
                        val bigB = -scale * op.b
                        val bigC = scale * op.c
                        val bigD = -scale * op.d
                        val bigE = scale * op.e
                        val bigF = pageHeightPx - scale * op.f
                        val imageMatrix = android.graphics.Matrix().apply {
                            setValues(floatArrayOf(
                                bigA / bw, -bigC / bh, bigC + bigE,
                                bigB / bw, -bigD / bh, bigD + bigF,
                                0f, 0f, 1f,
                            ))
                        }
                        nc.drawBitmap(bitmap, imageMatrix, imagePaint)
                    }
                }
            }
        }
        repeat(saveDepth) { nc.restore() }

        // Highlights draw last, on top of the page's own content (translucent, so the
        // underlying text stays legible) — drawing them first would just get painted over by
        // the page's own background fills, which are ordinary content ops like any other.
        for (rect in searchHighlights) {
            nc.drawRect(
                toPxX(rect.x), toPxY(rect.y + rect.height), toPxX(rect.x + rect.width), toPxY(rect.y),
                Paint().apply { color = highlightColor.copy(alpha = 0.4f).toArgb() },
            )
        }
        currentMatchRect?.let { rect ->
            nc.drawRect(
                toPxX(rect.x), toPxY(rect.y + rect.height), toPxX(rect.x + rect.width), toPxY(rect.y),
                Paint().apply { color = currentHighlightColor.copy(alpha = 0.55f).toArgb() },
            )
        }
        if (selection != null && glyphs.isNotEmpty()) {
            val lo = selection.lo.coerceIn(0, glyphs.size - 1)
            val hi = selection.hi.coerceIn(0, glyphs.size - 1)
            val selectionPaint = Paint().apply { color = selectionColor.toArgb() }
            for (i in lo..hi) {
                val g = glyphs[i]
                nc.drawRect(
                    toPxX(g.x), toPxY(g.y + g.fontSize * 0.8f), toPxX(g.x + g.advance), toPxY(g.y - g.fontSize * 0.25f),
                    selectionPaint,
                )
            }
        }
        nc.restore()
    }
}
