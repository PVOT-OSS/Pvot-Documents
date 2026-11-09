package org.prauga.documents.util

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * An OpenDocument text file (`.odt`, a zip with the body in `content.xml` and named styles in
 * `styles.xml`) read for viewing into a [RichDocument]: paragraphs and headings with their styles
 * resolved through style inheritance, numbered and bulleted lists, tables (spans, covered cells,
 * fills), images and text boxes in frames, links, sections, tables of contents, and page breaks.
 * Notes and comments aren't shown.
 *
 * [file] must be a private copy: the [RichDocument] keeps it to read images from, and deletes it
 * when closed.
 */
fun parseOdt(file: File): RichDocument {
    val styles = OdtStyles()
    val blocks = ZipFile(file).use { zip ->
        val content = zip.getEntry("content.xml") ?: error("Not an OpenDocument text file")
        zip.getEntry("styles.xml")?.let { entry -> zip.getInputStream(entry).use { styles.read(it, stopAtBody = false) } }
        zip.getInputStream(content).use { input ->
            val p = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                setInput(input, null)
            }
            styles.readFrom(p)
            OdtBodyReader(p, styles).read()
        }
    }
    return RichDocument(blocks, file, styles.defaultText.sizePt ?: OdfDefaultSizePt)
}

/** LibreOffice's default body font size, when a document doesn't set one. */
private const val OdfDefaultSizePt = 12f

private fun XmlPullParser.attr(name: String): String? {
    for (i in 0 until attributeCount) if (getAttributeName(i) == name) return getAttributeValue(i)
    return null
}

private fun XmlPullParser.skipElement() {
    var depth = 1
    while (depth > 0) {
        when (next()) {
            XmlPullParser.START_TAG -> depth++
            XmlPullParser.END_TAG -> depth--
            XmlPullParser.END_DOCUMENT -> return
        }
    }
}

/** A length like "2.5cm", "1in", "12pt" or "4mm" in points. */
internal fun odfLengthPt(value: String?): Float? {
    if (value == null) return null
    val number = value.takeWhile { it.isDigit() || it == '.' || it == '-' }.toFloatOrNull() ?: return null
    return when (value.dropWhile { it.isDigit() || it == '.' || it == '-' }) {
        "cm" -> number * 72f / 2.54f
        "mm" -> number * 72f / 25.4f
        "in" -> number * 72f
        "pt" -> number
        "pc" -> number * 12f
        "px" -> number * 0.75f
        else -> null
    }
}

/** Text properties as a style sets them; null is "not set here", so styles layer over their
 * parents. A [color] of 0 means explicitly automatic. */
private data class OdtText(
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underline: Boolean? = null,
    val strike: Boolean? = null,
    val color: Int? = null,
    val highlight: Int? = null,
    val sizePt: Float? = null,
    val position: TextPosition? = null,
    val monospace: Boolean? = null,
) {
    fun over(base: OdtText) = OdtText(
        bold ?: base.bold, italic ?: base.italic, underline ?: base.underline, strike ?: base.strike,
        color ?: base.color, highlight ?: base.highlight, sizePt ?: base.sizePt, position ?: base.position,
        monospace ?: base.monospace,
    )

    fun toStyle(link: String?) = RunStyle(
        bold = bold ?: false,
        italic = italic ?: false,
        underline = underline ?: (link != null),
        strike = strike ?: false,
        color = color?.takeIf { it != 0 },
        highlight = highlight,
        sizePt = sizePt,
        position = position ?: TextPosition.Normal,
        monospace = monospace ?: false,
        link = link,
    )
}

private class OdtStyle(
    val parent: String?,
    val text: OdtText,
    val align: ParagraphAlign?,
    val breakBefore: Boolean,
    val outlineLevel: Int?,
    val displayName: String?,
    val fill: Int?,
    val listStyle: String?,
)

private class ListLevel(val bullet: String?, val format: String, val prefix: String, val suffix: String, val start: Int)

