package org.prauga.documents.viewer

import android.content.ClipData
import android.net.Uri
import androidx.compose.animation.core.FloatExponentialDecaySpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.prauga.pvot.coreui.PvotErrorContent
import com.prauga.pvot.coreui.PvotLoadingContent
import com.prauga.pvot.coreui.UiState
import com.prauga.pvot.designsystem.components.button.PvotIconButton
import com.prauga.pvot.designsystem.components.textfield.PvotTextField
import com.prauga.pvot.designsystem.theme.PvotSpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.prauga.documents.R
import kotlin.math.abs
import kotlin.math.roundToInt

/** [uri]'s pages, rendered in-process by Pdfium (Google's PDF engine, the one in Chrome). Pages
 * render lazily at list width as they scroll into view; when zoomed in, the visible part of each
 * on-screen page is re-rendered at the zoomed resolution once the gesture settles, so text stays
 * sharp. In-process matters: androidx.pdf is Pdfium too, but behind a system service whose
 * per-page binder round-trip stuttered visibly on scroll. See `CLAUDE.md`. */
@Composable
fun PdfView(uri: Uri, topPadding: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var state by remember(uri) { mutableStateOf<UiState<PdfiumDocument>>(UiState.Loading) }
    var retryToken by remember(uri) { mutableIntStateOf(0) }
    val readFailedMessage = stringResource(R.string.viewer_read_failed)

    LaunchedEffect(uri, retryToken) {
        state = UiState.Loading
        state = try {
            UiState.Success(PdfiumDocument.open(context, uri))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UiState.Error(readFailedMessage, e)
        }
    }

    val opened = (state as? UiState.Success)?.data
    DisposableEffect(opened) {
        onDispose { opened?.close() }
    }

    when (val current = state) {
        is UiState.Loading -> Box(modifier = modifier, contentAlignment = Alignment.Center) { PvotLoadingContent() }
        is UiState.Error -> Box(modifier = modifier, contentAlignment = Alignment.Center) {
            PvotErrorContent(
                title = stringResource(R.string.viewer_read_failed),
                message = current.cause?.message.orEmpty(),
                onRetry = { retryToken++ },
            )
        }
        is UiState.Success -> PdfContent(document = current.data, topPadding = topPadding, modifier = modifier)
    }
}

/** A selection as Pdfium character indices on one page; [anchor] is where it started, so dragging
 * either way from there works. */
private data class PageSelection(val anchor: Int, val focus: Int) {
    val first get() = minOf(anchor, focus)
    val last get() = maxOf(anchor, focus)
}

