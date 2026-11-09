package org.prauga.documents.util

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * A `.docx` (a zip of WordprocessingML parts) read for viewing into a [RichDocument]: paragraphs
 * with their styles resolved (headings, alignment, bold, italic, underline, strike, colors,
 * highlighting, sizes, superscript and subscript), numbered and bulleted lists, tables (with
 * spans, merged rows and fills), images, links (including Word's HYPERLINK fields), text boxes,
 * and page breaks. Tracked insertions show; tracked deletions don't. Footnotes, headers, footers
 * and comments aren't read.
 *
 * [file] must be a private copy: the [RichDocument] keeps it to read images from, and deletes it
 * when closed.
 */
fun parseDocx(file: File): RichDocument {
    var defaultSize = WordDefaultSizePt
    val blocks = ZipFile(file).use { zip ->
        fun part(name: String): InputStream? = zip.getEntry(name)?.let(zip::getInputStream)
        val document = zip.getEntry("word/document.xml") ?: error("Not a Word document")
        val rels = part("word/_rels/document.xml.rels")?.use(::readDocxRelationships).orEmpty()
        val styles = part("word/styles.xml")?.use(::readDocxStyles) ?: DocxStyles.Empty
        styles.defaults.sizePt?.let { defaultSize = it }
        val numbering = part("word/numbering.xml")?.use(::readNumbering).orEmpty()
        zip.getInputStream(document).use { DocxBodyReader(it, rels, styles, numbering).read() }
    }
    return RichDocument(blocks, file, defaultSize)
}

/** Word's own default font size, when a document doesn't set one. */
private const val WordDefaultSizePt = 11f

private fun newParser(input: InputStream): XmlPullParser = Xml.newPullParser().apply {
    setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
    setInput(input, null)
}

private fun XmlPullParser.attr(name: String): String? {
    for (i in 0 until attributeCount) if (getAttributeName(i) == name) return getAttributeValue(i)
    return null
}

/** `w:val` as a toggle: present without a value, or anything but "0"/"false"/"none", is on. */
private fun XmlPullParser.isOn(): Boolean = attr("w:val").let { it == null || (it != "0" && it != "false" && it != "none") }

/** Skips the element the parser is on, children and all. */
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

private class DocxRel(val target: String, val external: Boolean)

private fun readDocxRelationships(input: InputStream): Map<String, DocxRel> {
    val p = newParser(input)
    val map = HashMap<String, DocxRel>()
    while (p.next() != XmlPullParser.END_DOCUMENT) {
        if (p.eventType == XmlPullParser.START_TAG && p.name == "Relationship") {
            val id = p.attr("Id") ?: continue
            val target = p.attr("Target") ?: continue
            map[id] = DocxRel(target, p.attr("TargetMode") == "External")
        }
    }
    return map
}

/** Run properties as a style or run sets them: null means "not set here", so styles can be
 * layered — document defaults, then the paragraph style chain, then the run's. */
private data class RunProps(
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underline: Boolean? = null,
    val strike: Boolean? = null,
    /** 0 means explicitly "auto". */
    val color: Int? = null,
    val highlight: Int? = null,
    val sizePt: Float? = null,
    val position: TextPosition? = null,
    val monospace: Boolean? = null,
) {
    fun over(base: RunProps) = RunProps(
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

private class DocxStyle(
    val name: String?,
    val basedOn: String?,
    val run: RunProps,
    val align: ParagraphAlign?,
    val outlineLevel: Int?,
    val numId: String?,
    val numLevel: Int?,
)

private class DocxStyles(val byId: Map<String, DocxStyle>, val defaults: RunProps, val defaultParagraph: String?) {
    /** The style and those it's based on, nearest first, guarding against loops. */
    fun chain(id: String?): List<DocxStyle> {
        val out = ArrayList<DocxStyle>()
        var current = id
        while (current != null && out.size < 16) {
            val style = byId[current] ?: break
            out.add(style)
            current = style.basedOn
        }
        return out
    }

    fun runProps(id: String?): RunProps = chain(id).fold(RunProps()) { acc, s -> acc.over(s.run) }

    /** 1..6 for a heading style: by outline level, or a name like "heading 2" or "Title". */
    fun heading(id: String?): Int? {
        for (s in chain(id)) {
            s.outlineLevel?.let { return (it + 1).coerceIn(1, 6) }
            val name = s.name?.lowercase() ?: continue
            if (name == "title") return 1
            if (name == "subtitle") return 2
            if (name.startsWith("heading")) name.filter { it.isDigit() }.toIntOrNull()?.let { return it.coerceIn(1, 6) }
        }
        return null
    }

    companion object {
        val Empty = DocxStyles(emptyMap(), RunProps(), null)
    }
}

/** Reads the `w:rPr` the parser is on into [RunProps]. */
private fun readRunProps(p: XmlPullParser): RunProps {
    var props = RunProps()
    while (true) {
        val event = p.next()
        if (event == XmlPullParser.END_TAG && p.name == "w:rPr") break
        if (event == XmlPullParser.END_DOCUMENT) break
        if (event == XmlPullParser.START_TAG) props = runProp(p, props)
    }
    return props
}

/** [props] with the run property element the parser is on applied. */
private fun runProp(p: XmlPullParser, props: RunProps): RunProps = when (p.name) {
    "w:b" -> props.copy(bold = p.isOn())
    "w:i" -> props.copy(italic = p.isOn())
    "w:u" -> props.copy(underline = p.isOn())
    "w:strike", "w:dstrike" -> props.copy(strike = p.isOn())
    "w:color" -> props.copy(color = p.attr("w:val")?.let { if (it == "auto") 0 else hexColor(it) })
    "w:highlight" -> props.copy(highlight = HighlightColors[p.attr("w:val")])
    "w:shd" -> hexColor(p.attr("w:fill"))?.let { props.copy(highlight = it) } ?: props
    "w:sz" -> props.copy(sizePt = p.attr("w:val")?.toFloatOrNull()?.div(2f))
    "w:vertAlign" -> props.copy(
        position = when (p.attr("w:val")) {
            "superscript" -> TextPosition.Superscript
            "subscript" -> TextPosition.Subscript
            else -> TextPosition.Normal
        }
    )
    "w:rFonts" -> (p.attr("w:ascii") ?: p.attr("w:hAnsi"))?.let { font ->
        props.copy(monospace = MonospaceFonts.any { font.contains(it, ignoreCase = true) })
    } ?: props
    else -> props
}

private val MonospaceFonts = listOf("Courier", "Consolas", "Mono", "Menlo", "Lucida Console")

private fun readDocxStyles(input: InputStream): DocxStyles {
    val p = newParser(input)
    val styles = HashMap<String, DocxStyle>()
    var defaults = RunProps()
    var defaultParagraph: String? = null
    var inDefaults = false

    var id: String? = null
    var name: String? = null
    var basedOn: String? = null
    var run = RunProps()
    var align: ParagraphAlign? = null
    var outline: Int? = null
    var numId: String? = null
    var numLevel: Int? = null
    var isDefaultParagraph = false

    while (p.next() != XmlPullParser.END_DOCUMENT) {
        when (p.eventType) {
            XmlPullParser.START_TAG -> when (p.name) {
                "w:docDefaults" -> inDefaults = true
                "w:style" -> {
                    id = p.attr("w:styleId"); name = null; basedOn = null; run = RunProps(); align = null
                    outline = null; numId = null; numLevel = null
                    isDefaultParagraph = p.attr("w:type") == "paragraph" && p.attr("w:default") == "1"
                }
                "w:name" -> name = p.attr("w:val")
                "w:basedOn" -> basedOn = p.attr("w:val")
                "w:rPr" -> if (inDefaults) defaults = readRunProps(p) else if (id != null) run = readRunProps(p)
                "w:jc" -> align = paragraphAlign(p.attr("w:val"))
                "w:outlineLvl" -> outline = p.attr("w:val")?.toIntOrNull()?.takeIf { it < 9 }
                "w:numId" -> numId = p.attr("w:val")
                "w:ilvl" -> numLevel = p.attr("w:val")?.toIntOrNull()
            }
            XmlPullParser.END_TAG -> when (p.name) {
                "w:docDefaults" -> inDefaults = false
                "w:style" -> {
                    id?.let {
                        styles[it] = DocxStyle(name, basedOn, run, align, outline, numId, numLevel)
                        if (isDefaultParagraph) defaultParagraph = it
                    }
                    id = null
                }
            }
        }
    }
    return DocxStyles(styles, defaults, defaultParagraph)
}

private fun paragraphAlign(value: String?): ParagraphAlign? = when (value) {
    "center" -> ParagraphAlign.Center
    "right", "end" -> ParagraphAlign.End
    "both", "distribute" -> ParagraphAlign.Justify
    "left", "start" -> ParagraphAlign.Start
    else -> null
}

/** One level of a list definition: how its number is written, the text around it ("%1."), and
 * where it starts. */
private class NumberingLevel(val format: String, val text: String, val start: Int)

/** Numbering definitions by `numId`, each a list of levels. */
private fun readNumbering(input: InputStream): Map<String, List<NumberingLevel>> {
    val p = newParser(input)
    val abstract = HashMap<String, MutableMap<Int, NumberingLevel>>()
    val nums = HashMap<String, String>()
    var currentAbstract: String? = null
    var level = 0
    var format = "decimal"
    var text = "%1."
    var start = 1
    var currentNum: String? = null
    while (p.next() != XmlPullParser.END_DOCUMENT) {
        when (p.eventType) {
            XmlPullParser.START_TAG -> when (p.name) {
                "w:abstractNum" -> { currentAbstract = p.attr("w:abstractNumId"); abstract.getOrPut(currentAbstract ?: "") { HashMap() } }
                "w:lvl" -> { level = p.attr("w:ilvl")?.toIntOrNull() ?: 0; format = "decimal"; text = "%${level + 1}."; start = 1 }
                "w:numFmt" -> format = p.attr("w:val") ?: format
                "w:lvlText" -> text = p.attr("w:val") ?: text
                "w:start" -> start = p.attr("w:val")?.toIntOrNull() ?: 1
                "w:num" -> currentNum = p.attr("w:numId")
                "w:abstractNumId" -> currentNum?.let { num -> p.attr("w:val")?.let { nums[num] = it } }
            }
            XmlPullParser.END_TAG -> when (p.name) {
                "w:lvl" -> currentAbstract?.let { abstract[it]?.put(level, NumberingLevel(format, text, start)) }
                "w:abstractNum" -> currentAbstract = null
                "w:num" -> currentNum = null
            }
        }
    }
    return nums.mapValues { (_, abstractId) ->
        val levels = abstract[abstractId].orEmpty()
        (0..8).map { levels[it] ?: NumberingLevel("decimal", "%${it + 1}.", 1) }
    }
}

/** Reads `word/document.xml` into blocks, keeping list counters as it goes. */
private class DocxBodyReader(
    input: InputStream,
    private val rels: Map<String, DocxRel>,
    private val styles: DocxStyles,
    private val numbering: Map<String, List<NumberingLevel>>,
) {
    private val p = newParser(input)
    private val counters = HashMap<String, IntArray>()

    fun read(): List<DocBlock> {
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == "w:body") return body("w:body")
        }
        return emptyList()
    }

    /** Blocks until the end of [endTag]. Containers it doesn't know (content controls, custom
     * XML) are walked through, so their paragraphs still show. */
    private fun body(endTag: String): List<DocBlock> {
        val blocks = ArrayList<DocBlock>()
        while (true) {
            when (p.next()) {
                XmlPullParser.END_DOCUMENT -> return blocks
                XmlPullParser.END_TAG -> if (p.name == endTag) return blocks
                XmlPullParser.START_TAG -> when (p.name) {
                    "w:p" -> paragraph(blocks)
                    "w:tbl" -> blocks.add(table())
                    "w:sectPr", "mc:Fallback", "w:del" -> p.skipElement()
                }
            }
        }
    }

    private fun paragraph(out: MutableList<DocBlock>) {
        var styleId: String? = styles.defaultParagraph
        var align: ParagraphAlign? = null
        var numId: String? = null
        var numLevel: Int? = null
        var indent = 0
        var pageBreakBefore = false
        val runs = ArrayList<TextRun>()
        // Pictures and text boxes inside the paragraph, shown after its text.
        val after = ArrayList<DocBlock>()
        var link: String? = null
        var fieldLink: String? = null
        var fieldDepth = 0
        var inFieldResult = false
        var pageBreakAfter = false

        while (true) {
            when (p.next()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.END_TAG -> when (p.name) {
                    "w:p" -> break
                    "w:hyperlink" -> link = null
                }
                XmlPullParser.START_TAG -> when (p.name) {
                    "w:pPr" -> {
                        while (true) {
                            val e = p.next()
                            if (e == XmlPullParser.END_DOCUMENT || (e == XmlPullParser.END_TAG && p.name == "w:pPr")) break
                            if (e != XmlPullParser.START_TAG) continue
                            when (p.name) {
                                "w:pStyle" -> styleId = p.attr("w:val")
                                "w:jc" -> align = paragraphAlign(p.attr("w:val"))
                                "w:numId" -> numId = p.attr("w:val")
                                "w:ilvl" -> numLevel = p.attr("w:val")?.toIntOrNull()
                                "w:ind" -> indent = ((p.attr("w:left") ?: p.attr("w:start"))?.toIntOrNull() ?: 0) / TwipsPerIndent
                                "w:pageBreakBefore" -> pageBreakBefore = p.isOn()
                                "w:rPr", "w:sectPr" -> p.skipElement()
                            }
                        }
                    }
                    "w:hyperlink" -> link = p.attr("r:id")?.let { rels[it] }?.takeIf { it.external }?.target
                    "w:r" -> {
                        val run = run(styleId, link ?: fieldLink.takeIf { inFieldResult }, after)
                        when (run.fieldEvent) {
                            FieldEvent.Begin -> { fieldDepth++; inFieldResult = false }
                            FieldEvent.Separate -> inFieldResult = true
                            FieldEvent.End -> { fieldDepth--; if (fieldDepth <= 0) { fieldDepth = 0; fieldLink = null; inFieldResult = false } }
                            null -> {}
                        }
                        run.instruction?.let { instr ->
                            HyperlinkField.find(instr)?.let { fieldLink = it.groupValues[1] }
                        }
                        if (run.pageBreak) pageBreakAfter = true
                        runs.addAll(run.runs)
                    }
                    "w:del", "mc:Fallback" -> p.skipElement()
                }
            }
        }

        val styleChain = styles.chain(styleId)
        if (numId == null) {
            styleChain.firstOrNull { it.numId != null }?.let { numId = it.numId; if (numLevel == null) numLevel = it.numLevel }
        }
        // Word's "List Number 2" / "List Bullet 3" styles are separate one-level lists that only
        // look nested through their indent; their digit says how deep.
        val styleDepth = styleChain.firstNotNullOfOrNull { s -> s.name?.let { ListStyleDepth.matchEntire(it) }?.groupValues?.get(1)?.toIntOrNull() }
        val level = maxOf(numLevel ?: 0, (styleDepth ?: 1) - 1)
        val list = numId?.takeIf { it != "0" }?.let { listMarker(it, numLevel ?: 0)?.copy(level = level) }
        val paragraph = DocParagraph(
            runs = mergeRuns(runs),
            heading = styles.heading(styleId),
            align = align ?: styleChain.firstNotNullOfOrNull { it.align } ?: ParagraphAlign.Start,
            list = list,
            indentLevel = if (list != null) 0 else indent.coerceIn(0, MaxIndent),
        )
        if (pageBreakBefore && out.isNotEmpty()) out.add(DocPageBreak)
        // An empty paragraph that only held a picture or a text box isn't worth a blank line.
        if (!(paragraph.isEmpty && after.isNotEmpty() && list == null)) out.add(paragraph)
        out.addAll(after)
        if (pageBreakAfter) out.add(DocPageBreak)
    }

    private enum class FieldEvent { Begin, Separate, End }

    private class RunResult(
        val runs: List<TextRun>,
        val fieldEvent: FieldEvent?,
        val instruction: String?,
        val pageBreak: Boolean,
    )

    private fun run(paragraphStyle: String?, link: String?, after: MutableList<DocBlock>): RunResult {
        var direct = RunProps()
        var runStyle: String? = null
        val text = StringBuilder()
        val runs = ArrayList<TextRun>()
        var fieldEvent: FieldEvent? = null
        val instruction = StringBuilder()
        var pageBreak = false
        var inText = false
        var inInstruction = false

        fun style(): RunStyle =
            direct.over(styles.runProps(runStyle)).over(styles.runProps(paragraphStyle))
                .over(styles.defaults.copy(sizePt = null)).toStyle(link)

        fun flush() {
            if (text.isNotEmpty()) {
                runs.add(TextRun(text.toString(), style()))
                text.clear()
            }
        }

        while (true) {
            when (p.next()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.TEXT -> if (inText) text.append(p.text) else if (inInstruction) instruction.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "w:r" -> break
                    "w:t" -> inText = false
                    "w:instrText" -> inInstruction = false
                }
                XmlPullParser.START_TAG -> when (p.name) {
                    "w:rPr" -> {
                        // rStyle sits inside rPr; read both in one pass.
                        val start = p.depth
                        var props = RunProps()
                        while (true) {
                            val e = p.next()
                            if (e == XmlPullParser.END_DOCUMENT) break
                            if (e == XmlPullParser.END_TAG && p.depth == start && p.name == "w:rPr") break
                            if (e != XmlPullParser.START_TAG) continue
                            if (p.name == "w:rStyle") runStyle = p.attr("w:val")
                            else props = runProp(p, props)
                        }
                        direct = props
                    }
                    "w:t" -> inText = true
                    "w:instrText" -> inInstruction = true
                    "w:tab", "w:ptab" -> text.append('\t')
                    "w:br", "w:cr" -> {
                        if (p.attr("w:type") == "page") pageBreak = true else text.append('\n')
                    }
                    "w:noBreakHyphen" -> text.append('-')
                    "w:sym" -> p.attr("w:char")?.toIntOrNull(16)?.let { code ->
                        // Symbol-font characters live in the private use area; show a bullet.
                        text.append(if (code in 0xF000..0xF0FF) '•' else code.toChar())
                    }
                    "w:fldChar" -> fieldEvent = when (p.attr("w:fldCharType")) {
                        "begin" -> FieldEvent.Begin
                        "separate" -> FieldEvent.Separate
                        "end" -> FieldEvent.End
                        else -> null
                    }
                    "w:drawing" -> { flush(); drawing("w:drawing", after) }
                    "w:pict", "w:object" -> { flush(); drawing(p.name, after) }
                    "w:delText", "mc:Fallback" -> p.skipElement()
                }
            }
        }
        flush()
        return RunResult(runs, fieldEvent, instruction.toString().takeIf { it.isNotBlank() }, pageBreak)
    }

    /** A drawing, picture or embedded object: its image (by relationship id, sized from its
     * extent), and the paragraphs of any text box inside it. */
    private fun drawing(endTag: String, after: MutableList<DocBlock>) {
        val start = p.depth
        var widthPt: Float? = null
        var heightPt: Float? = null
        var description: String? = null
        var imageRel: String? = null
        while (true) {
            val e = p.next()
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.END_TAG && p.depth == start && p.name == endTag) break
            if (e != XmlPullParser.START_TAG) continue
            when (p.name) {
                "wp:extent" -> {
                    widthPt = p.attr("cx")?.toFloatOrNull()?.times(PointsPerEmu)
                    heightPt = p.attr("cy")?.toFloatOrNull()?.times(PointsPerEmu)
                }
                "wp:docPr" -> description = p.attr("descr")?.takeIf { it.isNotBlank() } ?: p.attr("title")
                "a:blip" -> imageRel = p.attr("r:embed") ?: imageRel
                "v:imagedata" -> imageRel = p.attr("r:id") ?: imageRel
                "w:txbxContent" -> after.addAll(body("w:txbxContent"))
                "mc:Fallback" -> p.skipElement()
            }
        }
        val rel = imageRel?.let { rels[it] } ?: return
        if (rel.external) return
        after.add(DocImage(resolvePart(rel.target), widthPt, heightPt, description))
    }

    private fun resolvePart(target: String): String =
        if (target.startsWith("/")) target.drop(1) else "word/" + target.removePrefix("./")

    private fun table(): DocTable {
        val rows = ArrayList<List<DocTableCell>>()
        while (true) {
            when (p.next()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.END_TAG -> if (p.name == "w:tbl") break
                XmlPullParser.START_TAG -> when (p.name) {
                    "w:tr" -> rows.add(tableRow())
                    "w:tblPr", "w:tblGrid" -> p.skipElement()
                }
            }
        }
        return DocTable(rows)
    }

    private fun tableRow(): List<DocTableCell> {
        val cells = ArrayList<DocTableCell>()
        while (true) {
            when (p.next()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.END_TAG -> if (p.name == "w:tr") break
                XmlPullParser.START_TAG -> when (p.name) {
                    "w:tc" -> cells.add(tableCell())
                    "w:trPr", "w:tblPrEx" -> p.skipElement()
                }
            }
        }
        return cells
    }

    private fun tableCell(): DocTableCell {
        var span = 1
        var fill: Int? = null
        var covered = false
        val blocks = ArrayList<DocBlock>()
        while (true) {
            when (p.next()) {
                XmlPullParser.END_DOCUMENT -> break
                XmlPullParser.END_TAG -> if (p.name == "w:tc") break
                XmlPullParser.START_TAG -> when (p.name) {
                    "w:gridSpan" -> span = p.attr("w:val")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    // vMerge without "restart" continues the cell above.
                    "w:vMerge" -> covered = p.attr("w:val") != "restart"
                    "w:shd" -> fill = hexColor(p.attr("w:fill"))
                    "w:p" -> paragraph(blocks)
                    "w:tbl" -> blocks.add(table())
                    "mc:Fallback", "w:del" -> p.skipElement()
                }
            }
        }
        return DocTableCell(if (covered) emptyList() else blocks, span, fill, covered)
    }

    /** The marker for the next item of list [numId] at [level], advancing its counter and
     * restarting deeper levels, as Word numbers nested lists. */
    private fun listMarker(numId: String, level: Int): ListMarker? {
        val levels = numbering[numId] ?: return null
        val lvl = level.coerceIn(0, 8)
        val counts = counters.getOrPut(numId) { IntArray(9) { levels[it].start - 1 } }
        counts[lvl]++
        for (deeper in lvl + 1 until 9) counts[deeper] = levels[deeper].start - 1
        val definition = levels[lvl]
        val label = if (definition.format == "bullet") {
            bulletLabel(definition.text, lvl)
        } else {
            NumberPlaceholder.replace(definition.text) { m ->
                val idx = (m.groupValues[1].toIntOrNull() ?: 1) - 1
                listNumber(counts.getOrElse(idx) { 1 }.coerceAtLeast(1), levels.getOrNull(idx)?.format ?: "decimal")
            }
        }
        return ListMarker(label, lvl)
    }

    private companion object {
        const val TwipsPerIndent = 720
        const val MaxIndent = 6
        val NumberPlaceholder = Regex("%(\\d)")
        val HyperlinkField = Regex("HYPERLINK\\s+\"([^\"]+)\"")
        val ListStyleDepth = Regex("List(?: Number| Bullet| Continue| Paragraph)? (\\d)", RegexOption.IGNORE_CASE)
    }
}

