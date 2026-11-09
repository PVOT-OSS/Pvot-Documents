package org.prauga.documents.util

/** How a cell's text sits across its width. [Auto] is the spreadsheet default: numbers to the
 * end, text to the start. */
enum class CellAlign { Auto, Start, Center, End }

/** A cell's look, as the file set it. Colors are the document's own (ARGB), null where the file
 * leaves them to the default, so the viewer can use the theme's colors there instead. */
data class CellStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val textColor: Int? = null,
    val fill: Int? = null,
    val align: CellAlign = CellAlign.Auto,
    val wrap: Boolean = false,
) {
    companion object {
        val Default = CellStyle()
    }
}

/** One cell with something to show. [text] is what the spreadsheet app would display (numbers
 * already formatted); [numeric] decides the default alignment; [style] indexes
 * [Workbook.styles]. */
class SheetCell(val col: Int, val text: String, val style: Int, val numeric: Boolean)

/** A row's cells, sparse and in column order. */
class SheetRow(val index: Int, val cells: List<SheetCell>, val heightPt: Float?)

/** A rectangle of cells shown as one, inclusive at both ends. */
data class CellRange(val firstRow: Int, val firstCol: Int, val lastRow: Int, val lastCol: Int)

/**
 * One sheet: its non-empty [rows] (sparse, in order), and the size of the area worth showing,
 * trimmed to the last row and column that hold something. Column widths are in "characters" of the
 * default font, as both formats store or can be converted to; hidden columns have width 0.
 */
class Sheet(
    val name: String,
    val rows: List<SheetRow>,
    val rowCount: Int,
    val colCount: Int,
    val colWidths: Map<Int, Float>,
    val defaultColWidth: Float,
    val hiddenRows: Set<Int>,
    val merges: List<CellRange>,
) {
    private val byIndex: Map<Int, SheetRow> = rows.associateBy { it.index }

    fun row(index: Int): SheetRow? = byIndex[index]

    fun colWidth(col: Int): Float = colWidths[col] ?: defaultColWidth
}

/** A whole spreadsheet. [truncated] when it was too large to read in full, in which case only its
 * first [MaxCells] cells are shown. */
class Workbook(val sheets: List<Sheet>, val styles: List<CellStyle>, val truncated: Boolean)

/** Past this many cells a workbook stops loading: each cell is an object in memory, and a phone has
 * far less of it than the desktop the sheet was made on. */
const val MaxCells = 2_000_000

/** Excel's default column width, in characters, when a sheet doesn't say. */
internal const val DefaultColWidthChars = 8.43f

/** "A".."Z", "AA".. for zero-based [col]. */
fun columnName(col: Int): String {
    val name = StringBuilder()
    var n = col + 1
    while (n > 0) {
        val rem = (n - 1) % 26
        name.append('A' + rem)
        n = (n - 1) / 26
    }
    return name.reverse().toString()
}

/** Zero-based column of a cell reference like "AB12", or -1. */
internal fun columnOf(ref: String): Int {
    var col = 0
    var any = false
    for (ch in ref) {
        if (ch !in 'A'..'Z' && ch !in 'a'..'z') break
        col = col * 26 + (ch.uppercaseChar() - 'A' + 1)
        any = true
    }
    return if (any) col - 1 else -1
}

/** Zero-based row of a cell reference like "AB12", or -1. */
internal fun rowOf(ref: String): Int =
    ref.dropWhile { it.isLetter() || it == '$' }.filter { it.isDigit() }.toIntOrNull()?.minus(1) ?: -1

/** "A1:C3" as a range, or null. */
internal fun parseRange(ref: String): CellRange? {
    val parts = ref.replace("$", "").split(':')
    if (parts.size != 2) return null
    val r1 = rowOf(parts[0]); val c1 = columnOf(parts[0])
    val r2 = rowOf(parts[1]); val c2 = columnOf(parts[1])
    if (r1 < 0 || c1 < 0 || r2 < 0 || c2 < 0) return null
    return CellRange(minOf(r1, r2), minOf(c1, c2), maxOf(r1, r2), maxOf(c1, c2))
}

/** A [Sheet] from parsed rows, trimmed to the cells that hold text or a fill, and with merges and
 * hidden rows kept only inside that area. */
internal fun buildSheet(
    name: String,
    rows: List<SheetRow>,
    styles: List<CellStyle>,
    colWidths: Map<Int, Float>,
    defaultColWidth: Float,
    hiddenRows: Set<Int>,
    merges: List<CellRange>,
): Sheet {
    var lastRow = -1
    var lastCol = -1
    val kept = ArrayList<SheetRow>(rows.size)
    for (row in rows) {
        val cells = row.cells.filter { it.text.isNotEmpty() || styles.getOrNull(it.style)?.fill != null }
        if (cells.isEmpty()) continue
        kept.add(if (cells.size == row.cells.size) row else SheetRow(row.index, cells, row.heightPt))
        lastRow = maxOf(lastRow, row.index)
        lastCol = maxOf(lastCol, cells.last().col)
    }
    for (m in merges) {
        if (m.firstRow <= lastRow && m.firstCol <= lastCol) {
            lastCol = maxOf(lastCol, minOf(m.lastCol, lastCol + MergeOverhang))
        }
    }
    return Sheet(
        name = name,
        rows = kept,
        rowCount = lastRow + 1,
        colCount = lastCol + 1,
        colWidths = colWidths,
        defaultColWidth = defaultColWidth,
        hiddenRows = hiddenRows.filterTo(HashSet()) { it <= lastRow },
        merges = merges.filter { it.firstRow <= lastRow && it.firstCol <= lastCol },
    )
}

/** How far a merge starting inside the used area may widen it: a title merged across the page
 * should show in full, but not drag in a thousand empty columns. */
private const val MergeOverhang = 32
