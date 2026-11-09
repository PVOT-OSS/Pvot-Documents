package org.prauga.documents.viewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.animateScrollBy
import android.content.ClipData
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.res.Configuration
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.layout.PinnableContainer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FloatExponentialDecaySpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import com.prauga.pvot.designsystem.motion.PvotDurations
import com.prauga.pvot.designsystem.motion.PvotMotion
import org.prauga.documents.ReadingPosition
import androidx.compose.animation.core.animateDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.prauga.pvot.coreui.PvotErrorContent
import com.prauga.pvot.coreui.PvotLoadingContent
import com.prauga.pvot.coreui.UiState
import com.prauga.pvot.designsystem.theme.PvotSpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
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
fun PdfView(
    uri: Uri,
    topPadding: Dp,
    search: PdfSearchState,
    navigation: PdfNavigationState,
    reading: PdfReadingSettings,
    selection: PdfSelectionState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var state by remember(uri) { mutableStateOf<UiState<PdfiumDocument>>(UiState.Loading) }
    var retryToken by remember(uri) { mutableIntStateOf(0) }
    // The password the user typed, kept only while this document is open.
    var password by remember(uri) { mutableStateOf<String?>(null) }
    // Non-null while the document is waiting for a password; true once one has been tried and failed.
    var passwordPrompt by remember(uri) { mutableStateOf<Boolean?>(null) }
    val readFailedMessage = stringResource(R.string.viewer_read_failed)

    LaunchedEffect(uri, retryToken, password) {
        state = UiState.Loading
        state = try {
            UiState.Success(PdfiumDocument.open(context, uri, password)).also { passwordPrompt = null }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PdfPasswordRequiredException) {
            passwordPrompt = e.passwordWasGiven
            UiState.Loading
        } catch (e: Exception) {
            UiState.Error(readFailedMessage, e)
        }
    }

    val opened = (state as? UiState.Success)?.data
    DisposableEffect(opened) {
        onDispose { opened?.close() }
    }
    // Give cached renders and decoded pages back when the system is short of memory or the app
    // goes to the background; a large scanned PDF can otherwise hold a few hundred megabytes.
    DisposableEffect(opened, context) {
        val callbacks = object : ComponentCallbacks2 {
            // From "running low" up: memory short while in use, or the app has been left. The
            // running levels are deprecated (newer Android sends only the hidden and background
            // ones), but older versions still send them.
            @Suppress("DEPRECATION")
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) opened?.trimMemory()
            }

            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            @Deprecated("Superseded by onTrimMemory")
            override fun onLowMemory() {
                opened?.trimMemory()
            }
        }
        context.registerComponentCallbacks(callbacks)
        onDispose { context.unregisterComponentCallbacks(callbacks) }
    }

    val prompt = passwordPrompt
    if (prompt != null) {
        PdfPasswordPrompt(
            wrongPassword = prompt,
            attempt = retryToken,
            // Bumping the token retries even when the same password is entered again.
            onSubmit = {
                password = it
                retryToken++
            },
            modifier = modifier.padding(top = topPadding),
        )
        return
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
        is UiState.Success -> PdfContent(
            document = current.data,
            topPadding = topPadding,
            search = search,
            navigation = navigation,
            reading = reading,
            selectionState = selection,
            modifier = modifier,
        )
    }
}