@Composable
private fun PdfContent(document: PdfiumDocument, topPadding: Dp, modifier: Modifier) {
    val zoom = remember(document) { PdfZoomState() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val density = LocalDensity.current
    val clipLabel = stringResource(R.string.viewer_pdf_clip_label)

    var searchActive by remember(document) { mutableStateOf(false) }
    var searchQuery by remember(document) { mutableStateOf("") }
    var matches by remember(document) { mutableStateOf<List<SearchMatch>>(emptyList()) }
    var currentMatch by remember(document) { mutableIntStateOf(0) }
    val matchesByPage = remember(matches) { matches.withIndex().groupBy { it.value.pageIndex } }

    var selectionPage by remember(document) { mutableIntStateOf(-1) }
    var selection by remember(document) { mutableStateOf<PageSelection?>(null) }

    // Bumped once zoom and scroll have both been still for a moment: the cue for on-screen pages
    // to re-render their visible part at the current zoom. Waiting for the gesture to settle keeps
    // renders from piling up behind a pinch that's still moving.
    var viewportSettled by remember(document) { mutableIntStateOf(0) }
    LaunchedEffect(document) {
        snapshotFlow {
            listOf(
                zoom.scale, zoom.offsetX, zoom.offsetY,
                listState.firstVisibleItemIndex.toFloat(), listState.firstVisibleItemScrollOffset.toFloat(),
            )
        }.collectLatest {
            delay(HiResSettleMillis)
            viewportSettled++
        }
    }

    val pageHorizontalPadding = with(density) { (PvotSpacing.L * 2).toPx() }
    fun scrollToMatch(match: SearchMatch) {
        val info = listState.layoutInfo
        val pageWidth = info.viewportSize.width - pageHorizontalPadding
        val pageHeight = pageWidth / document.pageAspects[match.pageIndex]
        val offset = (match.rects.first().top * pageHeight - info.viewportSize.height / 4f)
            .roundToInt().coerceAtLeast(0)
        scope.launch { listState.animateScrollToItem(match.pageIndex, offset) }
    }

    LaunchedEffect(searchQuery, document) {
        if (searchQuery.isBlank()) {
            matches = emptyList()
            return@LaunchedEffect
        }
        delay(SearchDebounceMillis)
        val found = document.search(searchQuery)
        matches = found
        currentMatch = 0
        found.firstOrNull()?.let(::scrollToMatch)
    }

    Box(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                // The gesture sits outside graphicsLayer so it sees finger movement in screen
                // pixels. Inside the layer, movement arrives divided by the zoom factor, and a
                // page at 4x would only pan a quarter as far as the finger moved.
                .pinchZoom(zoom, listState, scope)
                // Block form, so zoom state is read in the draw phase: the parameter overload
                // reads it during composition and recomposed the whole list on every frame of
                // a pinch or pan.
                .graphicsLayer {
                    scaleX = zoom.scale
                    scaleY = zoom.scale
                    translationX = zoom.offsetX
                    translationY = zoom.offsetY
                },
            contentPadding = PaddingValues(
                top = topPadding + (if (searchActive) SearchBarHeight else PvotSpacing.M),
                start = PvotSpacing.L,
                end = PvotSpacing.L,
                bottom = PvotSpacing.M,
            ),
        ) {
            items(count = document.pageCount, key = { it }) { index ->
                val pageMatches = matchesByPage[index].orEmpty()
                PdfPageItem(
                    document = document,
                    index = index,
                    zoomScale = { zoom.scale },
                    viewportSettled = viewportSettled,
                    highlights = pageMatches.flatMap { it.value.rects },
                    currentHighlight = pageMatches.firstOrNull { it.index == currentMatch }?.value?.rects,
                    selection = selection.takeIf { selectionPage == index },
                    onSelectionChange = { sel ->
                        selectionPage = if (sel == null) -1 else index
                        selection = sel
                    },
                    onCopy = { text ->
                        scope.launch { clipboard.setClipEntry(ClipData.newPlainText(clipLabel, text).toClipEntry()) }
                        selectionPage = -1
                        selection = null
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
                        scrollToMatch(matches[currentMatch])
                    }
                },
                onNext = {
                    if (matches.isNotEmpty()) {
                        currentMatch = (currentMatch + 1) % matches.size
                        scrollToMatch(matches[currentMatch])
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
                icon = painterResource(R.drawable.ic_search),
                contentDescription = stringResource(R.string.viewer_pdf_search),
                onClick = { searchActive = true },
                modifier = Modifier
                    .padding(top = topPadding + PvotSpacing.S, end = PvotSpacing.L)
                    .align(Alignment.TopEnd),
            )
        }
    }
}

private val SearchBarHeight = 64.dp
private const val SearchDebounceMillis = 250L
private const val HiResSettleMillis = 150L

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
    Row(
        modifier = modifier
            .padding(top = topPadding + PvotSpacing.S, start = PvotSpacing.L, end = PvotSpacing.L)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f), MaterialTheme.shapes.large),
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
            icon = painterResource(R.drawable.ic_chevron_up),
            contentDescription = stringResource(R.string.viewer_pdf_search_previous),
            onClick = onPrevious,
            size = 36.dp,
        )
        PvotIconButton(
            icon = painterResource(R.drawable.ic_chevron_down),
            contentDescription = stringResource(R.string.viewer_pdf_search_next),
            onClick = onNext,
            size = 36.dp,
        )
        PvotIconButton(
            icon = painterResource(R.drawable.ic_x),
            contentDescription = stringResource(R.string.viewer_pdf_search_clear),
            onClick = onClose,
            size = 36.dp,
        )
    }
}

/** The whole document's zoom and pan — pinch scales the entire scrolling document in place, as a
 * PDF reader does, rather than one page inside its own frame. Offsets are in screen pixels. */
private class PdfZoomState {
    var scale by mutableFloatStateOf(1f)
    var offsetX by mutableFloatStateOf(0f)
    var offsetY by mutableFloatStateOf(0f)
}

private const val MaxZoom = 5f

/** Pinch zooms around the point between the fingers. Once zoomed in, a one-finger drag pans, and
 * any vertical movement past the edge of the zoomed page scrolls the document instead, so
 * reading on while zoomed doesn't stop at the bottom of the screen. Lifting with speed flings. At
 * 1x a one-finger drag is left alone for the list's own scrolling. */
