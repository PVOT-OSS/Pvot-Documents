package org.prauga.documents.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.Closeable
import java.io.File
import java.util.zip.ZipFile

/** How a run of text looks. Colors are the document's own (ARGB), null where it leaves them to
 * the default. [sizePt] is the font size in points, null for the document's default. */
data class RunStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val color: Int? = null,
    val highlight: Int? = null,
    val sizePt: Float? = null,
    val position: TextPosition = TextPosition.Normal,
    val monospace: Boolean = false,
    val link: String? = null,
) {
    companion object {
        val Default = RunStyle()
    }
}

enum class TextPosition { Normal, Superscript, Subscript }

/** One stretch of text with one look. */
data class TextRun(val text: String, val style: RunStyle)

enum class ParagraphAlign { Start, Center, End, Justify }

/** A list item's marker, already worked out ("•", "3.", "b)"), and how deeply it's nested,
 * from 0. */
data class ListMarker(val label: String, val level: Int)

/** A part of a document, in reading order. */
sealed interface DocBlock

/** A paragraph: [heading] is 1..6 for a heading, null for body text. [indentLevel] is extra
 * indentation, in steps, for text indented without being a list. */
data class DocParagraph(
    val runs: List<TextRun>,
    val heading: Int? = null,
    val align: ParagraphAlign = ParagraphAlign.Start,
    val list: ListMarker? = null,
    val indentLevel: Int = 0,
) : DocBlock {
    val isEmpty: Boolean get() = runs.all { it.text.isBlank() }
}

/** A table cell: its own blocks (paragraphs, nested tables), how many columns it spans, and its
 * fill. A cell covered by one above it ([covered]) is drawn as part of that one. */
data class DocTableCell(
    val blocks: List<DocBlock>,
    val colSpan: Int = 1,
    val fill: Int? = null,
    val covered: Boolean = false,
)

data class DocTable(val rows: List<List<DocTableCell>>) : DocBlock {
    val columnCount: Int get() = rows.maxOfOrNull { row -> row.sumOf { it.colSpan } } ?: 0
}

/** A picture stored in the document's package at [path], with its intended size in points when
 * the document gives one. */
data class DocImage(val path: String, val widthPt: Float?, val heightPt: Float?, val description: String?) : DocBlock

data object DocPageBreak : DocBlock

/**
 * A parsed word-processor document, and the package it came from — kept open while it's viewed
 * so images are read only when they scroll into view, rather than all held in memory at once.
 * [close] deletes the private copy.
 */
class RichDocument(
    val blocks: List<DocBlock>,
    private val packageFile: File,
    /** The document's default font size in points; runs at this size leave [RunStyle.sizePt]
     * null, so a heading without a size of its own still looks like a heading. */
    val defaultSizePt: Float,
) : Closeable {
    private val zip by lazy { ZipFile(packageFile) }

    /** The picture at [path], decoded at no more than [maxWidthPx] wide. Null if it's missing or
     * not a format Android can decode (EMF and WMF drawings, for one). */
    @Synchronized
    fun image(path: String, maxWidthPx: Int): Bitmap? = runCatching {
        val entry = zip.getEntry(path) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidthPx) sample *= 2
        zip.getInputStream(entry).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }.getOrNull()

    override fun close() {
        runCatching { zip.close() }
        packageFile.delete()
    }
}

/** Points per EMU, the unit Office drawings are sized in (914,400 to the inch). */
internal const val PointsPerEmu = 72f / 914_400f

/** A list counter as a label in [format]: "decimal", "lowerLetter", "upperRoman" and so on, the
 * names DOCX uses (ODT's "1", "a", "I" are mapped to them). */
internal fun listNumber(n: Int, format: String): String = when (format) {
    "lowerLetter" -> letters(n).lowercase()
    "upperLetter" -> letters(n)
    "lowerRoman" -> roman(n).lowercase()
    "upperRoman" -> roman(n)
    "decimalZero" -> if (n < 10) "0$n" else n.toString()
    "none" -> ""
    else -> n.toString()
}

private fun letters(n: Int): String {
    val out = StringBuilder()
    var v = n
    while (v > 0) {
        val rem = (v - 1) % 26
        out.append('A' + rem)
        v = (v - 1) / 26
    }
    return out.reverse().toString()
}

private fun roman(n: Int): String {
    if (n <= 0 || n >= 4000) return n.toString()
    val values = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
    val symbols = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
    val out = StringBuilder()
    var v = n
    for (i in values.indices) while (v >= values[i]) { out.append(symbols[i]); v -= values[i] }
    return out.toString()
}

/** A hex color like "FF0000" or "#ff0000" as opaque ARGB, or null for "auto" and anything else. */
internal fun hexColor(value: String?): Int? {
    val hex = value?.removePrefix("#") ?: return null
    if (hex.length != 6) return null
    return hex.toIntOrNull(16)?.let { it or 0xFF000000.toInt() }
}