@Composable
private fun PdfContent(
    document: PdfiumDocument,
    topPadding: Dp,
    search: PdfSearchState,
    navigation: PdfNavigationState,
    reading: PdfReadingSettings,
    selectionState: PdfSelectionState,
    modifier: Modifier,
) {
    val zoom = remember(document) { PdfZoomState() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val density = LocalDensity.current
    val clipLabel = stringResource(R.string.viewer_pdf_clip_label)

    var matches by remember(document) { mutableStateOf<List<SearchMatch>>(emptyList()) }
    val currentMatch = search.currentMatch
    val matchesByPage = remember(matches) { matches.withIndex().groupBy { it.value.pageIndex } }

    var selection by remember(document) { mutableStateOf<DocSelection?>(null) }
    // The pages on screen, to find which page and character a finger is over: a selection drag
    // or a handle can move from one page onto the next.
    val pages = remember(document) { PageRegistry() }
    // The word a long press landed on, which a drag from that press extends from.
    var pressedWord by remember(document) { mutableStateOf<DocSelection?>(null) }

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

    // In landscape the camera cutout, and a three-button navigation bar, sit at the sides; pages
    // keep clear of them as well as the usual margin.
    val layoutDirection = LocalLayoutDirection.current
    val sideInsets = WindowInsets.displayCutout.union(WindowInsets.navigationBars)
        .only(WindowInsetsSides.Horizontal).asPaddingValues()
    val pageStart = PvotSpacing.L + sideInsets.calculateStartPadding(layoutDirection)
    val pageEnd = PvotSpacing.L + sideInsets.calculateEndPadding(layoutDirection)
    val pageStartPx = with(density) { pageStart.toPx() }
    val pageEndPx = with(density) { pageEnd.toPx() }
    val pageGapPx = with(density) { PvotSpacing.S.toPx() }
    var scrollJob by remember { mutableStateOf<Job?>(null) }

    /** Scrolls so the point at [yFraction] down page [pageIndex] lands [placeAt] of the way down
     * the visible area (below the top bar, above the search bar when it's open), at the current
     * zoom. With [xFraction], a zoomed-in view also pans sideways to bring that point on screen.
     * Measured from the live layout, so the keyboard or the search bar changing the viewport
     * doesn't throw it off. */
    fun scrollToPagePoint(pageIndex: Int, yFraction: Float, placeAt: Float, xFraction: Float? = null) {
        scrollJob?.cancel()
        scrollJob = scope.launch {
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == pageIndex }) {
                listState.scrollToItem(pageIndex)
            }
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == pageIndex } ?: return@launch
            val width = info.viewportSize.width.toFloat()
            val height = info.viewportSize.height.toFloat()
            val pageWidth = width - pageStartPx - pageEndPx
            // List coordinates: from the top-left of the list's own (unzoomed) box.
            val pointY = item.offset - info.viewportStartOffset + yFraction * item.size
            // Screen = (list - centre) * scale + centre + offset; graphicsLayer zooms about the centre.
            val s = zoom.scale
            val visibleTop = info.beforeContentPadding.toFloat()
            // The search bar, riding on the keyboard, floats over the bottom of the list.
            val barHeight = if (search.active) search.barHeightPx else 0
            val visibleBottom = (height - barHeight).coerceAtLeast(visibleTop + 1f)
            val targetY = visibleTop + (visibleBottom - visibleTop) * placeAt
            if (s > 1f && xFraction != null) {
                val pointX = pageStartPx + xFraction * pageWidth
                val maxX = width * (s - 1f) / 2f
                zoom.offsetX = (-(pointX - width / 2f) * s).coerceIn(-maxX, maxX)
            }
            val delta = pointY - height / 2f - (targetY - height / 2f - zoom.offsetY) / s
            listState.animateScrollBy(delta)
        }
    }

    /** Brings [match] a third of the way down the visible area, where the eye lands first. */
    fun scrollToMatch(match: SearchMatch) {
        val rect = match.rects.first()
        scrollToPagePoint(
            pageIndex = match.pageIndex,
            yFraction = (rect.top + rect.bottom) / 2f,
            placeAt = 1f / 3f,
            xFraction = (rect.left + rect.right) / 2f,
        )
    }

    LaunchedEffect(search.query, document) {
        val query = search.query
        if (query.isBlank()) {
            matches = emptyList()
            search.matchCount = 0
            return@LaunchedEffect
        }
        delay(SearchDebounceMillis)
        val found = document.search(query)
        matches = found
        search.matchCount = found.size
        search.currentMatch = 0
        found.firstOrNull()?.let(::scrollToMatch)
    }
    DisposableEffect(search, document) {
        search.available = true
        search.step = { delta ->
            if (matches.isNotEmpty()) {
                search.currentMatch = Math.floorMod(search.currentMatch + delta, matches.size)
                scrollToMatch(matches[search.currentMatch])
            }
        }
        onDispose {
            search.available = false
            search.step = {}
        }
    }

    DisposableEffect(navigation, document) {
        navigation.pageCount = document.pageCount
        navigation.currentPage = 0
        navigation.goTo = { page -> scrollToPagePoint(page, yFraction = 0f, placeAt = 0f) }
        onDispose {
            navigation.pageCount = 0
            navigation.outline = emptyList()
            navigation.indicatorVisible = false
            navigation.goTo = {}
        }
    }
    LaunchedEffect(navigation, document) {
        navigation.outline = document.outline()
    }
    // The page on screen is the one under the middle of the viewport; at the very end of the
    // document, where a short last page may never reach the middle, it's the last page.
    LaunchedEffect(navigation, document) {
        snapshotFlow {
            val info = listState.layoutInfo
            val items = info.visibleItemsInfo
            if (items.isEmpty()) {
                0
            } else if (!listState.canScrollForward && zoom.scale <= 1f) {
                items.last().index
            } else {
                val centre = info.viewportSize.height / 2f
                // The list point under the screen's centre, undoing the zoom about that centre.
                val listY = centre - zoom.offsetY / zoom.scale
                (items.firstOrNull { item ->
                    val top = item.offset - info.viewportStartOffset
                    listY >= top && listY < top + item.size + pageGapPx
                } ?: items.first()).index
            }
        }.collect { navigation.currentPage = it }
    }
    // The indicator shows while the document moves and for a moment after, including on open.
    LaunchedEffect(navigation, document) {
        snapshotFlow {
            listOf(listState.firstVisibleItemIndex.toFloat(), listState.firstVisibleItemScrollOffset.toFloat(), zoom.offsetY)
        }.collectLatest {
            navigation.indicatorVisible = true
            delay(PageIndicatorHideMillis)
            navigation.indicatorVisible = false
        }
    }

    // When the width changes (rotation, split screen), pages change height, and a scroll offset
    // kept in pixels would land somewhere else on the page. So the place is kept as a fraction of
    // the page and put back once the list has been laid out at the new width. Zoom offsets are
    // in pixels too, so zoom goes back to the whole page.
    LaunchedEffect(document) {
        var lastWidth = 0
        var anchorPage = 0
        var anchorFraction = 0f
        snapshotFlow { Triple(listState.layoutInfo.viewportSize.width, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
            .collect { (width, index, offset) ->
                if (width == 0) return@collect
                if (lastWidth != 0 && width != lastWidth) {
                    lastWidth = width
                    zoom.scale = 1f
                    zoom.offsetX = 0f
                    zoom.offsetY = 0f
                    // The old pixel offset may have carried the list past the anchor page, so go
                    // to it first; its new height is known once it has been laid out.
                    listState.scrollToItem(anchorPage)
                    val size = withTimeoutOrNull(RestoreLayoutTimeoutMillis) {
                        snapshotFlow { listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == anchorPage }?.size }
                            .filterNotNull()
                            .first()
                    } ?: 0
                    listState.scrollToItem(anchorPage, (anchorFraction * size).roundToInt())
                } else {
                    lastWidth = width
                    val size = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.size ?: return@collect
                    anchorPage = index
                    anchorFraction = offset.toFloat() / size.coerceAtLeast(1)
                }
            }
    }

    // Reopening a document picks up where reading stopped. Nothing is saved until the old
    // position has been restored, so the first frame at page 1 can't overwrite it.
    var positionRestored by remember(document) { mutableStateOf(false) }
    LaunchedEffect(document) {
        reading.initialPosition?.takeIf { it.page in 0 until document.pageCount }?.let { position ->
            listState.scrollToItem(position.page)
            // The offset is a fraction of the page's height, known once the page has been laid out.
            val size = withTimeoutOrNull(RestoreLayoutTimeoutMillis) {
                snapshotFlow { listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == position.page }?.size }
                    .filterNotNull()
                    .first()
            } ?: 0
            listState.scrollToItem(position.page, (position.offsetFraction * size).roundToInt())
        }
        positionRestored = true
    }
    fun currentPosition(): ReadingPosition? {
        if (!positionRestored) return null
        val first = listState.layoutInfo.visibleItemsInfo.firstOrNull() ?: return null
        val offset = listState.firstVisibleItemScrollOffset.toFloat() / first.size.coerceAtLeast(1)
        return ReadingPosition(listState.firstVisibleItemIndex, offset.coerceIn(0f, 1f))
    }
    val savePosition = rememberUpdatedState(reading.onPositionChange)
    LaunchedEffect(document) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collectLatest {
                delay(SavePositionMillis)
                currentPosition()?.let(savePosition.value)
            }
    }
    DisposableEffect(document) {
        onDispose { currentPosition()?.let(savePosition.value) }
    }

    // The list's box on screen, before the zoom transform, to place double taps in.
    val listCoordinates = remember { CoordinatesHolder() }
    var zoomJob by remember { mutableStateOf<Job?>(null) }

    /** Double tap: zoom in around the tapped point, or back out to the whole page if zoomed. */
    fun toggleZoomAt(windowPosition: Offset) {
        val coords = listCoordinates.value?.takeIf { it.isAttached } ?: return
        val point = coords.windowToLocal(windowPosition)
        val width = coords.size.width.toFloat()
        val height = coords.size.height.toFloat()
        val fromCentre = point - Offset(width / 2f, height / 2f)
        val startScale = zoom.scale
        val startX = zoom.offsetX
        val startY = zoom.offsetY
        val target = if (startScale > 1.01f) 1f else DoubleTapZoom
        zoomJob?.cancel()
        zoomJob = scope.launch {
            animate(startScale, target, animationSpec = tween(PvotDurations.Medium, easing = PvotMotion.Settle)) { value, _ ->
                // Same anchoring as a pinch: the tapped point stays under the finger.
                val ratio = value / startScale
                val maxX = width * (value - 1f) / 2f
                val maxY = height * (value - 1f) / 2f
                zoom.scale = value
                zoom.offsetX = (fromCentre.x - (fromCentre.x - startX) * ratio).coerceIn(-maxX, maxX)
                zoom.offsetY = (fromCentre.y - (fromCentre.y - startY) * ratio).coerceIn(-maxY, maxY)
            }
        }
    }

    // Auto-scroll while a selection drag is held near the top or bottom of the visible document:
    // the finger can't reach text that's off screen, so the document comes to it. While it
    // scrolls, the selection keeps following whatever is now under the still finger.
    var dragFinger by remember(document) { mutableStateOf<Offset?>(null) }
    val dragApply = remember(document) { DragApply() }
    val topPaddingPx = with(density) { topPadding.toPx() }
    val edgeZonePx = with(density) { AutoScrollEdge.toPx() }
    val maxScrollPx = with(density) { AutoScrollMaxSpeed.toPx() }

    fun dragMoved(windowPosition: Offset, apply: (Offset) -> Unit) {
        dragApply.apply = apply
        dragFinger = windowPosition
        apply(windowPosition)
    }

    fun dragEnded() {
        dragFinger = null
    }

    LaunchedEffect(dragFinger != null) {
        if (dragFinger == null) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val seconds = (now - last) / 1_000_000_000f
            last = now
            val finger = dragFinger ?: break
            val coords = listCoordinates.value?.takeIf { it.isAttached } ?: continue
            // The visible document: below the top bar, above the selection bar.
            val top = coords.localToWindow(Offset.Zero).y + topPaddingPx
            val bottom = coords.localToWindow(Offset(0f, coords.size.height.toFloat())).y - selectionState.barHeightPx
            val depth = when {
                finger.y < top + edgeZonePx -> -((top + edgeZonePx - finger.y) / edgeZonePx)
                finger.y > bottom - edgeZonePx -> (finger.y - (bottom - edgeZonePx)) / edgeZonePx
                else -> 0f
            }.coerceIn(-1f, 1f)
            if (depth != 0f) {
                // Faster the deeper into the edge; the list scrolls in unzoomed units.
                val scrolled = listState.dispatchRawDelta(depth * maxScrollPx * seconds / zoom.scale)
                if (scrolled != 0f) dragApply.apply(finger)
            }
        }
    }

    val context = LocalContext.current
    val shareTitle = stringResource(R.string.viewer_pdf_share_selection)

    val truncatedMessage = pluralStringResource(R.plurals.viewer_pdf_selection_truncated, SelectionTextLimit, SelectionTextLimit)

    /** The selected text, read page by page (Pdfium text for pages not on screen is loaded as
     * needed), with a line break between pages, and whether it was cut short. It stops at
     * [SelectionTextLimit] characters: the clipboard and the share sheet both go through a
     * size-limited binder transaction (10 MB of text from a 2,000-page Select all crashed the app),
     * and stopping early also saves reading every page of a long document. */
    suspend fun selectedText(sel: DocSelection): Pair<String, Boolean> {
        val text = StringBuilder()
        var truncated = false
        for (page in sel.start.page..sel.end.page) {
            val pageText = document.pageText(page)
            val range = sel.rangeOn(page, pageText.text.lastIndex) ?: continue
            if (text.isNotEmpty()) text.append('\n')
            text.append(pageText.text, range.first, (range.last + 1).coerceAtMost(pageText.text.length))
            if (text.length >= SelectionTextLimit) {
                truncated = text.length > SelectionTextLimit || page < sel.end.page
                text.setLength(SelectionTextLimit)
                break
            }
        }
        return text.toString().replace("\r\n", "\n").trim() to truncated
    }

    DisposableEffect(selectionState, document) {
        selectionState.onCopy = {
            selection?.let { sel ->
                scope.launch {
                    val (text, truncated) = selectedText(sel)
                    clipboard.setClipEntry(ClipData.newPlainText(clipLabel, text).toClipEntry())
                    selection = null
                    if (truncated) selectionState.notice = truncatedMessage
                }
            }
        }
        selectionState.onShare = {
            selection?.let { sel ->
                scope.launch {
                    val (text, truncated) = selectedText(sel)
                    if (truncated) selectionState.notice = truncatedMessage
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    context.startActivity(Intent.createChooser(send, shareTitle))
                    selection = null
                }
            }
        }
        selectionState.onSearch = {
            selection?.let { sel ->
                scope.launch {
                    // A search query is one line of text: the PDF keeps line breaks between lines,
                    // so a query joining two lines would never match. The first line it is.
                    val query = selectedText(sel).first.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
                        .orEmpty().take(SearchFromSelectionLimit).trim()
                    selection = null
                    if (query.isNotEmpty()) {
                        search.query = query
                        search.open()
                    }
                }
            }
        }
        selectionState.onSelectAll = {
            scope.launch {
                val lastPage = document.pageCount - 1
                val lastChar = document.pageText(lastPage).text.lastIndex.coerceAtLeast(0)
                selection = DocSelection(TextPos(0, 0), TextPos(lastPage, lastChar))
            }
        }
        onDispose {
            selectionState.active = false
            selectionState.onCopy = {}
            selectionState.onShare = {}
            selectionState.onSearch = {}
            selectionState.onSelectAll = {}
        }
    }
    LaunchedEffect(selection) { selectionState.active = selection != null }
    BackHandler(enabled = selection != null) { selection = null }

    var pendingLink by remember(document) { mutableStateOf<String?>(null) }
    pendingLink?.let { link -> OpenLinkDialog(link, onDismiss = { pendingLink = null }) }

    Box(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { listCoordinates.value = it }
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
                top = topPadding + PvotSpacing.M,
                start = pageStart,
                end = pageEnd,
                // Room to scroll the last page clear of the search bar.
                bottom = if (search.active) SearchBarClearance else PvotSpacing.M,
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
                    selection = selection,
                    pages = pages,
                    onLongPress = { windowPosition ->
                        pages.charAt(windowPosition, onlyPage = index, skipWhitespace = true)?.let { pos ->
                            val word = pages.text(index)?.wordAround(pos.char) ?: return@let
                            val sel = DocSelection(TextPos(index, word.first), TextPos(index, word.last))
                            pressedWord = sel
                            selection = sel
                        }
                    },
                    onLongPressDrag = { windowPosition ->
                        val word = pressedWord
                        if (word != null && selection != null) {
                            dragMoved(windowPosition) { finger ->
                                pages.charAt(finger)?.let { pos ->
                                    // Dragging back past the pressed word keeps all of that word,
                                    // by anchoring on its far end.
                                    selection = if (pos < word.start) DocSelection(word.end, pos)
                                    else DocSelection(word.start, maxOf(pos, word.end))
                                }
                            }
                        }
                    },
                    onLongPressEnd = ::dragEnded,
                    onClearSelection = { selection = null },
                    pageFilter = reading.pageFilter,
                    onDoubleTap = ::toggleZoomAt,
                    onLinkTap = { link ->
                        val dest = link.destPage
                        if (dest != null) navigation.goToPage(dest) else pendingLink = link.uri
                    },
                )
            }
        }
        selection?.let { sel ->
            // Handles sit over the list rather than inside a page, so dragging one across a page
            // boundary doesn't drop it when the page it started on stops holding that end.
            // State, not a plain holder: the handles can only be placed once the overlay has been
            // laid out, and that has to bring them back into composition.
            var overlay by remember { mutableStateOf<LayoutCoordinates?>(null) }
            val handleColor = MaterialTheme.colorScheme.primary
            Box(Modifier.matchParentSize().onGloballyPositioned { overlay = it }) {
                SelectionHandle(
                    color = handleColor,
                    position = { pages.handlePosition(sel.start, start = true, overlay, scrollTick(listState, zoom)) },
                    onDrag = { windowPosition ->
                        val fixed = sel.end
                        dragMoved(windowPosition) { finger -> pages.charAt(finger)?.let { selection = DocSelection(fixed, it) } }
                    },
                    onDragEnd = ::dragEnded,
                    toWindow = { overlay?.takeIf { c -> c.isAttached }?.localToWindow(it) },
                )
                SelectionHandle(
                    color = handleColor,
                    position = { pages.handlePosition(sel.end, start = false, overlay, scrollTick(listState, zoom)) },
                    onDrag = { windowPosition ->
                        val fixed = sel.start
                        dragMoved(windowPosition) { finger -> pages.charAt(finger)?.let { selection = DocSelection(fixed, it) } }
                    },
                    onDragEnd = ::dragEnded,
                    toWindow = { overlay?.takeIf { c -> c.isAttached }?.localToWindow(it) },
                )
            }
        }
        AnimatedVisibility(
            visible = navigation.overviewOpen,
            enter = fadeIn(tween(PvotDurations.Medium, easing = PvotMotion.Settle)),
            exit = fadeOut(tween(PvotDurations.Short)),
        ) {
            PdfPageOverview(
                document = document,
                currentPage = navigation.currentPage,
                topPadding = topPadding,
                pageFilter = reading.pageFilter,
                onPick = { page ->
                    navigation.overviewOpen = false
                    navigation.goToPage(page)
                },
                onClose = { navigation.overviewOpen = false },
            )
        }
    }
}

