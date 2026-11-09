package org.prauga.documents.util

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * An OpenDocument spreadsheet (`.ods`, a zip with the sheets in `content.xml`) read for viewing.
 * OpenDocument stores each cell's displayed text alongside its value, so numbers and dates show
 * exactly as LibreOffice formatted them. Cell styles (bold, italic, colors, fill, alignment,
 * wrapping), column widths, hidden rows and columns, and merged cells are kept; charts, images and
 * notes are not.
 */
fun parseOds(file: File): Workbook = ZipFile(file).use { zip ->
    val content = zip.getEntry("content.xml") ?: error("Not an OpenDocument spreadsheet")
    zip.getInputStream(content).use(::readContent)
}

private fun XmlPullParser.attr(name: String): String? {
    for (i in 0 until attributeCount) if (getAttributeName(i) == name) return getAttributeValue(i)
    return null
}

/** A length like "2.258cm", "0.889in", "64pt" or "1.2mm" in points. */
private fun lengthPt(value: String?): Float? {
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

/** Column widths in Excel's "characters": about 7 points per character at the default font. */
private fun pointsToChars(pt: Float): Float = pt / PointsPerChar

private const val PointsPerChar = 5.25f

private fun parseColor(value: String?): Int? {
    if (value == null || !value.startsWith('#') || value.length != 7) return null
    return value.drop(1).toIntOrNull(16)?.let { it or 0xFF000000.toInt() }
}

private fun readContent(input: InputStream): Workbook {
    val p = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        setInput(input, null)
    }

    // Automatic styles, by name: cell styles become indices into [styles]; column and row styles
    // carry widths and heights.
    val styles = arrayListOf(CellStyle.Default)
    val cellStyleIndex = HashMap<String, Int>()
    val columnWidth = HashMap<String, Float>()
    val rowHeight = HashMap<String, Float>()
    val hiddenTableStyles = HashSet<String>()
    var styleName: String? = null
    var styleFamily: String? = null
    var styleDraft = CellStyle.Default

    val sheets = ArrayList<Sheet>()
    var truncated = false
    var cellBudget = MaxCells

    // Sheet being read.
    var sheetName = ""
    var sheetHidden = false
    var rows = ArrayList<SheetRow>()
    var colWidths = HashMap<Int, Float>()
    var columnDefaultStyle = HashMap<Int, Int>()
    var hiddenRows = HashSet<Int>()
    var merges = ArrayList<CellRange>()
    var nextColumn = 0
    var rowIndex = 0

    // Row being read.
    var rowRepeat = 1
    var rowHidden = false
    var rowHeightPt: Float? = null
    var cells = ArrayList<SheetCell>()
    var col = 0

    // Cell being read.
    var cellRepeat = 1
    var cellStyle = 0
    var cellNumeric = false
    var colSpan = 1
    var rowSpan = 1
    val text = StringBuilder()
    var paragraphs = 0
    var inParagraph = 0
    var inAnnotation = 0
    var inCell = false

    fun finishCell() {
        val value = text.toString()
        val style = cellStyle.takeIf { it != 0 } ?: columnDefaultStyle[col] ?: 0
        val worthKeeping = value.isNotEmpty() || styles[style].fill != null
        if (worthKeeping) {
            // A repeated cell with content really is repeated; empty repeats are just a gap, and
            // the last cell of a row is often "repeated" out to column 1024.
            val count = if (value.isEmpty() && cellRepeat > MaxColumnRepeat) 1 else minOf(cellRepeat, MaxColumnRepeat)
            for (k in 0 until count) cells.add(SheetCell(col + k, value, style, cellNumeric))
            if (colSpan > 1 || rowSpan > 1) merges.add(CellRange(rowIndex, col, rowIndex + rowSpan - 1, col + colSpan - 1))
        }
        col += cellRepeat
    }

    loop@ while (p.next() != XmlPullParser.END_DOCUMENT) {
        when (p.eventType) {
            XmlPullParser.START_TAG -> when (p.name) {
                "style:style" -> {
                    styleName = p.attr("style:name")
                    styleFamily = p.attr("style:family")
                    styleDraft = CellStyle.Default
                }
                "style:table-cell-properties" -> if (styleFamily == "table-cell") {
                    styleDraft = styleDraft.copy(
                        fill = parseColor(p.attr("fo:background-color")),
                        wrap = p.attr("fo:wrap-option") == "wrap",
                    )
                }
                "style:text-properties" -> if (styleFamily == "table-cell") {
                    styleDraft = styleDraft.copy(
                        bold = p.attr("fo:font-weight").let { it == "bold" || (it?.toIntOrNull() ?: 400) >= 600 },
                        italic = p.attr("fo:font-style") == "italic",
                        underline = p.attr("style:text-underline-style").let { it != null && it != "none" },
                        strike = p.attr("style:text-line-through-style").let { it != null && it != "none" },
                        // Black is "automatic" in practice; leave it to the viewer.
                        textColor = parseColor(p.attr("fo:color"))?.takeIf { it != 0xFF000000.toInt() },
                    )
                }
                "style:paragraph-properties" -> if (styleFamily == "table-cell") {
                    styleDraft = styleDraft.copy(
                        align = when (p.attr("fo:text-align")) {
                            "start", "left" -> CellAlign.Start
                            "center" -> CellAlign.Center
                            "end", "right" -> CellAlign.End
                            else -> CellAlign.Auto
                        }
                    )
                }
                "style:table-properties" -> if (styleFamily == "table" && p.attr("table:display") == "false") {
                    styleName?.let(hiddenTableStyles::add)
                }
                "style:table-column-properties" -> if (styleFamily == "table-column") {
                    lengthPt(p.attr("style:column-width"))?.let { w -> styleName?.let { columnWidth[it] = pointsToChars(w) } }
                }
                "style:table-row-properties" -> if (styleFamily == "table-row") {
                    lengthPt(p.attr("style:row-height"))?.let { h -> styleName?.let { rowHeight[it] = h } }
                }
                "table:table" -> {
                    sheetName = p.attr("table:name").orEmpty()
                    sheetHidden = p.attr("table:style-name") in hiddenTableStyles
                    rows = ArrayList(); colWidths = HashMap(); columnDefaultStyle = HashMap()
                    hiddenRows = HashSet(); merges = ArrayList()
                    nextColumn = 0; rowIndex = 0
                }
                "table:table-column" -> {
                    val repeat = p.attr("table:number-columns-repeated")?.toIntOrNull() ?: 1
                    val hidden = p.attr("table:visibility").let { it == "collapse" || it == "filter" }
                    val width = if (hidden) 0f else p.attr("table:style-name")?.let { columnWidth[it] }
                    val defaultStyle = p.attr("table:default-cell-style-name")?.let { cellStyleIndex[it] }
                    for (k in 0 until minOf(repeat, MaxColumnRepeat)) {
                        width?.let { colWidths[nextColumn + k] = it }
                        defaultStyle?.let { columnDefaultStyle[nextColumn + k] = it }
                    }
                    nextColumn += repeat
                }
                "table:table-row" -> {
                    rowRepeat = p.attr("table:number-rows-repeated")?.toIntOrNull() ?: 1
                    rowHidden = p.attr("table:visibility").let { it == "collapse" || it == "filter" }
                    rowHeightPt = p.attr("table:style-name")?.let { rowHeight[it] }
                    cells = ArrayList()
                    col = 0
                }
                "table:table-cell", "table:covered-table-cell" -> {
                    inCell = true
                    cellRepeat = p.attr("table:number-columns-repeated")?.toIntOrNull() ?: 1
                    cellStyle = p.attr("table:style-name")?.let { cellStyleIndex[it] } ?: 0
                    cellNumeric = p.attr("office:value-type") in NumericTypes
                    colSpan = p.attr("table:number-columns-spanned")?.toIntOrNull() ?: 1
                    rowSpan = p.attr("table:number-rows-spanned")?.toIntOrNull() ?: 1
                    text.clear()
                    paragraphs = 0
                }
                "office:annotation" -> inAnnotation++
                "text:p" -> if (inCell && inAnnotation == 0) {
                    if (paragraphs > 0) text.append('\n')
                    paragraphs++
                    inParagraph++
                }
                "text:s" -> if (inParagraph > 0 && inAnnotation == 0) {
                    repeat(p.attr("text:c")?.toIntOrNull() ?: 1) { text.append(' ') }
                }
                "text:tab" -> if (inParagraph > 0 && inAnnotation == 0) text.append('\t')
                "text:line-break" -> if (inParagraph > 0 && inAnnotation == 0) text.append('\n')
            }
            XmlPullParser.TEXT -> if (inParagraph > 0 && inAnnotation == 0) text.append(p.text)
            XmlPullParser.END_TAG -> when (p.name) {
                "style:style" -> {
                    val name = styleName
                    if (styleFamily == "table-cell" && name != null) {
                        cellStyleIndex[name] = styles.size
                        styles.add(styleDraft)
                    }
                    styleName = null; styleFamily = null
                }
                "office:annotation" -> inAnnotation--
                "text:p" -> if (inParagraph > 0) inParagraph--
                "table:table-cell", "table:covered-table-cell" -> {
                    if (inCell) finishCell()
                    inCell = false
                }
                "table:table-row" -> {
                    if (rowHidden) for (k in 0 until minOf(rowRepeat, MaxRowRepeat)) hiddenRows.add(rowIndex + k)
                    if (cells.isNotEmpty()) {
                        // Repeated rows with content are rare (a filled band); empty ones are the
                        // "rest of the sheet" filler, often a million rows long.
                        for (k in 0 until minOf(rowRepeat, MaxRowRepeat)) {
                            val copy = if (k == 0) cells else cells.map { SheetCell(it.col, it.text, it.style, it.numeric) }
                            rows.add(SheetRow(rowIndex + k, copy, rowHeightPt))
                            cellBudget -= copy.size
                        }
                        if (cellBudget <= 0) {
                            truncated = true
                            sheets.add(buildSheet(sheetName, rows, styles, colWidths, DefaultColWidthChars, hiddenRows, merges))
                            break@loop
                        }
                    }
                    rowIndex += rowRepeat
                }
                "table:table" -> {
                    if (!sheetHidden) sheets.add(buildSheet(sheetName, rows, styles, colWidths, DefaultColWidthChars, hiddenRows, merges))
                }
            }
        }
    }
    return Workbook(sheets, styles, truncated)
}

private val NumericTypes = setOf("float", "percentage", "currency", "date", "time")

/** Caps on OpenDocument's run-length repeats: they compress whole empty sheet areas, and expanding
 * a million-row filler would only waste memory. */
private const val MaxColumnRepeat = 1024
private const val MaxRowRepeat = 1024
