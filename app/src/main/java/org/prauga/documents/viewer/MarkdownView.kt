package org.prauga.documents.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prauga.pvot.designsystem.theme.PvotSpacing
import org.prauga.documents.util.MarkdownBlock
import org.prauga.documents.util.parseMarkdown

/** [text] parsed and shown as rendered Markdown: headings, paragraphs, lists, code fences,
 * blockquotes and rules, with bold/italic/code/link styling inside each line. One block per list
 * row, so a long document scrolls lazily rather than laying out as a single giant [Text]. */
@Composable
fun MarkdownView(text: String, topPadding: Dp, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseMarkdown(text) }
    val linkColor = MaterialTheme.colorScheme.primary
    SelectionContainer {
        LazyColumn(
            modifier = modifier,
            contentPadding = PaddingValues(
                top = topPadding + PvotSpacing.L,
                start = PvotSpacing.L,
                end = PvotSpacing.L,
                bottom = PvotSpacing.L
            )
        ) {
            items(blocks) { block -> MarkdownBlockView(block = block, linkColor = linkColor) }
        }
    }
}

@Composable
private fun MarkdownBlockView(block: MarkdownBlock, linkColor: Color) {
    when (block) {
        is MarkdownBlock.Heading -> Text(
            text = inlineMarkdown(block.text, linkColor),
            style = headingStyle(block.level),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = PvotSpacing.M, bottom = PvotSpacing.S)
        )

        is MarkdownBlock.Paragraph -> Text(
            text = inlineMarkdown(block.text, linkColor),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(vertical = PvotSpacing.XS)
        )

        is MarkdownBlock.ListItem -> Text(
            text = buildAnnotatedString {
                append(if (block.ordered) "${block.number}.  " else "•  ")
                append(inlineMarkdown(block.text, linkColor))
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = PvotSpacing.S, top = PvotSpacing.XS, bottom = PvotSpacing.XS)
        )

        is MarkdownBlock.CodeBlock -> Box(
            modifier = Modifier
                .padding(vertical = PvotSpacing.S)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                .horizontalScroll(rememberScrollState())
        ) {
            Text(
                text = block.text,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(PvotSpacing.M)
            )
        }

        is MarkdownBlock.Quote -> Row(modifier = Modifier.padding(vertical = PvotSpacing.XS)) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .background(MaterialTheme.colorScheme.primary)
            )
            Text(
                text = inlineMarkdown(block.text, linkColor),
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = PvotSpacing.S)
            )
        }

        MarkdownBlock.Rule -> HorizontalDivider(modifier = Modifier.padding(vertical = PvotSpacing.S))
    }
}

@Composable
private fun headingStyle(level: Int) = when (level) {
    1 -> MaterialTheme.typography.headlineMedium
    2 -> MaterialTheme.typography.headlineSmall
    3 -> MaterialTheme.typography.titleLarge
    4 -> MaterialTheme.typography.titleMedium
    5 -> MaterialTheme.typography.titleSmall
    else -> MaterialTheme.typography.labelLarge
}

private val InlineRegex = Regex(
    "\\*\\*(.+?)\\*\\*|__(.+?)__|`(.+?)`|\\[(.+?)]\\((.+?)\\)|\\*(.+?)\\*|_(.+?)_"
)

/** [line]'s inline markers (`**bold**`, `_italic_`, `` `code` ``, `[text](url)`) turned into spans
 * on an [AnnotatedString] — the same line text the block parser kept untouched, styled now that a
 * composable (and its theme colors) is available. */
private fun inlineMarkdown(line: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (match in InlineRegex.findAll(line)) {
        if (match.range.first > last) append(line.substring(last, match.range.first))
        val groups = match.groups
        when {
            groups[1] != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(groups[1]!!.value) }
            groups[2] != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(groups[2]!!.value) }
            groups[3] != null -> withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            ) { append(groups[3]!!.value) }
            groups[4] != null -> withLink(LinkAnnotation.Url(groups[5]!!.value)) {
                withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                    append(groups[4]!!.value)
                }
            }
            groups[6] != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(groups[6]!!.value) }
            groups[7] != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(groups[7]!!.value) }
        }
        last = match.range.last + 1
    }
    if (last < line.length) append(line.substring(last))
}