private fun Modifier.pinchZoom(
    zoom: PdfZoomState,
    listState: LazyListState,
    scope: CoroutineScope,
): Modifier = pointerInput(Unit) {
    var fling: Job? = null

    fun pan(dx: Float, dy: Float) {
        val maxX = size.width * (zoom.scale - 1f) / 2f
        val maxY = size.height * (zoom.scale - 1f) / 2f
        zoom.offsetX = (zoom.offsetX + dx).coerceIn(-maxX, maxX)
        val wantedY = zoom.offsetY + dy
        zoom.offsetY = wantedY.coerceIn(-maxY, maxY)
        val overflow = wantedY - zoom.offsetY
        // The list scrolls in its own, unscaled units; on screen they're multiplied by the zoom.
        if (overflow != 0f) listState.dispatchRawDelta(-overflow / zoom.scale)
    }

    awaitEachGesture {
        fling?.cancel()
        val velocity = VelocityTracker()
        var panning = false
        var dragged = Offset.Zero
        do {
            val event = awaitPointerEvent()
            // A page's own long-press selection or a selection handle already took this one.
            if (event.changes.any { it.isConsumed }) continue
            val multiTouch = event.changes.count { it.pressed } >= 2
            if (!multiTouch && zoom.scale <= 1f) continue

            val pan = event.calculatePan()
            if (!multiTouch && !panning) {
                // Hold off until the finger has really moved, so a long press still reaches the
                // page underneath for text selection while zoomed in.
                dragged += pan
                if (dragged.getDistance() < viewConfiguration.touchSlop) continue
                panning = true
            }

            val zoomChange = if (multiTouch) event.calculateZoom() else 1f
            val oldScale = zoom.scale
            val newScale = (oldScale * zoomChange).coerceIn(1f, MaxZoom)
            if (newScale != oldScale) {
                // Keep the content under the fingers' centroid fixed while scaling about the
                // layer's centre (graphicsLayer's default transform origin).
                val centroid = event.calculateCentroid(useCurrent = true)
                val fromCentre = centroid - Offset(size.width / 2f, size.height / 2f)
                val ratio = newScale / oldScale
                zoom.offsetX = fromCentre.x - (fromCentre.x - zoom.offsetX) * ratio
                zoom.offsetY = fromCentre.y - (fromCentre.y - zoom.offsetY) * ratio
                zoom.scale = newScale
            }
            if (zoom.scale <= 1f) {
                zoom.offsetX = 0f
                zoom.offsetY = 0f
            } else {
                pan(pan.x, pan.y)
            }

            event.changes.firstOrNull { it.pressed }?.let { velocity.addPosition(it.uptimeMillis, it.position) }
            event.changes.forEach { if (it.positionChange() != Offset.Zero || multiTouch) it.consume() }
        } while (event.changes.any { it.pressed })

        if (panning && zoom.scale > 1f) {
            val v = velocity.calculateVelocity()
            if (abs(v.x) + abs(v.y) > MinFlingVelocity) {
                fling = scope.launch {
                    var lastX = 0f
                    var lastY = 0f
                    launch {
                        animateDecay(0f, v.x, FloatExponentialDecaySpec()) { value, _ ->
                            pan(value - lastX, 0f)
                            lastX = value
                        }
                    }
                    animateDecay(0f, v.y, FloatExponentialDecaySpec()) { value, _ ->
                        pan(0f, value - lastY)
                        lastY = value
                    }
                }
            }
        }
    }
}

private const val MinFlingVelocity = 300f

/** The current zoom's sharp re-render of the part of a page that's on screen, and where it goes
 * in the page's own (unzoomed) pixel space. */
private class HiResTile(val bounds: Rect, val image: ImageBitmap)

private class CoordinatesHolder {
    var value: LayoutCoordinates? = null
}