/** Every named and automatic style, plus list styles, from both `styles.xml` and `content.xml`. */
private class OdtStyles {
    val styles = HashMap<String, OdtStyle>()
    val lists = HashMap<String, MutableMap<Int, ListLevel>>()
    var defaultText = OdtText()

    fun read(input: InputStream, stopAtBody: Boolean) {
        val p = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(input, null)
        }
        readUntilBody(p, stopAtBody)
    }

    /** Reads `content.xml`'s automatic styles, leaving [p] at the start of `office:body`. */
    fun readFrom(p: XmlPullParser) = readUntilBody(p, stopAtBody = true)

    private fun readUntilBody(p: XmlPullParser, stopAtBody: Boolean) {
        var name: String? = null
        var family: String? = null
        var isDefault = false
        var parent: String? = null
        var displayName: String? = null
        var text = OdtText()
        var align: ParagraphAlign? = null
        var breakBefore = false
        var outline: Int? = null
        var fill: Int? = null
        var listStyleOfStyle: String? = null
        var listName: String? = null

        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "office:body" -> if (stopAtBody) return
                    "style:style", "style:default-style" -> {
                        isDefault = p.name == "style:default-style"
                        name = p.attr("style:name"); family = p.attr("style:family")
                        parent = p.attr("style:parent-style-name"); displayName = p.attr("style:display-name")
                        text = OdtText(); align = null; breakBefore = false; fill = null
                        outline = p.attr("style:default-outline-level")?.toIntOrNull()
                        listStyleOfStyle = p.attr("style:list-style-name")
                    }
                    "style:text-properties" -> if (family != null) text = textProperties(p)
                    "style:paragraph-properties" -> if (family != null) {
                        align = when (p.attr("fo:text-align")) {
                            "center" -> ParagraphAlign.Center
                            "end", "right" -> ParagraphAlign.End
                            "justify" -> ParagraphAlign.Justify
                            "start", "left" -> ParagraphAlign.Start
                            else -> null
                        }
                        breakBefore = p.attr("fo:break-before") == "page"
                        fill = hexColor(p.attr("fo:background-color")) ?: fill
                    }
                    "style:table-cell-properties" -> if (family != null) fill = hexColor(p.attr("fo:background-color"))
                    "text:list-style" -> { listName = p.attr("style:name"); listName?.let { lists.getOrPut(it) { HashMap() } } }
                    "text:list-level-style-bullet" -> listName?.let { list ->
                        val level = (p.attr("text:level")?.toIntOrNull() ?: 1) - 1
                        lists[list]?.put(level, ListLevel(p.attr("text:bullet-char") ?: "•", "", "", "", 1))
                    }
                    "text:list-level-style-number" -> listName?.let { list ->
                        val level = (p.attr("text:level")?.toIntOrNull() ?: 1) - 1
                        val format = when (p.attr("style:num-format")) {
                            "a" -> "lowerLetter"; "A" -> "upperLetter"; "i" -> "lowerRoman"; "I" -> "upperRoman"
                            "", null -> "none"
                            else -> "decimal"
                        }
                        lists[list]?.put(
                            level,
                            ListLevel(null, format, p.attr("style:num-prefix").orEmpty(), p.attr("style:num-suffix").orEmpty(),
                                p.attr("text:start-value")?.toIntOrNull() ?: 1),
                        )
                    }
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "style:style", "style:default-style" -> {
                        if (isDefault && family == "paragraph") defaultText = text.over(defaultText)
                        name?.let { styles[it] = OdtStyle(parent, text, align, breakBefore, outline, displayName, fill, listStyleOfStyle) }
                        name = null; family = null
                    }
                    "text:list-style" -> listName = null
                }
            }
        }
    }

    private fun textProperties(p: XmlPullParser) = OdtText(
        bold = p.attr("fo:font-weight")?.let { it == "bold" || (it.toIntOrNull() ?: 400) >= 600 },
        italic = p.attr("fo:font-style")?.let { it == "italic" || it == "oblique" },
        underline = p.attr("style:text-underline-style")?.let { it != "none" },
        strike = p.attr("style:text-line-through-style")?.let { it != "none" },
        color = p.attr("fo:color")?.let { hexColor(it) } ?: p.attr("style:use-window-font-color")?.let { if (it == "true") 0 else null },
        highlight = p.attr("fo:background-color")?.let { if (it == "transparent") null else hexColor(it) },
        sizePt = p.attr("fo:font-size")?.let(::odfLengthPt),
        position = p.attr("style:text-position")?.let {
            when {
                it.startsWith("super") || it.startsWith("33%") -> TextPosition.Superscript
                it.startsWith("sub") || it.startsWith("-33%") -> TextPosition.Subscript
                else -> TextPosition.Normal
            }
        },
        monospace = p.attr("style:font-name")?.let { font -> MonospaceNames.any { font.contains(it, ignoreCase = true) } },
    )

    fun chain(name: String?): List<OdtStyle> {
        val out = ArrayList<OdtStyle>()
        var current = name
        while (current != null && out.size < 16) {
            val style = styles[current] ?: break
            out.add(style)
            current = style.parent
        }
        return out
    }

    fun text(name: String?): OdtText = chain(name).fold(OdtText()) { acc, s -> acc.over(s.text) }

    /** 1..6 when the paragraph style (or one it inherits) is a heading or title style. */
    fun headingOf(name: String?): Int? {
        for (s in chain(name)) {
            s.outlineLevel?.let { return it.coerceIn(1, 6) }
        }
        val names = listOfNotNull(name) + chain(name).mapNotNull { it.parent } + chain(name).mapNotNull { it.displayName }
        for (n in names) {
            val lower = n.lowercase().replace("_20_", " ")
            if (lower == "title") return 1
            if (lower == "subtitle") return 2
            if (lower.startsWith("heading")) lower.filter { it.isDigit() }.toIntOrNull()?.let { return it.coerceIn(1, 6) }
        }
        return null
    }

    companion object {
        val MonospaceNames = listOf("Courier", "Mono", "Consolas", "Menlo", "Liberation Mono", "DejaVu Sans Mono")
    }
}

