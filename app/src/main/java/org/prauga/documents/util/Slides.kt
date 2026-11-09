package org.prauga.documents.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.Closeable
import java.io.File
import java.util.zip.ZipFile

/** A rectangle on a slide, in points from its top-left corner, with a clockwise rotation in
 * degrees about its centre. */
data class SlideFrame(val x: Float, val y: Float, val width: Float, val height: Float, val rotation: Float = 0f)

/** How a shape is filled: a solid ARGB color, a picture stretched over it, or nothing. */
sealed interface SlideFill {
    data class Solid(val color: Int) : SlideFill
    data class Picture(val path: String) : SlideFill
}

/** An outline: ARGB color and width in points. */
data class SlideLine(val color: Int, val widthPt: Float)

enum class SlideTextAnchor { Top, Middle, Bottom }

/** A paragraph of slide text: [bullet] is the marker drawn before it (already worked out, "•" or
 * "3."), [level] its indent level from 0, and [spaceBeforePt] the gap above it. */
data class SlideParagraph(
    val runs: List<TextRun>,
    val align: ParagraphAlign = ParagraphAlign.Start,
    val bullet: String? = null,
    val level: Int = 0,
    val spaceBeforePt: Float = 0f,
    /** Line height as a multiple of the font's normal line height. */
    val lineSpacing: Float = 1f,
    /** Size, in points, of an empty paragraph's line, so blank lines keep their height. */
    val emptySizePt: Float? = null,
    /** Where the text starts, in points from the shape's inner left edge, and where the first
     * line (and its bullet) starts relative to that: negative for a hanging bullet. */
    val marginLeftPt: Float = 0f,
    val indentPt: Float = 0f,
)

/** Text inside a shape: its paragraphs, where they sit vertically, and the shape's inner
 * margins in points. [fontScale] is PowerPoint's "shrink text on overflow" factor. */
data class SlideText(
    val paragraphs: List<SlideParagraph>,
    val anchor: SlideTextAnchor = SlideTextAnchor.Top,
    val insetLeft: Float = 7.2f,
    val insetTop: Float = 3.6f,
    val insetRight: Float = 7.2f,
    val insetBottom: Float = 3.6f,
    val fontScale: Float = 1f,
    val wrap: Boolean = true,
)

/** The outline a shape draws: the preset shapes slides mostly use. Anything else is drawn as
 * its bounding rectangle. */
enum class SlideGeometry { Rectangle, RoundRectangle, Ellipse, Line, Triangle, RightArrow, Chevron, Diamond }

/** Something drawn on a slide, in drawing order. */
sealed interface SlideShape {
    val frame: SlideFrame

    /** A shape with an outline, a fill, and optionally text inside. */
    data class Shape(
        override val frame: SlideFrame,
        val geometry: SlideGeometry,
        val fill: SlideFill?,
        val line: SlideLine?,
        val text: SlideText?,
        val flipH: Boolean = false,
        val flipV: Boolean = false,
    ) : SlideShape

    /** A picture from the package, with the fractions cropped off each side. */
    data class Picture(
        override val frame: SlideFrame,
        val path: String,
        val cropLeft: Float = 0f,
        val cropTop: Float = 0f,
        val cropRight: Float = 0f,
        val cropBottom: Float = 0f,
        val description: String? = null,
    ) : SlideShape

    /** A table: column widths and row heights in points, and its cells row by row. */
    data class Table(
        override val frame: SlideFrame,
        val columnWidths: List<Float>,
        val rowHeights: List<Float>,
        val cells: List<List<SlideTableCell>>,
    ) : SlideShape
}

data class SlideTableCell(
    val text: SlideText,
    val fill: Int?,
    val colSpan: Int = 1,
    val rowSpan: Int = 1,
    val covered: Boolean = false,
)

/** One slide: what's behind everything, then its shapes, those from its master and layout
 * included, in drawing order. */
data class Slide(val background: SlideFill?, val shapes: List<SlideShape>)

/**
 * A parsed presentation and the package it came from, kept open while it's viewed so pictures
 * are read only as their slides come into view. [close] deletes the private copy.
 */
class Presentation(
    val slides: List<Slide>,
    /** Slide size in points. */
    val width: Float,
    val height: Float,
    private val packageFile: File,
) : Closeable {
    private val zip by lazy { ZipFile(packageFile) }

    /** The picture at [path], decoded at no more than [maxWidthPx] wide; null if it's missing or
     * a format Android can't decode (such as EMF and WMF drawings). */
    @Synchronized
    fun image(path: String, maxWidthPx: Int): Bitmap? = runCatching {
        val entry = zip.getEntry(path) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidthPx.coerceAtLeast(1)) sample *= 2
        zip.getInputStream(entry).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }.getOrNull()

    override fun close() {
        runCatching { zip.close() }
        packageFile.delete()
    }
}