/** How the reader wants PDFs shown, from the app's preferences: where to start reading, where
 * reading stopped (to remember), and the dark-pages filter, null for normal pages. */
class PdfReadingSettings(
    val initialPosition: ReadingPosition?,
    val onPositionChange: (ReadingPosition) -> Unit,
    val pageFilter: ColorFilter?,
)

/** Inverts lightness while keeping hue (an inversion followed by a 180° hue turn), so black text
 * on white turns light on black but red stays red and blue stays blue. Each row sums to -1 on the
 * colour channels with an offset of 255: white maps to black and black to white. */
internal val DarkPageFilter = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            0.574f, -1.430f, -0.144f, 0f, 255f,
            -0.426f, -0.430f, -0.144f, 0f, 255f,
            -0.426f, -1.430f, 0.856f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        )
    )
)

/** The colour of a page before (or under) its render: paper white, or black with dark pages. */
internal fun pagePaper(dark: Boolean): Color = if (dark) Color.Black else Color.White

private const val DoubleTapZoom = 2.5f
private const val SavePositionMillis = 1000L
private const val SelectionTextLimit = 100_000
/** How close to the top or bottom of the visible document a selection drag starts scrolling. */
private val AutoScrollEdge = 56.dp
/** Auto-scroll speed per second with the finger right at the edge. */
private val AutoScrollMaxSpeed = 1200.dp
private const val SearchFromSelectionLimit = 100
private const val RestoreLayoutTimeoutMillis = 1000L

