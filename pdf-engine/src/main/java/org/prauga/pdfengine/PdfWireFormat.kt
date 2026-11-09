package org.prauga.pdfengine

import androidx.compose.ui.graphics.Color
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One drawing instruction from a page's render program. Coordinates are page user-space
 * (origin bottom-left, y-up, 1/72in units); [org.prauga.documents.viewer.PdfEngineView] is
 * responsible for flipping to a y-down canvas and applying the current zoom/pan.
 *
 * Mirrors the opcodes documented in `pdf-engine/src/main/rust/src/wire.rs` — keep both in sync.
 */
sealed interface PdfDrawOp {
    data class SetFillColor(val color: Color) : PdfDrawOp
    data class SetStrokeColor(val color: Color) : PdfDrawOp
    data class SetLineWidth(val width: Float) : PdfDrawOp
    data class MoveTo(val x: Float, val y: Float) : PdfDrawOp
    data class LineTo(val x: Float, val y: Float) : PdfDrawOp
    data class CurveTo(
        val x1: Float, val y1: Float,
        val x2: Float, val y2: Float,
        val x3: Float, val y3: Float,
    ) : PdfDrawOp
    data object CloseSubpath : PdfDrawOp
    data class FillPath(val evenOdd: Boolean) : PdfDrawOp
    data object StrokePath : PdfDrawOp
    data class FillAndStroke(val evenOdd: Boolean) : PdfDrawOp
    data class SetClipRect(val x: Float, val y: Float, val w: Float, val h: Float) : PdfDrawOp
    data object SaveState : PdfDrawOp
    data object RestoreState : PdfDrawOp

    /** A single glyph's baseline origin, approximate device-independent size, and the Unicode
     * text it represents (possibly empty, when no encoding/ToUnicode mapping was found). */
    data class DrawGlyph(
        val x: Float,
        val y: Float,
        val fontSize: Float,
        val advance: Float,
        val bold: Boolean,
        val italic: Boolean,
        val text: String,
    ) : PdfDrawOp

    data class DrawImage(val x: Float, val y: Float, val w: Float, val h: Float, val imageIndex: Int) : PdfDrawOp

    /** The `n` operator: discards the path built since the last paint op without painting it
     * (typically following `re W n` to clip without filling). Must reset the renderer's path
     * buffer the same as a paint op would — otherwise this path's contours silently carry over
     * and get unioned into the next fill. */
    data object EndPath : PdfDrawOp
}

object PdfWireFormat {
    private const val OP_SET_FILL_COLOR = 0
    private const val OP_SET_STROKE_COLOR = 1
    private const val OP_SET_LINE_WIDTH = 2
    private const val OP_MOVE_TO = 3
    private const val OP_LINE_TO = 4
    private const val OP_CURVE_TO = 5
    private const val OP_CLOSE_SUBPATH = 6
    private const val OP_FILL_PATH = 7
    private const val OP_STROKE_PATH = 8
    private const val OP_FILL_AND_STROKE = 9
    private const val OP_SET_CLIP_RECT = 10
    private const val OP_SAVE_STATE = 11
    private const val OP_RESTORE_STATE = 12
    private const val OP_DRAW_GLYPH = 13
    private const val OP_DRAW_IMAGE = 14
    private const val OP_END_PATH = 15
    private const val OP_END = 255

    fun decode(wire: ByteArray): List<PdfDrawOp> {
        val buf = ByteBuffer.wrap(wire).order(ByteOrder.LITTLE_ENDIAN)
        val ops = ArrayList<PdfDrawOp>()
        while (buf.hasRemaining()) {
            val op = buf.get().toInt() and 0xFF
            when (op) {
                OP_SET_FILL_COLOR -> ops += PdfDrawOp.SetFillColor(readColor(buf))
                OP_SET_STROKE_COLOR -> ops += PdfDrawOp.SetStrokeColor(readColor(buf))
                OP_SET_LINE_WIDTH -> ops += PdfDrawOp.SetLineWidth(buf.float)
                OP_MOVE_TO -> ops += PdfDrawOp.MoveTo(buf.float, buf.float)
                OP_LINE_TO -> ops += PdfDrawOp.LineTo(buf.float, buf.float)
                OP_CURVE_TO -> ops += PdfDrawOp.CurveTo(
                    buf.float, buf.float, buf.float, buf.float, buf.float, buf.float,
                )
                OP_CLOSE_SUBPATH -> ops += PdfDrawOp.CloseSubpath
                OP_FILL_PATH -> ops += PdfDrawOp.FillPath(buf.get().toInt() != 0)
                OP_STROKE_PATH -> ops += PdfDrawOp.StrokePath
                OP_FILL_AND_STROKE -> ops += PdfDrawOp.FillAndStroke(buf.get().toInt() != 0)
                OP_SET_CLIP_RECT -> ops += PdfDrawOp.SetClipRect(buf.float, buf.float, buf.float, buf.float)
                OP_SAVE_STATE -> ops += PdfDrawOp.SaveState
                OP_RESTORE_STATE -> ops += PdfDrawOp.RestoreState
                OP_DRAW_GLYPH -> {
                    val x = buf.float
                    val y = buf.float
                    val fontSize = buf.float
                    val advance = buf.float
                    val style = buf.get().toInt() and 0xFF
                    val len = (buf.short.toInt() and 0xFFFF)
                    val bytes = ByteArray(len)
                    buf.get(bytes)
                    ops += PdfDrawOp.DrawGlyph(
                        x, y, fontSize, advance,
                        bold = (style and 0x1) != 0,
                        italic = (style and 0x2) != 0,
                        text = String(bytes, Charsets.UTF_8),
                    )
                }
                OP_DRAW_IMAGE -> ops += PdfDrawOp.DrawImage(
                    buf.float, buf.float, buf.float, buf.float, buf.short.toInt() and 0xFFFF,
                )
                OP_END_PATH -> ops += PdfDrawOp.EndPath
                OP_END -> return ops
                else -> return ops // unrecognized trailing byte — stop rather than misparse
            }
        }
        return ops
    }

    private fun readColor(buf: ByteBuffer): Color {
        val r = (buf.get().toInt() and 0xFF) / 255f
        val g = (buf.get().toInt() and 0xFF) / 255f
        val b = (buf.get().toInt() and 0xFF) / 255f
        val a = (buf.get().toInt() and 0xFF) / 255f
        return Color(r, g, b, a)
    }
}