/** A bullet's character, or a plain one by level when Word used a symbol-font glyph that only
 * means something in that font. */
internal fun bulletLabel(text: String, level: Int): String {
    val ch = text.firstOrNull()
    if (ch == null || ch.code in 0xF000..0xF0FF || ch.isWhitespace()) return Bullets[level % Bullets.size]
    return text
}

internal val Bullets = listOf("•", "◦", "▪")

/** Adjacent runs with the same style joined, so the viewer builds fewer spans. */
internal fun mergeRuns(runs: List<TextRun>): List<TextRun> {
    if (runs.size < 2) return runs
    val out = ArrayList<TextRun>(runs.size)
    for (run in runs) {
        val last = out.lastOrNull()
        if (last != null && last.style == run.style) out[out.lastIndex] = TextRun(last.text + run.text, run.style)
        else out.add(run)
    }
    return out
}

private val HighlightColors: Map<String?, Int> = mapOf(
    "yellow" to 0xFFFFFF00.toInt(), "green" to 0xFF00FF00.toInt(), "cyan" to 0xFF00FFFF.toInt(),
    "magenta" to 0xFFFF00FF.toInt(), "blue" to 0xFF0000FF.toInt(), "red" to 0xFFFF0000.toInt(),
    "darkBlue" to 0xFF000080.toInt(), "darkCyan" to 0xFF008080.toInt(), "darkGreen" to 0xFF008000.toInt(),
    "darkMagenta" to 0xFF800080.toInt(), "darkRed" to 0xFF800000.toInt(), "darkYellow" to 0xFF808000.toInt(),
    "darkGray" to 0xFF808080.toInt(), "lightGray" to 0xFFC0C0C0.toInt(), "black" to 0xFF000000.toInt(),
    "white" to 0xFFFFFFFF.toInt(),
)
