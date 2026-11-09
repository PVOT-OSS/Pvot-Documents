package org.prauga.documents.util

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * An `.xlsx` workbook (a zip of SpreadsheetML parts) read for viewing: each visible sheet's cell
 * values as Excel last saved and displayed them, number formats applied, with the cell styling a
 * reader needs — bold, italic, colors, fills, alignment, wrapping — plus column widths, hidden rows
 * and columns, and merged cells. Formulas aren't recalculated: Excel stores each formula's last
 * result next to it, and that's what shows. Charts, images and comments aren't read.
 *
 * [file] must be a local copy: the parts sit in no fixed order in the zip (shared strings often
 * come after the sheets that use them), so it's read by random access.
 */
fun parseXlsx(file: File): Workbook = ZipFile(file).use { zip ->
    fun part(name: String): InputStream? = zip.getEntry(name)?.let(zip::getInputStream)

    val workbook = part("xl/workbook.xml")?.use(::readWorkbookPart) ?: error("Not an Excel workbook")
    val targets = part("xl/_rels/workbook.xml.rels")?.use(::readRelationships).orEmpty()
    val theme = part("xl/theme/theme1.xml")?.use(::readThemeColors).orEmpty()
    val stylesPart = part("xl/styles.xml")?.use { readStyles(it, theme) } ?: XlsxStyles.Empty
    val strings = part("xl/sharedStrings.xml")?.use(::readSharedStrings).orEmpty()

    var cellBudget = MaxCells
    var truncated = false
    val sheets = workbook.sheets.mapNotNull { ref ->
        if (ref.hidden) return@mapNotNull null
        val target = targets[ref.relId] ?: return@mapNotNull null
        // Chart sheets and dialog sheets have no cells.
        if (!target.contains("worksheets/")) return@mapNotNull null
        val path = if (target.startsWith("/")) target.drop(1) else "xl/$target"
        val stream = part(path) ?: return@mapNotNull null
        val parsed = stream.use { readWorksheet(it, ref.name, strings, stylesPart, workbook.date1904, cellBudget) }
        cellBudget -= parsed.cellsRead
        if (parsed.truncated) truncated = true
        parsed.sheet
    }
    Workbook(sheets, stylesPart.cellStyles, truncated)
}

private class SheetRef(val name: String, val relId: String, val hidden: Boolean)
private class WorkbookPart(val sheets: List<SheetRef>, val date1904: Boolean)

private fun parser(input: InputStream): XmlPullParser = Xml.newPullParser().apply {
    setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
    setInput(input, null)
}

/** Element name without its namespace prefix, so "x:sheet" and "sheet" read the same. Most files
 * use no prefix, and then nothing is allocated. */
private val XmlPullParser.local: String
    get() {
        val n = name
        val colon = n.indexOf(':')
        return if (colon < 0) n else n.substring(colon + 1)
    }

/** The attribute [name], with or without a namespace prefix; compared in place, since this runs
 * several times for each of hundreds of thousands of cells. */
private fun XmlPullParser.attr(name: String): String? {
    for (i in 0 until attributeCount) {
        val n = getAttributeName(i)
        if (n == name) return getAttributeValue(i)
        val colon = n.indexOf(':')
        if (colon >= 0 && n.length - colon - 1 == name.length && n.regionMatches(colon + 1, name, 0, name.length)) {
            return getAttributeValue(i)
        }
    }
    return null
}

private fun readWorkbookPart(input: InputStream): WorkbookPart {
    val p = parser(input)
    val sheets = mutableListOf<SheetRef>()
    var date1904 = false
    while (p.next() != XmlPullParser.END_DOCUMENT) {
        if (p.eventType != XmlPullParser.START_TAG) continue
        when (p.local) {
            "workbookPr" -> date1904 = p.attr("date1904").let { it == "1" || it == "true" }
            "sheet" -> sheets.add(
                SheetRef(
                    name = p.attr("name").orEmpty(),
                    relId = p.attr("r:id") ?: p.attr("id").orEmpty(),
                    hidden = p.attr("state").let { it == "hidden" || it == "veryHidden" },
                )
            )
        }
    }
    return WorkbookPart(sheets, date1904)
}

private fun readRelationships(input: InputStream): Map<String, String> {
    val p = parser(input)
    val map = HashMap<String, String>()
    while (p.next() != XmlPullParser.END_DOCUMENT) {
        if (p.eventType == XmlPullParser.START_TAG && p.local == "Relationship") {
            val id = p.attr("Id") ?: continue
            map[id] = p.attr("Target") ?: continue
        }
    }
    return map
}

