package org.prauga.documents.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.prauga.pvot.designsystem.theme.PvotShapes
import com.prauga.pvot.designsystem.theme.PvotSpacing
import org.prauga.documents.R

/** Every page as a thumbnail, in a grid over the document; the page being read is outlined and
 * scrolled into view. Tapping a page goes there and closes the overview, as does back. */
@Composable
internal fun PdfPageOverview(
    document: PdfiumDocument,
    currentPage: Int,
    topPadding: Dp,
    pageFilter: ColorFilter?,
    onPick: (Int) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val gridState = rememberLazyGridState()
    LaunchedEffect(Unit) { gridState.scrollToItem(currentPage) }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Clear of the camera cutout and a side navigation bar in landscape, like the pages.
    val layoutDirection = LocalLayoutDirection.current
    val sides = WindowInsets.displayCutout.union(WindowInsets.navigationBars)
        .only(WindowInsetsSides.Horizontal).asPaddingValues()

    LazyVerticalGrid(
        columns = GridCells.Adaptive(ThumbnailMinWidth),
        state = gridState,
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(
            start = PvotSpacing.L + sides.calculateStartPadding(layoutDirection),
            end = PvotSpacing.L + sides.calculateEndPadding(layoutDirection),
            top = topPadding + PvotSpacing.M,
            bottom = bottom + PvotSpacing.L,
        ),
        horizontalArrangement = Arrangement.spacedBy(PvotSpacing.M),
        verticalArrangement = Arrangement.spacedBy(PvotSpacing.L),
    ) {
        items(count = document.pageCount, key = { it }) { index ->
            PageThumbnail(
                document = document,
                index = index,
                current = index == currentPage,
                pageFilter = pageFilter,
                onClick = { onPick(index) },
            )
        }
    }
}

@Composable
private fun PageThumbnail(
    document: PdfiumDocument,
    index: Int,
    current: Boolean,
    pageFilter: ColorFilter?,
    onClick: () -> Unit,
) {
    var widthPx by remember { mutableIntStateOf(0) }
    var image by remember(index) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(index, widthPx) {
        if (widthPx > 0) image = document.renderThumbnail(index, widthPx).asImageBitmap()
    }
    val description = stringResource(R.string.viewer_pdf_page_thumbnail, index + 1)
    val outline = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier = Modifier
            .clip(PvotShapes.Small)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PvotSpacing.XS),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(document.pageAspects[index])
                .border(if (current) CurrentOutline else Outline, outline, PvotShapes.Small)
                .clip(PvotShapes.Small)
                .background(pagePaper(pageFilter != null))
                .onSizeChanged { widthPx = it.width },
        ) {
            image?.let { bitmap ->
                Canvas(Modifier.fillMaxSize()) {
                    drawImage(
                        image = bitmap,
                        dstOffset = IntOffset.Zero,
                        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                        colorFilter = pageFilter,
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.viewer_pdf_page_number, index + 1),
            style = MaterialTheme.typography.labelMedium,
            color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val ThumbnailMinWidth = 96.dp
private val Outline = 1.dp
private val CurrentOutline = 2.dp
