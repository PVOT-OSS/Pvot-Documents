package org.prauga.documents.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import com.prauga.pvot.designsystem.components.pvotGlassSurface
import com.prauga.pvot.designsystem.effects.LocalPvotGlassBackdrop
import com.prauga.pvot.designsystem.effects.PvotGlassDefaults
import com.prauga.pvot.designsystem.effects.PvotGlassMaterial
import com.prauga.pvot.designsystem.components.PvotGlassIconButton
import com.prauga.pvot.designsystem.components.button.PvotIconButton
import com.prauga.pvot.designsystem.components.button.PvotIconButtonDefaults
import com.prauga.pvot.designsystem.components.textfield.PvotTextField
import com.prauga.pvot.designsystem.components.textfield.PvotTextFieldDefaults
import com.prauga.pvot.designsystem.theme.PvotShapes
import com.prauga.pvot.designsystem.theme.PvotSpacing
import org.prauga.documents.R

/**
 * Find-in-document, shared between the viewer's bars and [PdfView]: the top bar opens it, the
 * bottom bar ([PdfSearchBar]) edits the query and steps through matches, and [PdfView] runs the
 * search and scrolls to each match.
 */
@Stable
class PdfSearchState {
    /** Whether a document is loaded that can be searched; set by [PdfView]. */
    var available by mutableStateOf(false)
        internal set
    var active by mutableStateOf(false)
        private set
    var query by mutableStateOf("")
    var matchCount by mutableIntStateOf(0)
        internal set
    var currentMatch by mutableIntStateOf(0)
        internal set

    /** Height of [PdfSearchBar] from the bottom of the screen, keyboard included, so [PdfView]
     * scrolls matches clear of it. */
    var barHeightPx by mutableIntStateOf(0)
        internal set

    /** Moves to the match [delta] steps away; [PdfView] provides it. */
    internal var step: (delta: Int) -> Unit = {}

    fun open() {
        active = true
    }

    fun close() {
        active = false
        query = ""
    }
}

/**
 * The search bar, floating over the bottom of the document as glass and riding above the
 * keyboard: a capsule with the query, the match count and previous/next, and a round close button
 * beside it, the same height. Goes in the scaffold's bottom bar so it refracts the page behind it.
 */
@Composable
fun PdfSearchBar(state: PdfSearchState, modifier: Modifier = Modifier) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // Opening search goes straight to typing.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    BackHandler(onBack = state::close)

    val hasMatches = state.matchCount > 0
    val glassStyle = PvotGlassDefaults.materialStyle(PvotGlassMaterial.Thick)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { state.barHeightPx = it.height }
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
            .padding(horizontal = PvotSpacing.L, vertical = PvotSpacing.M),
        horizontalArrangement = Arrangement.spacedBy(PvotSpacing.S),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A capsule of Thick glass rather than PvotCapsule's Regular: the page behind is usually
        // white, and through the thinner tint the query and icons washed out against it.
        Row(
            modifier = Modifier
                .weight(1f)
                .pvotGlassSurface(
                    backdrop = LocalPvotGlassBackdrop.current,
                    style = glassStyle,
                    role = null,
                )
                .padding(BarPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PvotTextField(
                value = state.query,
                onValueChange = { state.query = it },
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                placeholder = stringResource(R.string.viewer_pdf_search_placeholder),
                leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                singleLine = true,
                shape = PvotShapes.Pill,
                // The capsule is the field's surface; a second fill and focus ring inside the glass
                // would just box the text in.
                colors = PvotTextFieldDefaults.colors(
                    MaterialTheme.colorScheme,
                    containerColor = Color.Transparent,
                    focusedBorderColor = Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboard?.hide()
                    state.step(1)
                }),
            )
            if (state.query.isNotBlank()) {
                Text(
                    text = if (hasMatches) {
                        stringResource(R.string.viewer_pdf_search_count, state.currentMatch + 1, state.matchCount)
                    } else {
                        stringResource(R.string.viewer_pdf_search_none)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = PvotSpacing.XS),
                )
            }
            PvotIconButton(
                icon = painterResource(R.drawable.ic_chevron_up),
                contentDescription = stringResource(R.string.viewer_pdf_search_previous),
                onClick = { state.step(-1) },
                enabled = hasMatches,
                colors = PvotIconButtonDefaults.plainColors(),
            )
            PvotIconButton(
                icon = painterResource(R.drawable.ic_chevron_down),
                contentDescription = stringResource(R.string.viewer_pdf_search_next),
                onClick = { state.step(1) },
                enabled = hasMatches,
                colors = PvotIconButtonDefaults.plainColors(),
            )
        }
        PvotGlassIconButton(
            onClick = state::close,
            style = glassStyle,
            // As tall as the capsule beside it, so the two read as one bar.
            size = PvotTextFieldDefaults.MinHeight + BarPadding * 2,
        ) {
            Icon(
                painterResource(R.drawable.ic_x),
                contentDescription = stringResource(R.string.viewer_pdf_search_close),
            )
        }
    }
}

private val BarPadding = PvotSpacing.XS