/** Every `<si>`'s text in order, rich-text runs joined, phonetic hints left out. */
private fun readSharedStrings(input: InputStream): List<String> {
    val p = parser(input)
    val strings = ArrayList<String>()
    val current = StringBuilder()
    var inText = false
    var phonetic = 0
    while (p.next() != XmlPullParser.END_DOCUMENT) {
        when (p.eventType) {
            XmlPullParser.START_TAG -> when (p.local) {
                "si" -> current.clear()
                "t" -> inText = phonetic == 0
                "rPh" -> phonetic++
            }
            XmlPullParser.TEXT -> if (inText) current.append(p.text)
            XmlPullParser.END_TAG -> when (p.local) {
                "t" -> inText = false
                "rPh" -> phonetic--
                "si" -> strings.add(current.toString())
            }
        }
    }
    return strings
}

/** The theme's twelve colors, in the order cell colors index them: Excel swaps the first two
 * pairs, so theme index 0 is the light background and 1 the dark text. */
private fun readThemeColors(input: InputStream): List<Int> {
    val p = parser(input)
    val byName = HashMap<String, Int>()
    var current: String? = null
    var inScheme = false
    while (p.next() != XmlPullParser.END_DOCUMENT) {
        when (p.eventType) {
            XmlPullParser.START_TAG -> when (p.local) {
                "clrScheme" -> inScheme = true
                "dk1", "lt1", "dk2", "lt2", "accent1", "accent2", "accent3", "accent4", "accent5",
                "accent6", "hlink", "folHlink" -> if (inScheme) current = p.local
                "srgbClr" -> current?.let { name -> p.attr("val")?.let { byName[name] = 0xFF000000.toInt() or it.toInt(16) } }
                "sysClr" -> current?.let { name ->
                    (p.attr("lastClr") ?: p.attr("val")?.let { if (it == "window") "FFFFFF" else "000000" })
                        ?.let { byName[name] = 0xFF000000.toInt() or it.toInt(16) }
                }
            }
            XmlPullParser.END_TAG -> when (p.local) {
                "clrScheme" -> inScheme = false
                current -> current = null
            }
        }
    }
    val order = listOf("lt1", "dk1", "lt2", "dk2", "accent1", "accent2", "accent3", "accent4", "accent5", "accent6", "hlink", "folHlink")
    return order.map { byName[it] ?: 0xFF000000.toInt() }
}

private class XlsxFont(val bold: Boolean, val italic: Boolean, val underline: Boolean, val strike: Boolean, val color: Int?)

private class XlsxStyles(val cellStyles: List<CellStyle>, val numberFormats: List<String>) {
    companion object {
        val Empty = XlsxStyles(listOf(CellStyle.Default), listOf("General"))
    }
}

/** `styles.xml`'s cell formats (`cellXfs`) resolved into [CellStyle]s and number format codes,
 * one per style index a cell's `s` attribute names. */
private fun readStyles(input: InputStream, theme: List<Int>): XlsxStyles {
    val p = parser(input)
    val customFormats = HashMap<Int, String>()
    val fonts = ArrayList<XlsxFont>()
    val fills = ArrayList<Int?>()
    val styles = ArrayList<CellStyle>()
    val formats = ArrayList<String>()

    var section = ""
    // Font being read.
    var bold = false; var italic = false; var underline = false; var strike = false; var fontColor: Int? = null
    // Fill being read.
    var solid = false; var fillColor: Int? = null
    // Cell format being read.
    var xfFormat = 0; var xfFont = 0; var xfFill = 0; var align = CellAlign.Auto; var wrap = false

    while (p.next() != XmlPullParser.END_DOCUMENT) {
        when (p.eventType) {
            XmlPullParser.START_TAG -> when (p.local) {
                "numFmt" -> p.attr("numFmtId")?.toIntOrNull()?.let { id -> customFormats[id] = p.attr("formatCode").orEmpty() }
                "fonts", "fills", "cellXfs", "cellStyleXfs", "dxfs" -> section = p.local
                "font" -> if (section == "fonts") { bold = false; italic = false; underline = false; strike = false; fontColor = null }
                "b" -> if (section == "fonts") bold = p.attr("val").let { it == null || it == "1" || it == "true" }
                "i" -> if (section == "fonts") italic = p.attr("val").let { it == null || it == "1" || it == "true" }
                "u" -> if (section == "fonts") underline = p.attr("val") != "none"
                "strike" -> if (section == "fonts") strike = p.attr("val").let { it == null || it == "1" || it == "true" }
                "color" -> if (section == "fonts") fontColor = resolveColor(p, theme)
                "fill" -> if (section == "fills") { solid = false; fillColor = null }
                "patternFill" -> if (section == "fills") solid = p.attr("patternType").let { it != null && it != "none" }
                "fgColor" -> if (section == "fills") fillColor = resolveColor(p, theme)
                "xf" -> if (section == "cellXfs") {
                    xfFormat = p.attr("numFmtId")?.toIntOrNull() ?: 0
                    xfFont = p.attr("fontId")?.toIntOrNull() ?: 0
                    xfFill = p.attr("fillId")?.toIntOrNull() ?: 0
                    align = CellAlign.Auto; wrap = false
                }
                "alignment" -> if (section == "cellXfs") {
                    align = when (p.attr("horizontal")) {
                        "left" -> CellAlign.Start
                        "center", "centerContinuous" -> CellAlign.Center
                        "right" -> CellAlign.End
                        else -> CellAlign.Auto
                    }
                    wrap = p.attr("wrapText").let { it == "1" || it == "true" }
                }
            }
            XmlPullParser.END_TAG -> when (p.local) {
                "fonts", "fills", "cellXfs", "cellStyleXfs", "dxfs" -> section = ""
                "font" -> if (section == "fonts") fonts.add(XlsxFont(bold, italic, underline, strike, fontColor))
                "fill" -> if (section == "fills") fills.add(if (solid) fillColor else null)
                "xf" -> if (section == "cellXfs") {
                    val font = fonts.getOrNull(xfFont)
                    styles.add(
                        CellStyle(
                            bold = font?.bold ?: false,
                            italic = font?.italic ?: false,
                            underline = font?.underline ?: false,
                            strike = font?.strike ?: false,
                            textColor = font?.color,
                            fill = fills.getOrNull(xfFill),
                            align = align,
                            wrap = wrap,
                        )
                    )
                    formats.add(customFormats[xfFormat] ?: BuiltInNumberFormats[xfFormat] ?: "General")
                }
            }
        }
    }
    if (styles.isEmpty()) return XlsxStyles.Empty
    return XlsxStyles(styles, formats)
}