@Composable
private fun PdfPageItem(
    document: PdfiumDocument,
    index: Int,
    zoomScale: () -> Float,
    viewportSettled: Int,
    highlights: List<PageRect>,
    currentHighlight: List<PageRect>?,
    selection: PageSelection?,
    onSelectionChange: (PageSelection?) -> Unit,
    onCopy: (String) -> Unit,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var page by remember(index) { mutableStateOf<ImageBitmap?>(null) }
    var tile by remember(index) { mutableStateOf<HiResTile?>(null) }
    var text by remember(index) { mutableStateOf<PageText?>(null) }
    val coordinates = remember { CoordinatesHolder() }

    LaunchedEffect(index, size) {
        if (size.width <= 0 || size.height <= 0) return@LaunchedEffect
        tile = null
        page = (document.cachedPage(index, size.width) ?: document.renderPage(index, size.width, size.height))
            .asImageBitmap()
        text = document.pageText(index)
    }

    LaunchedEffect(viewportSettled, size, page != null) {
        val scale = zoomScale()
        val coords = coordinates.value
        if (scale <= 1.01f || page == null || coords == null || !coords.isAttached) {
            tile = null
            return@LaunchedEffect
        }
        val visible = coords.boundsInWindow()
        if (visible.isEmpty) {
            tile = null
            return@LaunchedEffect
        }
        val topLeft = coords.windowToLocal(visible.topLeft)
        val bottomRight = coords.windowToLocal(visible.bottomRight)
        val fullWidth = (size.width * scale).roundToInt()
        val fullHeight = (size.height * scale).roundToInt()
        val left = (topLeft.x * scale).roundToInt().coerceIn(0, fullWidth)
        val top = (topLeft.y * scale).roundToInt().coerceIn(0, fullHeight)
        val right = (bottomRight.x * scale).roundToInt().coerceIn(left, fullWidth)
        val bottom = (bottomRight.y * scale).roundToInt().coerceIn(top, fullHeight)
        if (right - left < 1 || bottom - top < 1) {
            tile = null
            return@LaunchedEffect
        }
        val image = document.renderRegion(index, fullWidth, fullHeight, left, top, right - left, bottom - top)
        tile = HiResTile(
            Rect(left / scale, top / scale, right / scale, bottom / scale),
            image.asImageBitmap(),
        )
    }

    val highlightColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.4f)
    val currentHighlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    val selectionColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
    val handleColor = MaterialTheme.colorScheme.primary
    // The gesture coroutines below outlive recompositions, so they read these through State
    // rather than capturing whatever value was current when they started.
    val currentText = rememberUpdatedState(text)
    val currentSelection = rememberUpdatedState(selection)

    Box(modifier = Modifier.fillMaxWidth().padding(bottom = PvotSpacing.S)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(document.pageAspects[index])
                // An unpainted PDF page is white; this also shows while the page renders.
                .background(Color.White)
                .onSizeChanged { size = it }
                .onGloballyPositioned { coordinates.value = it }
                .pointerInput(index) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { position ->
                            currentText.value?.charNearest(position, size)?.let {
                                onSelectionChange(PageSelection(it, it))
                            }
                        },
                        onDrag = { change, _ ->
                            val current = currentSelection.value ?: return@detectDragGesturesAfterLongPress
                            currentText.value?.charNearest(change.position, size)?.let {
                                onSelectionChange(current.copy(focus = it))
                            }
                        },
                    )
                }
                .pointerInput(index) {
                    detectTapGestures(onTap = { if (currentSelection.value != null) onSelectionChange(null) })
                },
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                page?.let { drawImage(it) }
                tile?.let {
                    drawImage(
                        image = it.image,
                        dstOffset = IntOffset(it.bounds.left.roundToInt(), it.bounds.top.roundToInt()),
                        dstSize = IntSize(it.bounds.width.roundToInt(), it.bounds.height.roundToInt()),
                    )
                }
                for (r in highlights) drawRect(highlightColor, r.topLeft(this.size), r.size(this.size))
                currentHighlight?.forEach { r -> drawRect(currentHighlightColor, r.topLeft(this.size), r.size(this.size)) }
                val pageText = text
                if (selection != null && pageText != null) {
                    for (line in pageText.lineRects(selection.first, selection.last)) {
                        drawRect(selectionColor, line.topLeft(this.size), line.size(this.size))
                    }
                }
            }

            val pageText = text
            if (selection != null && pageText != null && size.width > 0) {
                val start = pageText.boxAtOrAfter(selection.first)
                val end = pageText.boxAtOrBefore(selection.last)
                if (start != null && end != null) {
                    val startPx = Offset(start.left * size.width, start.bottom * size.height)
                    val endPx = Offset(end.right * size.width, end.bottom * size.height)
                    SelectionHandle(handleColor, startPx) { position ->
                        pageText.charNearest(position, size)?.let { onSelectionChange(selection.copy(anchor = it)) }
                    }
                    SelectionHandle(handleColor, endPx) { position ->
                        pageText.charNearest(position, size)?.let { onSelectionChange(selection.copy(focus = it)) }
                    }
                    CopySelectionChip(
                        offsetPx = Offset(startPx.x, start.top * size.height),
                        onClick = { onCopy(pageText.copyText(selection.first, selection.last)) },
                    )
                }
            }
        }
    }
}

