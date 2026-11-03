package org.prauga.documents.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import com.prauga.pvot.coreui.PvotErrorContent
import com.prauga.pvot.coreui.PvotLoadingContent
import com.prauga.pvot.coreui.UiState
import com.prauga.pvot.designsystem.components.PvotCard
import com.prauga.pvot.designsystem.theme.PvotSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.prauga.documents.R

/** [uri]'s pages rendered through the platform's own [PdfRenderer], one page per card, scrolling
 * through the whole document like a continuous strip rather than a page-at-a-time pager. */
@Composable
fun PdfView(uri: Uri, topPadding: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var state by remember(uri) { mutableStateOf<UiState<List<Bitmap>>>(UiState.Loading) }
    var retryToken by remember(uri) { mutableIntStateOf(0) }
    val readFailedMessage = stringResource(R.string.viewer_read_failed)
    val widthPx = with(density) { PdfPageWidth.roundToPx() }

    LaunchedEffect(uri, retryToken) {
        state = UiState.Loading
        state = withContext(Dispatchers.IO) {
            runCatching { renderPdfPages(context, uri, widthPx) }
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

        is UiState.Success -> LazyColumn(
            modifier = modifier,
            contentPadding = PaddingValues(
                top = topPadding + PvotSpacing.M,
                start = PvotSpacing.L,
                end = PvotSpacing.L,
                bottom = PvotSpacing.M
            )
        ) {
            items(current.data) { page ->
                PvotCard(modifier = Modifier.fillMaxWidth().padding(bottom = PvotSpacing.M)) {
                    val zoom = remember(page) { PageZoomState() }
                    Box(modifier = Modifier.fillMaxWidth().clipToBounds()) {
                        Image(
                            bitmap = page.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer(
                                    scaleX = zoom.scale,
                                    scaleY = zoom.scale,
                                    translationX = zoom.offsetX,
                                    translationY = zoom.offsetY
                                )
                                .pinchZoom(zoom)
                        )
                    }
                }
            }
        }
    }
}

/** A page's zoom level and pan offset, reset whenever the page's own [remember] key changes —
 * each page keeps its own zoom rather than sharing one across the whole scrolling strip. */
private class PageZoomState {
    var scale by mutableFloatStateOf(1f)
    var offsetX by mutableFloatStateOf(0f)
    var offsetY by mutableFloatStateOf(0f)
}

private const val MaxPageZoom = 5f

/** Two-finger pinch-to-zoom and pan on [zoom], one-finger drags left untouched so the page list
 * keeps scrolling normally. Only consumes pointer events once a second finger is down — a plain
 * scroll never reaches this gesture detector's zoom/pan branch, so there's nothing to arbitrate
 * with the enclosing [androidx.compose.foundation.lazy.LazyColumn]'s own scroll handling. */
private fun Modifier.pinchZoom(zoom: PageZoomState): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        do {
            val event = awaitPointerEvent()
            if (event.changes.size >= 2) {
                val zoomChange = event.calculateZoom()
                val panChange = event.calculatePan()
                zoom.scale = (zoom.scale * zoomChange).coerceIn(1f, MaxPageZoom)
                if (zoom.scale > 1f) {
                    zoom.offsetX += panChange.x
                    zoom.offsetY += panChange.y
                } else {
                    zoom.offsetX = 0f
                    zoom.offsetY = 0f
                }
                event.changes.forEach { it.consume() }
            }
        } while (event.changes.any { it.pressed })
    }
}

private val PdfPageWidth = 480.dp

/** [renderer]'s pages, each rasterized at [widthPx] wide and scaled to the page's own aspect
 * ratio. Runs blocking file I/O — call from [Dispatchers.IO], never the main thread. */
private fun renderPdfPages(context: Context, uri: Uri, widthPx: Int): List<Bitmap> {
    val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
        ?: error("Could not open $uri for reading")
    return descriptor.use { fd ->
        PdfRenderer(fd).use { renderer ->
            (0 until renderer.pageCount).map { index ->
                renderer.openPage(index).use { page ->
                    val height = (widthPx.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                    val bitmap = createBitmap(widthPx, height)
                    bitmap.eraseColor(AndroidColor.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }
        }
    }
}