/** A `color`/`fgColor` element as ARGB, or null for "automatic" (the theme's own text or
 * background — left to the viewer, which knows whether it's showing light or dark). */
private fun resolveColor(p: XmlPullParser, theme: List<Int>): Int? {
    if (p.attr("auto").let { it == "1" || it == "true" }) return null
    val base = p.attr("rgb")?.let { rgb ->
        val hex = rgb.takeLast(8).padStart(8, 'F')
        hex.toLongOrNull(16)?.toInt()?.let { it or 0xFF000000.toInt() }
    } ?: p.attr("theme")?.toIntOrNull()?.let { index ->
        // Theme 0 and 1 are the document's own background and text: leave those to the viewer.
        if (index <= 1) return null
        theme.getOrNull(index)
    } ?: p.attr("indexed")?.toIntOrNull()?.let { index ->
        // 64 and 65 are the system foreground and background: automatic.
        IndexedColors.getOrNull(index)
    } ?: return null
    val tint = p.attr("tint")?.toDoubleOrNull() ?: 0.0
    return if (tint == 0.0) base else applyTint(base, tint)
}

/** Excel's tint: lighten toward white (positive) or darken toward black (negative), in HSL
 * lightness. */
private fun applyTint(argb: Int, tint: Double): Int {
    val hsl = FloatArray(3)
    androidx.core.graphics.ColorUtils.colorToHSL(argb, hsl)
    val l = hsl[2].toDouble()
    hsl[2] = (if (tint < 0) l * (1 + tint) else l * (1 - tint) + tint).toFloat().coerceIn(0f, 1f)
    return androidx.core.graphics.ColorUtils.HSLToColor(hsl)
}

private class ParsedSheet(val sheet: Sheet, val cellsRead: Int, val truncated: Boolean)