/** Reads `office:text` into blocks, keeping list counters as it goes. */
private class OdtBodyReader(private val p: XmlPullParser, private val styles: OdtStyles) {
    /** Counters per list style, one per level. */
    private val counters = HashMap<String, IntArray>()

    fun read(): List<DocBlock> {
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == "office:text") return body("office:text", null, 0)
        }
        return emptyList()
    }

    /** Blocks until the end of [endTag]. [list] and [level] are set inside a list item, which
     * gives its first paragraph a marker. Unknown containers (sections, indexes) are walked
     * through. */
    private fun body(endTag: String, list: ListContext?, level: Int): List<DocBlock> {
        val blocks = ArrayList<DocBlock>()
        val start = p.depth
        while (true) {
            when (p.next()) {
                XmlPullParser.END_DOCUMENT -> return blocks
                XmlPullParser.END_TAG -> if (p.name == endTag && p.depth == start) return blocks
                XmlPullParser.START_TAG -> when (p.name) {
                    "text:p", "text:h" -> {
                        // An item's first paragraph carries its marker; any after it line up
                        // with its text, under an empty marker.
                        val marker = list?.let { if (it.markerUsed) ListMarker("", level) else it.marker }
                        list?.markerUsed = true
                        paragraph(blocks, marker)
                    }
                    "text:list" -> blocks.addAll(listBlock(list, level))
                    "table:table" -> blocks.add(table())
                    "text:sequence-decls", "office:forms", "text:tracked-changes", "office:annotation",
                    "text:index-title-template", "text:table-of-content-source", "text:alphabetical-index-source",
                    "text:illustration-index-source", "text:user-index-source" -> p.skipElement()
                }
            }
        }
    }

    /** The list a list item belongs to: its style, and whether the item's marker has been used by
     * its first paragraph yet. */
    private class ListContext(val styleName: String?, val marker: ListMarker?) {
        var markerUsed = false
    }

    private fun listBlock(parent: ListContext?, parentLevel: Int): List<DocBlock> {
        val level = if (parent == null) 0 else parentLevel + 1
        val styleName = p.attr("text:style-name") ?: parent?.styleName
        val continues = p.attr("text:continue-numbering") == "true" || p.attr("text:continue-list") != null
        // A new top-level list starts its numbering again, unless it says it continues.
        if (level == 0 && !continues && styleName != null) counters.remove(styleName)
        val blocks = ArrayList<DocBlock>()
        val start = p.depth
        while (true) {
            when (p.next()) {
                XmlPullParser.END_DOCUMENT -> return blocks
                XmlPullParser.END_TAG -> if (p.name == "text:list" && p.depth == start) return blocks
                XmlPullParser.START_TAG -> when (p.name) {
                    "text:list-item" -> {
                        val marker = marker(styleName, level, p.attr("text:start-value")?.toIntOrNull())
                        blocks.addAll(body("text:list-item", ListContext(styleName, marker), level))
                    }
                    "text:list-header" -> blocks.addAll(body("text:list-header", ListContext(styleName, null).also { it.markerUsed = true }, level))
                }
            }
        }
    }

    private fun marker(styleName: String?, level: Int, startValue: Int?): ListMarker {
        val levels = styleName?.let { styles.lists[it] }
        val definition = levels?.get(level)
        if (definition == null || definition.bullet != null) {
            return ListMarker(definition?.bullet?.let { bulletLabel(it, level) } ?: Bullets[level % Bullets.size], level)
        }
        val counts = counters.getOrPut(styleName) { IntArray(10) { lvl -> (levels[lvl]?.start ?: 1) - 1 } }
        val lvl = level.coerceIn(0, 9)
        counts[lvl] = startValue ?: (counts[lvl] + 1)
        for (deeper in lvl + 1 until 10) counts[deeper] = (levels[deeper]?.start ?: 1) - 1
        val label = definition.prefix + listNumber(counts[lvl], definition.format) + definition.suffix
        return ListMarker(label, level)
    }

    private fun paragraph(out: MutableList<DocBlock>, marker: ListMarker?) {
        val isHeading = p.name == "text:h"
        val endTag = p.name
        val styleName = p.attr("text:style-name")
        val outline = p.attr("text:outline-level")?.toIntOrNull()
        // The default size stays unset on runs: the viewer treats it as body size.
        val base = styles.text(styleName).over(styles.defaultText.copy(sizePt = null))
        val runs = ArrayList<TextRun>()
        val after = ArrayList<DocBlock>()
        readInline(endTag, base, null, runs, after)

        val chain = styles.chain(styleName)
        val heading = if (isHeading) (outline ?: styles.headingOf(styleName) ?: 1).coerceIn(1, 6) else styles.headingOf(styleName)
        val paragraph = DocParagraph(
            runs = mergeRuns(runs),
            heading = heading,
            align = chain.firstNotNullOfOrNull { it.align } ?: ParagraphAlign.Start,
            list = marker,
        )
        if (chain.any { it.breakBefore } && out.isNotEmpty()) out.add(DocPageBreak)
        if (!(paragraph.isEmpty && after.isNotEmpty() && marker == null)) out.add(paragraph)
        out.addAll(after)
    }

    /** Inline content until [endTag]: text, spans, links, spaces, tabs and breaks into [runs];
     * pictures and text boxes into [after]. */
    private fun readInline(endTag: String, style: OdtText, link: String?, runs: MutableList<TextRun>, after: MutableList<DocBlock>) {
        val start = p.depth
        while (true) {
            when (p.next()) {
                XmlPullParser.END_DOCUMENT -> return
                XmlPullParser.TEXT -> if (p.text.isNotEmpty()) runs.add(TextRun(collapse(p.text), style.toStyle(link)))
                XmlPullParser.END_TAG -> if (p.name == endTag && p.depth == start) return
                XmlPullParser.START_TAG -> when (p.name) {
                    "text:span" -> {
                        val spanStyle = styles.text(p.attr("text:style-name")).over(style)
                        readInline("text:span", spanStyle, link, runs, after)
                    }
                    "text:a" -> {
                        val href = p.attr("xlink:href")?.takeIf { !it.startsWith("#") }
                        readInline("text:a", styles.text(p.attr("text:style-name")).over(style), href ?: link, runs, after)
                    }
                    "text:s" -> runs.add(TextRun(" ".repeat(p.attr("text:c")?.toIntOrNull() ?: 1), style.toStyle(link)))
                    "text:tab" -> runs.add(TextRun("\t", style.toStyle(link)))
                    "text:line-break" -> runs.add(TextRun("\n", style.toStyle(link)))
                    "draw:frame" -> frame(after)
                    "text:note", "office:annotation", "text:bookmark-ref", "text:tracked-changes" -> p.skipElement()
                }
            }
        }
    }

    /** XML whitespace in ODF text collapses to single spaces; real runs of spaces are `text:s`. */
    private fun collapse(text: String): String =
        if (text.any { it == '\n' || it == '\r' || it == '\t' }) text.replace(Whitespace, " ") else text

    private fun frame(after: MutableList<DocBlock>) {
        val widthPt = odfLengthPt(p.attr("svg:width"))
        val heightPt = odfLengthPt(p.attr("svg:height"))
        var description: String? = p.attr("draw:name")
        val start = p.depth
        var image: String? = null
        while (true) {
            when (p.next()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.END_TAG -> if (p.name == "draw:frame" && p.depth == start) break
                XmlPullParser.TEXT -> {}
                XmlPullParser.START_TAG -> when (p.name) {
                    // A frame may carry several renditions of one picture; the first is enough.
                    "draw:image" -> if (image == null) image = p.attr("xlink:href")?.takeIf { !it.contains("://") }?.removePrefix("./")
                    "draw:text-box" -> after.addAll(body("draw:text-box", null, 0))
                    "svg:desc", "svg:title" -> {
                        if (p.next() == XmlPullParser.TEXT) description = p.text
                    }
                }
            }
        }
        image?.let { after.add(DocImage(it, widthPt, heightPt, description)) }
    }

    private fun table(): DocTable {
        val rows = ArrayList<List<DocTableCell>>()
        val start = p.depth
        while (true) {
            when (p.next()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.END_TAG -> if (p.name == "table:table" && p.depth == start) break
                XmlPullParser.START_TAG -> if (p.name == "table:table-row") rows.add(tableRow())
            }
        }
        return DocTable(rows)
    }

    private fun tableRow(): List<DocTableCell> {
        val cells = ArrayList<DocTableCell>()
        val start = p.depth
        while (true) {
            when (p.next()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.END_TAG -> if (p.name == "table:table-row" && p.depth == start) break
                XmlPullParser.START_TAG -> when (p.name) {
                    "table:table-cell" -> {
                        val span = p.attr("table:number-columns-spanned")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                        val repeat = (p.attr("table:number-columns-repeated")?.toIntOrNull() ?: 1).coerceIn(1, MaxRepeat)
                        val fill = styles.chain(p.attr("table:style-name")).firstNotNullOfOrNull { it.fill }
                        val blocks = body("table:table-cell", null, 0)
                        repeat(repeat) { cells.add(DocTableCell(blocks, span, fill)) }
                    }
                    "table:covered-table-cell" -> {
                        val repeat = (p.attr("table:number-columns-repeated")?.toIntOrNull() ?: 1).coerceIn(1, MaxRepeat)
                        p.skipElement()
                        // Covered by a cell to the left (column span) or above (row span): the
                        // spanning cell already accounts for columns, so only rows need a filler.
                        if (cells.isEmpty() || cells.last().colSpan == 1) repeat(repeat) { cells.add(DocTableCell(emptyList(), 1, null, covered = true)) }
                    }
                }
            }
        }
        return cells
    }

    private companion object {
        const val MaxRepeat = 64
        val Whitespace = Regex("[\\s]+")
    }
}
