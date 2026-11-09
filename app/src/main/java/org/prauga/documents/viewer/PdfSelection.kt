package org.prauga.documents.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import com.prauga.pvot.designsystem.components.button.PvotButton
import com.prauga.pvot.designsystem.components.button.PvotButtonStyle
import com.prauga.pvot.designsystem.components.pvotGlassSurface
import com.prauga.pvot.designsystem.effects.LocalPvotGlassBackdrop
import com.prauga.pvot.designsystem.effects.PvotGlassDefaults
import com.prauga.pvot.designsystem.effects.PvotGlassMaterial
import com.prauga.pvot.designsystem.theme.PvotSpacing
import org.prauga.documents.R

/** A character in the document: [char] is a Pdfium character index on page [page]. */
internal data class TextPos(val page: Int, val char: Int) : Comparable<TextPos> {
    override fun compareTo(other: TextPos): Int =
        if (page != other.page) page.compareTo(other.page) else char.compareTo(other.char)
}

/** A selection that can run across pages. [anchor] is where it started and [focus] where it was
 * dragged to, in either order; [start] and [end] are the two in reading order, both inclusive. */
internal data class DocSelection(val anchor: TextPos, val focus: TextPos) {
    val start: TextPos get() = minOf(anchor, focus)
    val end: TextPos get() = maxOf(anchor, focus)

    /** The selected characters on [page], whose last character index is [lastChar]; null when the
     * selection doesn't reach that page. */
    fun rangeOn(page: Int, lastChar: Int): IntRange? {
        if (page < start.page || page > end.page || lastChar < 0) return null
        val first = if (page == start.page) start.char else 0
        val last = if (page == end.page) end.char else lastChar
        return if (first <= last) first..last else null
    }
}

/**
 * The text selection's actions, shared between [PdfView], which owns the selection, and the
 * viewer's bottom bar ([PdfSelectionBar]), which offers them while something is selected.
 */
@Stable
class PdfSelectionState {
    /** Whether any text is selected. */
    var active by mutableStateOf(false)
        internal set

    /** Height of [PdfSelectionBar] from the bottom of the screen, so a selection drag knows
     * where the visible document ends and auto-scrolls before the finger reaches the bar. */
    var barHeightPx by mutableIntStateOf(0)
        internal set

    /** A message for the viewer to show, such as a copy cut short; cleared once shown. */
    var notice by mutableStateOf<String?>(null)

    internal var onCopy: () -> Unit = {}
    internal var onShare: () -> Unit = {}
    internal var onSearch: () -> Unit = {}
    internal var onSelectAll: () -> Unit = {}
}

/** What to do with the selected text, in a glass capsule over the bottom of the document: copy,
 * share, search the document for it, or widen the selection to the whole document. Thick glass,
 * like the search bar, because it sits over white pages. */
@Composable
fun PdfSelectionBar(state: PdfSelectionState, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { state.barHeightPx = it.height }
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = PvotSpacing.L, vertical = PvotSpacing.M)
            .pvotGlassSurface(
                backdrop = LocalPvotGlassBackdrop.current,
                style = PvotGlassDefaults.materialStyle(PvotGlassMaterial.Thick),
                role = null,
            )
            .padding(PvotSpacing.XS),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PvotButton(
            text = stringResource(R.string.viewer_pdf_copy_selection),
            onClick = { state.onCopy() },
            style = PvotButtonStyle.Text,
        )
        PvotButton(
            text = stringResource(R.string.viewer_pdf_share_selection),
            onClick = { state.onShare() },
            style = PvotButtonStyle.Text,
        )
        PvotButton(
            text = stringResource(R.string.viewer_pdf_search_selection),
            onClick = { state.onSearch() },
            style = PvotButtonStyle.Text,
        )
        PvotButton(
            text = stringResource(R.string.viewer_pdf_select_all),
            onClick = { state.onSelectAll() },
            style = PvotButtonStyle.Text,
        )
    }
}
