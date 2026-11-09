package org.prauga.documents.util

/** A line-level piece of a Markdown document. Parsed once up front, then rendered by whatever UI
 * wants to show it — [text] in every variant still carries inline markers (`**bold**`, links, ...)
 * for the renderer to style. */
sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class ListItem(val ordered: Boolean, val number: Int?, val text: String) : MarkdownBlock
    data class CodeBlock(val text: String) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data object Rule : MarkdownBlock
}

private val HeadingRegex = Regex("^(#{1,6})\\s+(.*)$")
private val OrderedListRegex = Regex("^\\s*\\d+[.)]\\s+(.*)$")
private val UnorderedListRegex = Regex("^\\s*[-*+]\\s+(.*)$")

/** `---`, `***` or `___`: three or more of the same rule character, spaces allowed between them. */
private fun isRuleLine(line: String): Boolean {
    val chars = line.filterNot { it == ' ' }
    if (chars.length < 3) return false
    val marker = chars[0]
    if (marker != '-' && marker != '*' && marker != '_') return false
    return chars.all { it == marker }
}

/** [text] split into [MarkdownBlock]s: headings, paragraphs (consecutive plain lines joined with a
 * space), list items, fenced code blocks, blockquotes and horizontal rules. Nothing beyond this —
 * nested lists, tables and images aren't block types the renderer needs yet. */
fun parseMarkdown(text: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = text.lines()
    var index = 0
    val paragraph = StringBuilder()
    var orderedCounter = 1

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks.add(MarkdownBlock.Paragraph(paragraph.toString().trim()))
            paragraph.clear()
        }
    }

    while (index < lines.size) {
        val line = lines[index]
        if (!OrderedListRegex.matches(line)) orderedCounter = 1

        when {
            line.startsWith("```") -> {
                flushParagraph()
                val code = StringBuilder()
                index++
                while (index < lines.size && !lines[index].startsWith("```")) {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[index])
                    index++
                }
                blocks.add(MarkdownBlock.CodeBlock(code.toString()))
            }

            isRuleLine(line) -> {
                flushParagraph()
                blocks.add(MarkdownBlock.Rule)
            }

            HeadingRegex.matches(line) -> {
                flushParagraph()
                val match = HeadingRegex.find(line)!!
                blocks.add(MarkdownBlock.Heading(match.groupValues[1].length, match.groupValues[2].trim()))
            }

            line.trimStart().startsWith(">") -> {
                flushParagraph()
                blocks.add(MarkdownBlock.Quote(line.trimStart().removePrefix(">").trim()))
            }

            OrderedListRegex.matches(line) -> {
                flushParagraph()
                val content = OrderedListRegex.find(line)!!.groupValues[1]
                blocks.add(MarkdownBlock.ListItem(ordered = true, number = orderedCounter, text = content))
                orderedCounter++
            }

            UnorderedListRegex.matches(line) -> {
                flushParagraph()
                val content = UnorderedListRegex.find(line)!!.groupValues[1]
                blocks.add(MarkdownBlock.ListItem(ordered = false, number = null, text = content))
            }

            line.isBlank() -> flushParagraph()

            else -> {
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(line.trim())
            }
        }
        index++
    }
    flushParagraph()
    return blocks
}