private val SearchBarClearance = 96.dp
private const val SearchDebounceMillis = 250L
private const val HiResSettleMillis = 150L
private const val PageIndicatorHideMillis = 1500L

/** The whole document's zoom and pan — pinch scales the entire scrolling document in place, as a
 * PDF reader does, rather than one page inside its own frame. Offsets are in screen pixels. */
internal class PdfZoomState {
    var scale by mutableFloatStateOf(1f)
    var offsetX by mutableFloatStateOf(0f)
    var offsetY by mutableFloatStateOf(0f)
}

private const val MaxZoom = 5f

/** Pinch zooms around the point between the fingers. Once zoomed in, a one-finger drag pans, and
 * any vertical movement past the edge of the zoomed page scrolls the document instead, so
 * reading on while zoomed doesn't stop at the bottom of the screen. Lifting with speed flings. At
 * 1x a one-finger drag is left alone for the list's own scrolling. */
internal fun Modifier.pinchZoom(
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

private class BooleanHolder {
    var value = false
}

/** The current drag's "apply the selection at this finger position", re-run as auto-scroll moves
 * the text under a finger that's holding still. */
private class DragApply {
    var apply: (Offset) -> Unit = {}
}

/** Keeps a lazy list item composed while pinned, however far it scrolls away. */
private class PinHolder {
    private var handle: PinnableContainer.PinnedHandle? = null

    fun pin(container: PinnableContainer?) {
        if (handle == null) handle = container?.pin()
    }

    fun release() {
        handle?.release()
        handle = null
    }
}

@Composable
private fun PdfPageItem(
    document: PdfiumDocument,
    index: Int,
    zoomScale: () -> Float,
    viewportSettled: Int,
    highlights: List<PageRect>,
    currentHighlight: List<PageRect>?,
    selection: DocSelection?,
    pages: PageRegistry,
    onLongPress: (windowPosition: Offset) -> Unit,
    onLongPressDrag: (windowPosition: Offset) -> Unit,
    onLongPressEnd: () -> Unit,
    onClearSelection: () -> Unit,
    pageFilter: ColorFilter?,
    onDoubleTap: (windowPosition: Offset) -> Unit,
    onLinkTap: (PageLink) -> Unit,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var page by remember(index) { mutableStateOf<ImageBitmap?>(null) }
    var tile by remember(index) { mutableStateOf<HiResTile?>(null) }
    var text by remember(index) { mutableStateOf<PageText?>(null) }
    var links by remember(index) { mutableStateOf<List<PageLink>>(emptyList()) }
    val coordinates = remember { CoordinatesHolder() }

    LaunchedEffect(index, size) {
        if (size.width <= 0 || size.height <= 0) return@LaunchedEffect
        tile = null
        page = (document.cachedPage(index, size.width) ?: document.renderPage(index, size.width, size.height))
            .asImageBitmap()
        text = document.pageText(index).also { pages.texts[index] = it }
        links = document.pageLinks(index)
    }
    DisposableEffect(index) {
        onDispose {
            pages.coordinates.remove(index)
            pages.texts.remove(index)
        }
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
    val selectionColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
    // The gesture coroutines below outlive recompositions, so they read these through State
    // rather than capturing whatever value was current when they started.
    val currentText = rememberUpdatedState(text)
    val currentSelection = rememberUpdatedState(selection)
    val currentLinks = rememberUpdatedState(links)
    val linkSlopPx = with(LocalDensity.current) { PvotSpacing.XS.toPx() }
    // A selection drag that auto-scrolls can carry this page off screen; pinned, the list keeps it
    // (and so the drag) alive until the finger lifts.
    val pinnable = LocalPinnableContainer.current
    val pinned = remember { PinHolder() }
    DisposableEffect(Unit) { onDispose { pinned.release() } }
    val selectedRange = text?.let { selection?.rangeOn(index, it.text.lastIndex) }
    val pageDescription = stringResource(R.string.viewer_pdf_page_description, index + 1, document.pageCount)

    Box(modifier = Modifier.fillMaxWidth().padding(bottom = PvotSpacing.S)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(document.pageAspects[index])
                // An unpainted PDF page is white (black in dark pages); this also shows while the
                // page renders.
                .background(pagePaper(pageFilter != null))
                .onSizeChanged { size = it }
                .onGloballyPositioned {
                    coordinates.value = it
                    pages.coordinates[index] = it
                }
                // TalkBack reads the page's text, which on screen is only a picture of it.
                .semantics {
                    contentDescription = pageDescription
                    text?.let { this.text = AnnotatedString(it.text.replace("\r\n", "\n")) }
                }
                .pointerInput(index) {
                    fun toWindow(position: Offset) = coordinates.value?.takeIf { it.isAttached }?.localToWindow(position)
                    detectDragGesturesAfterLongPress(
                        onDragStart = { position ->
                            if (currentText.value == null) return@detectDragGesturesAfterLongPress
                            pinned.pin(pinnable)
                            toWindow(position)?.let(onLongPress)
                        },
                        onDrag = { change, _ -> toWindow(change.position)?.let(onLongPressDrag) },
                        onDragEnd = {
                            pinned.release()
                            onLongPressEnd()
                        },
                        onDragCancel = {
                            pinned.release()
                            onLongPressEnd()
                        },
                    )
                }
                .pointerInput(index) {
                    // A long press belongs to text selection above, but letting go of one also
                    // counts as a tap here, and a tap clears the selection. So a press held past
                    // the long-press time isn't a tap. (Passing onLongPress instead would have
                    // this detector swallow the rest of the gesture, cancelling the selection drag.)
                    var pressMillis = 0L
                    val longPressMillis = viewConfiguration.longPressTimeoutMillis
                    detectTapGestures(
                        onPress = {
                            val down = SystemClock.uptimeMillis()
                            tryAwaitRelease()
                            pressMillis = SystemClock.uptimeMillis() - down
                        },
                        onDoubleTap = { position ->
                            coordinates.value?.takeIf { it.isAttached }?.let { onDoubleTap(it.localToWindow(position)) }
                        },
                        onTap = { position ->
                        if (pressMillis >= longPressMillis) return@detectTapGestures
                        if (currentSelection.value != null) {
                            onClearSelection()
                            return@detectTapGestures
                        }
                        // A little slop around each link, since link boxes are often just the
                        // height of a line of small text.
                        currentLinks.value.firstOrNull { link ->
                            link.rects.any { r ->
                                position.x >= r.left * size.width - linkSlopPx &&
                                    position.x <= r.right * size.width + linkSlopPx &&
                                    position.y >= r.top * size.height - linkSlopPx &&
                                    position.y <= r.bottom * size.height + linkSlopPx
                            }
                        }?.let(onLinkTap)
                    },
                    )
                },
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                page?.let { drawImage(it, colorFilter = pageFilter) }
                tile?.let {
                    drawImage(
                        image = it.image,
                        dstOffset = IntOffset(it.bounds.left.roundToInt(), it.bounds.top.roundToInt()),
                        dstSize = IntSize(it.bounds.width.roundToInt(), it.bounds.height.roundToInt()),
                        colorFilter = pageFilter,
                    )
                }
                for (r in highlights) drawRect(highlightColor, r.topLeft(this.size), r.size(this.size))
                currentHighlight?.forEach { r -> drawRect(currentHighlightColor, r.topLeft(this.size), r.size(this.size)) }
                val pageText = text
                if (selectedRange != null && pageText != null) {
                    for (line in pageText.lineRects(selectedRange.first, selectedRange.last)) {
                        drawRect(selectionColor, line.topLeft(this.size), line.size(this.size))
                    }
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
private fun PageText.charNearest(position: Offset, size: IntSize, skipWhitespace: Boolean = false): Int? {
    if (size.width <= 0) return null
    var best: Int? = null
    var bestDistance = Float.MAX_VALUE
    boxes.forEachIndexed { i, box ->
        if (box == null) return@forEachIndexed
        if (skipWhitespace && text.getOrNull(i)?.isWhitespace() != false) return@forEachIndexed
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

/** The word around character [index] — the run of letters and digits it belongs to — so a long
 * press selects a whole word, as in other readers. Punctuation on its own is selected alone. */
private fun PageText.wordAround(index: Int): IntRange {
    fun isWordChar(i: Int) = text.getOrNull(i)?.isLetterOrDigit() == true
    if (!isWordChar(index)) return index..index
    var first = index
    var last = index
    while (isWordChar(first - 1)) first--
    while (isWordChar(last + 1)) last++
    return first..last
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

/** The pages currently laid out, by index: where each is on screen and its text. Lets a finger
 * position anywhere over the document be turned into a character, whichever page it's over. */
private class PageRegistry {
    val coordinates = HashMap<Int, LayoutCoordinates>()
    val texts = HashMap<Int, PageText>()

    fun text(page: Int): PageText? = texts[page]

    /** The character nearest [windowPosition]: on the page under it, or the nearest page above or
     * below when it's in the gap between pages. [onlyPage] restricts it to one page. */
    fun charAt(windowPosition: Offset, onlyPage: Int? = null, skipWhitespace: Boolean = false): TextPos? {
        var bestPage = -1
        var bestDistance = Float.MAX_VALUE
        for ((page, coords) in coordinates) {
            if (onlyPage != null && page != onlyPage) continue
            if (!coords.isAttached || texts[page] == null) continue
            val top = coords.localToWindow(Offset.Zero).y
            val bottom = coords.localToWindow(Offset(0f, coords.size.height.toFloat())).y
            val distance = when {
                windowPosition.y < top -> top - windowPosition.y
                windowPosition.y > bottom -> windowPosition.y - bottom
                else -> 0f
            }
            if (distance < bestDistance) {
                bestDistance = distance
                bestPage = page
            }
        }
        val coords = coordinates[bestPage] ?: return null
        val pageText = texts[bestPage] ?: return null
        val local = coords.windowToLocal(windowPosition)
        return pageText.charNearest(local, coords.size, skipWhitespace)?.let { TextPos(bestPage, it) }
    }

    /** Where the start or end handle of a selection goes, in [overlay]'s coordinates: under the
     * first or last selected character. Null when that page isn't on screen. */
    @Suppress("UNUSED_PARAMETER")
    fun handlePosition(pos: TextPos, start: Boolean, overlay: LayoutCoordinates?, tick: Any): Offset? {
        val coords = coordinates[pos.page]?.takeIf { it.isAttached } ?: return null
        val pageText = texts[pos.page] ?: return null
        val target = overlay?.takeIf { it.isAttached } ?: return null
        val box = (if (start) pageText.boxAtOrAfter(pos.char) else pageText.boxAtOrBefore(pos.char)) ?: return null
        val width = coords.size.width
        val height = coords.size.height
        val local = Offset((if (start) box.left else box.right) * width, box.bottom * height)
        val window = coords.localToWindow(local)
        val inOverlay = target.windowToLocal(window)
        // Off the visible list: no handle rather than one floating over the bars.
        if (inOverlay.y < 0f || inOverlay.y > target.size.height) return null
        return inOverlay
    }
}

/** Reads the scroll and zoom state, so a handle's position is recomputed as the document moves. */
private fun scrollTick(listState: LazyListState, zoom: PdfZoomState): Any =
    listOf(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, zoom.scale, zoom.offsetX, zoom.offsetY)

/** A selection handle, placed by [position] (in the overlay's coordinates; null hides it) and
 * reporting where the finger is dragged in window coordinates, so the selection can follow it onto
 * another page. */
@Composable
private fun SelectionHandle(
    color: Color,
    position: () -> Offset?,
    onDrag: (windowPosition: Offset) -> Unit,
    onDragEnd: () -> Unit,
    toWindow: (Offset) -> Offset?,
) {
    val currentOnDragEnd = rememberUpdatedState(onDragEnd)
    // If the handle leaves mid-drag (its text scrolled away), the drag ends with it, so
    // auto-scroll doesn't run on with no finger behind it.
    val dragging = remember { BooleanHolder() }
    DisposableEffect(Unit) {
        onDispose { if (dragging.value) currentOnDragEnd.value() }
    }
    val at = position() ?: return
    val handleSize = 24.dp
    val half = with(LocalDensity.current) { (handleSize / 2).roundToPx() }
    // The handle moves as the selection changes; keying the gesture on its position would restart
    // it, and drop the drag, on every move.
    val currentAt = rememberUpdatedState(at)
    val currentOnDrag = rememberUpdatedState(onDrag)
    val currentToWindow = rememberUpdatedState(toWindow)
    Box(
        modifier = Modifier
            .offset { IntOffset(at.x.roundToInt() - half, at.y.roundToInt()) }
            .size(handleSize)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { dragging.value = true },
                    onDragEnd = {
                        dragging.value = false
                        currentOnDragEnd.value()
                    },
                    onDragCancel = {
                        dragging.value = false
                        currentOnDragEnd.value()
                    },
                ) { change, _ ->
                    change.consume()
                    val local = currentAt.value + change.position - Offset(half.toFloat(), 0f)
                    currentToWindow.value(local)?.let(currentOnDrag.value)
                }
            }
            .clip(CircleShape)
            .background(color),
    )
}