private fun readWorksheet(
    input: InputStream,
    name: String,
    strings: List<String>,
    styles: XlsxStyles,
    date1904: Boolean,
    budget: Int,
): ParsedSheet {
    val p = parser(input)
    val rows = ArrayList<SheetRow>()
    val colWidths = HashMap<Int, Float>()
    val hiddenRows = HashSet<Int>()
    val merges = ArrayList<CellRange>()
    var defaultWidth = DefaultColWidthChars
    var cellsRead = 0
    var truncated = false

    var rowIndex = -1
    var rowHeight: Float? = null
    var cells = ArrayList<SheetCell>()
    var nextCol = 0
    // Cell being read.
    var cellCol = 0; var cellType = "n"; var cellStyle = 0
    val value = StringBuilder()
    var inValue = false
    var inInline = false

    loop@ while (p.next() != XmlPullParser.END_DOCUMENT) {
        when (p.eventType) {
            XmlPullParser.START_TAG -> when (p.local) {
                "sheetFormatPr" -> p.attr("defaultColWidth")?.toFloatOrNull()?.let { defaultWidth = it }
                    ?: p.attr("baseColWidth")?.toFloatOrNull()?.let { defaultWidth = it + 0.71f }
                "col" -> {
                    val min = (p.attr("min")?.toIntOrNull() ?: continue@loop) - 1
                    val max = (p.attr("max")?.toIntOrNull() ?: continue@loop) - 1
                    val hidden = p.attr("hidden").let { it == "1" || it == "true" }
                    val width = if (hidden) 0f else p.attr("width")?.toFloatOrNull() ?: continue@loop
                    // Formatting applied to whole columns spans to column 16384; only keep what a
                    // sheet could plausibly show.
                    for (c in min..minOf(max, min + MaxColumnSpan)) colWidths[c] = width
                }
                "row" -> {
                    rowIndex = (p.attr("r")?.toIntOrNull()?.minus(1)) ?: (rowIndex + 1)
                    rowHeight = p.attr("ht")?.toFloatOrNull()
                    if (p.attr("hidden").let { it == "1" || it == "true" }) hiddenRows.add(rowIndex)
                    cells = ArrayList()
                    nextCol = 0
                }
                "c" -> {
                    cellCol = p.attr("r")?.let(::columnOf)?.takeIf { it >= 0 } ?: nextCol
                    cellType = p.attr("t") ?: "n"
                    cellStyle = p.attr("s")?.toIntOrNull() ?: 0
                    value.clear()
                }
                "v" -> inValue = true
                "is" -> inInline = true
                "t" -> if (inInline) inValue = true
                "mergeCell" -> p.attr("ref")?.let(::parseRange)?.let(merges::add)
            }
            XmlPullParser.TEXT -> if (inValue) value.append(p.text)
            XmlPullParser.END_TAG -> when (p.local) {
                "v" -> inValue = false
                "t" -> if (inInline) inValue = false
                "is" -> inInline = false
                "c" -> {
                    val raw = value.toString()
                    val text = when (cellType) {
                        "s" -> raw.trim().toIntOrNull()?.let { strings.getOrNull(it) }.orEmpty()
                        "str", "inlineStr", "e" -> raw
                        "b" -> if (raw.trim() == "1") "TRUE" else "FALSE"
                        "d" -> raw
                        else -> parseNumber(raw)?.let {
                            formatSheetNumber(it, styles.numberFormats.getOrNull(cellStyle) ?: "General", date1904)
                        } ?: raw
                    }
                    val numeric = cellType == "n" && raw.isNotBlank()
                    if (text.isNotEmpty() || cellStyle != 0) {
                        cells.add(SheetCell(cellCol, text, cellStyle.coerceIn(0, styles.cellStyles.lastIndex), numeric))
                    }
                    nextCol = cellCol + 1
                }
                "row" -> {
                    if (cells.isNotEmpty()) {
                        cells.sortBy { it.col }
                        rows.add(SheetRow(rowIndex, cells, rowHeight))
                        cellsRead += cells.size
                        if (cellsRead >= budget) {
                            truncated = true
                            break@loop
                        }
                    }
                }
            }
        }
    }
    val sheet = buildSheet(name, rows, styles.cellStyles, colWidths, defaultWidth, hiddenRows, merges)
    return ParsedSheet(sheet, cellsRead, truncated)
}

/** [raw] as a number, or null. Not [String.toDoubleOrNull], which screens the text with a regular
 * expression first: across a few hundred thousand cells that alone took seconds. */
private fun parseNumber(raw: String): Double? {
    if (raw.isEmpty()) return null
    return try {
        java.lang.Double.parseDouble(raw)
    } catch (_: NumberFormatException) {
        null
    }
}

/** How many columns a single `<col>` range is allowed to fill in, past its first. */
private const val MaxColumnSpan = 1024

/** Excel's legacy 64-color palette, which older files index into. */
private val IndexedColors: List<Int> = listOf(
    0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
    0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
    0x800000, 0x008000, 0x000080, 0x808000, 0x800080, 0x008080, 0xC0C0C0, 0x808080,
    0x9999FF, 0x993366, 0xFFFFCC, 0xCCFFFF, 0x660066, 0xFF8080, 0x0066CC, 0xCCCCFF,
    0x000080, 0xFF00FF, 0xFFFF00, 0x00FFFF, 0x800080, 0x800000, 0x008080, 0x0000FF,
    0x00CCFF, 0xCCFFFF, 0xCCFFCC, 0xFFFF99, 0x99CCFF, 0xFF99CC, 0xCC99FF, 0xFFCC99,
    0x3366FF, 0x33CCCC, 0x99CC00, 0xFFCC00, 0xFF9900, 0xFF6600, 0x666699, 0x969696,
    0x003366, 0x339966, 0x003300, 0x333300, 0x993300, 0x993366, 0x333399, 0x333333,
).map { it or 0xFF000000.toInt() }