private fun PageRect.topLeft(size: Size) = Offset(left * size.width, top * size.height)

private fun PageRect.size(size: Size) = Size((right - left) * size.width, (bottom - top) * size.height)

/** The character whose box is closest to [position] (page-local pixels). Distance to a box is
 * zero inside it, and vertical distance counts double so a touch between two lines picks the
 * line the finger is actually on rather than the nearest letter on the line above. */
private fun PageText.charNearest(position: Offset, size: IntSize): Int? {
    if (size.width <= 0) return null
    var best: Int? = null
    var bestDistance = Float.MAX_VALUE
    boxes.forEachIndexed { i, box ->
        if (box == null) return@forEachIndexed
        val x = position.x / size.width
        val y = position.y / size.height
        val dx = (if (x < box.left) box.left - x else if (x > box.right) x - box.right else 0f) * size.width
        val dy = (if (y < box.top) box.top - y else if (y > box.bottom) y - box.bottom else 0f) * size.height * 2
        val distance = dx * dx + dy * dy
        if (distance < bestDistance) {
            bestDistance = distance
            best = i
        }
    }
    return best
}

private fun PageText.boxAtOrAfter(index: Int): PageRect? =
    (index until boxes.size).firstNotNullOfOrNull { boxes[it] }

private fun PageText.boxAtOrBefore(index: Int): PageRect? =
    (index downTo 0).firstNotNullOfOrNull { boxes.getOrNull(it) }

/** One rectangle per line of the selection, merging each line's character boxes, rather than a
 * rectangle per character (which leaves gaps between letters and over the spaces). */
private fun PageText.lineRects(first: Int, last: Int): List<PageRect> {
    val lines = ArrayList<PageRect>()
    var current: PageRect? = null
    for (i in first..last.coerceAtMost(boxes.lastIndex)) {
        val box = boxes[i] ?: continue
        val line = current
        val centre = (box.top + box.bottom) / 2f
        current = if (line != null && centre >= line.top && centre <= line.bottom) {
            PageRect(minOf(line.left, box.left), minOf(line.top, box.top), maxOf(line.right, box.right), maxOf(line.bottom, box.bottom))
        } else {
            line?.let(lines::add)
            box
        }
    }
    current?.let(lines::add)
    return lines
}

private fun PageText.copyText(first: Int, last: Int): String =
    text.substring(first.coerceIn(0, text.length), (last + 1).coerceIn(0, text.length))
        .replace("\r\n", "\n")
        .trim()

@Composable
private fun SelectionHandle(color: Color, offsetPx: Offset, onDrag: (Offset) -> Unit) {
    val handleSize = 24.dp
    val half = with(LocalDensity.current) { (handleSize / 2).roundToPx() }
    // The handle moves as the selection changes; keying the gesture on its position would restart
    // it, and drop the drag, on every move.
    val currentOffset = rememberUpdatedState(offsetPx)
    val currentOnDrag = rememberUpdatedState(onDrag)
    Box(
        modifier = Modifier
            .offset { IntOffset(offsetPx.x.roundToInt() - half, offsetPx.y.roundToInt()) }
            .size(handleSize)
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    currentOnDrag.value(currentOffset.value + change.position - Offset(half.toFloat(), 0f))
                }
            }
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
private fun CopySelectionChip(offsetPx: Offset, onClick: () -> Unit) {
    val lift = with(LocalDensity.current) { 40.dp.roundToPx() }
    Box(
        modifier = Modifier
            .offset { IntOffset(offsetPx.x.roundToInt(), (offsetPx.y.roundToInt() - lift).coerceAtLeast(0)) }
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.inverseSurface)
            .clickable(onClick = onClick),
    ) {
        Text(
            text = stringResource(R.string.viewer_pdf_copy_selection),
            color = MaterialTheme.colorScheme.inverseOnSurface,
            modifier = Modifier.padding(horizontal = PvotSpacing.S, vertical = PvotSpacing.XS),
        )
    }
}
