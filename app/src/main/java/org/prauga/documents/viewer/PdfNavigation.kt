package org.prauga.documents.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.prauga.pvot.designsystem.components.PvotBottomSheet
import com.prauga.pvot.designsystem.components.PvotDialog
import com.prauga.pvot.designsystem.components.PvotDialogAction
import com.prauga.pvot.designsystem.components.PvotDialogActionStyle
import com.prauga.pvot.designsystem.components.PvotSheetItem
import com.prauga.pvot.designsystem.components.pvotGlassSurface
import com.prauga.pvot.designsystem.components.textfield.PvotTextField
import com.prauga.pvot.designsystem.effects.LocalPvotGlassBackdrop
import com.prauga.pvot.designsystem.effects.PvotGlassDefaults
import com.prauga.pvot.designsystem.effects.PvotGlassMaterial
import com.prauga.pvot.designsystem.theme.PvotSpacing
import org.prauga.documents.R

/**
 * Where the reader is in a PDF and how to move around it, shared between the viewer's bars and
 * [PdfView]: [PdfView] reports the page count, the page on screen and the outline, and provides
 * [goToPage]; the bars show the page indicator, the go-to-page dialog and the contents sheet.
 */
@Stable
class PdfNavigationState {
    var pageCount by mutableIntStateOf(0)
        internal set

    /** Zero-based index of the page in the middle of the screen. */
    var currentPage by mutableIntStateOf(0)
        internal set

    var outline by mutableStateOf<List<OutlineEntry>>(emptyList())
        internal set

    /** Whether the page indicator is showing: it appears while the document moves, and fades
     * once it has been still for a moment. */
    var indicatorVisible by mutableStateOf(false)
        internal set

    internal var goTo: (pageIndex: Int) -> Unit = {}

    /** Scrolls to the top of page [pageIndex] (zero-based). */
    fun goToPage(pageIndex: Int) {
        if (pageIndex in 0 until pageCount) goTo(pageIndex)
    }
}

/** "3 / 12" in a small glass capsule over the bottom of the document; tapping it asks for a page
 * to go to. Thick glass for the same reason as [PdfSearchBar]: it sits over white pages. */
@Composable
fun PdfPageIndicator(state: PdfNavigationState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val page = state.currentPage + 1
    val description = stringResource(R.string.viewer_pdf_page_indicator_description, page, state.pageCount)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(PvotSpacing.M),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .pvotGlassSurface(
                    backdrop = LocalPvotGlassBackdrop.current,
                    style = PvotGlassDefaults.materialStyle(PvotGlassMaterial.Thick),
                    onClick = onClick,
                )
                .semantics { contentDescription = description }
                .padding(horizontal = PvotSpacing.L, vertical = PvotSpacing.S),
        ) {
            Text(
                text = stringResource(R.string.viewer_pdf_page_indicator, page, state.pageCount),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** Asks for a page number, 1 to [PdfNavigationState.pageCount], and goes there. */
@Composable
fun GoToPageDialog(state: PdfNavigationState, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val target = text.toIntOrNull()?.takeIf { it in 1..state.pageCount }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    fun go() {
        val page = target ?: return
        state.goToPage(page - 1)
        onDismiss()
    }

    PvotDialog(
        visible = true,
        onDismissRequest = onDismiss,
        title = stringResource(R.string.viewer_pdf_go_to_title),
        content = {
            PvotTextField(
                value = text,
                onValueChange = { new -> text = new.filter(Char::isDigit).take(MaxPageDigits) },
                placeholder = stringResource(R.string.viewer_pdf_go_to_hint, state.pageCount),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { go() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
        },
    ) {
        PvotDialogAction(label = stringResource(R.string.viewer_pdf_cancel), onClick = onDismiss)
        PvotDialogAction(
            label = stringResource(R.string.viewer_pdf_go_to_action),
            style = PvotDialogActionStyle.Primary,
            enabled = target != null,
            onClick = ::go,
        )
    }
}

private const val MaxPageDigits = 6

/** The document's outline as a sheet, nested entries indented under their parent; tapping one goes
 * to its page. */
@Composable
fun PdfContentsSheet(state: PdfNavigationState, onDismiss: () -> Unit) {
    PvotBottomSheet(
        visible = true,
        onDismissRequest = onDismiss,
        title = stringResource(R.string.viewer_pdf_contents),
    ) {
        state.outline.forEach { entry ->
            PvotSheetItem(
                title = entry.title,
                subtitle = stringResource(R.string.viewer_pdf_contents_page, entry.pageIndex + 1),
                onClick = {
                    state.goToPage(entry.pageIndex)
                    onDismiss()
                },
                modifier = Modifier.padding(start = PvotSpacing.L * entry.depth.coerceAtMost(MaxIndentLevels)),
            )
        }
    }
}

/** Deeper levels stop indenting, so a deeply nested outline doesn't squeeze titles to nothing. */
private const val MaxIndentLevels = 3

