package org.prauga.documents.viewer

import android.graphics.Typeface
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.text.style.UnderlineSpan
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.core.text.HtmlCompat
import com.prauga.pvot.designsystem.theme.PvotSpacing

/** [html] through the platform's own HTML parser, shown as scrollable, selectable styled text. */
@Composable
fun HtmlView(html: String, topPadding: Dp, modifier: Modifier = Modifier) {
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = remember(html, linkColor) { htmlToAnnotatedString(html, linkColor) }
    SelectionContainer {
        Text(
            text = annotated,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = modifier
                .verticalScroll(rememberScrollState())
                .padding(top = topPadding)
                .padding(PvotSpacing.L)
        )
    }
}

/** [html] parsed by [HtmlCompat], its character-level spans (bold, italic, underline,
 * strikethrough, color, links) carried over into an [AnnotatedString]. Layout spans such as
 * bullets, quotes or images aren't carried over — there's no Compose equivalent to drop them
 * into, so those render as plain paragraph breaks instead. */
private fun htmlToAnnotatedString(html: String, linkColor: Color): AnnotatedString {
    val spanned = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT)
    return buildAnnotatedString {
        append(spanned.toString())
        for (span in spanned.getSpans(0, spanned.length, Any::class.java)) {
            val start = spanned.getSpanStart(span)
            val end = spanned.getSpanEnd(span)
            if (start < 0 || end < 0 || start >= end) continue
            when (span) {
                is StyleSpan -> when (span.style) {
                    Typeface.BOLD -> addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, end)
                    Typeface.ITALIC -> addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, end)
                    Typeface.BOLD_ITALIC -> addStyle(
                        SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic), start, end
                    )
                }
                is UnderlineSpan -> addStyle(SpanStyle(textDecoration = TextDecoration.Underline), start, end)
                is StrikethroughSpan -> addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), start, end)
                is ForegroundColorSpan -> addStyle(SpanStyle(color = Color(span.foregroundColor)), start, end)
                is URLSpan -> {
                    addStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline), start, end)
                    addLink(LinkAnnotation.Url(span.url), start, end)
                }
            }
        }
    }
}
