package org.prauga.pdfengine

import android.graphics.Bitmap

data class PdfPageSize(val width: Float, val height: Float, val rotateDegrees: Int)

data class PdfRect(val x: Float, val y: Float, val width: Float, val height: Float)

/** A decoded page: its draw program plus a lazy accessor for the images it referenced. */
class PdfPage internal constructor(
    val ops: List<PdfDrawOp>,
    private val doc: PdfDocument,
    private val pageIndex: Int,
) {
    fun imageBitmap(imageIndex: Int): Bitmap? = doc.imageBitmap(pageIndex, imageIndex)
}

/**
 * A single open PDF document backed by the native Rust engine. Not thread-safe — the native
 * handle has no internal locking, so callers should confine a given instance to one thread (or
 * synchronize externally), matching how [PdfView][org.prauga.documents.viewer.PdfEngineView]
 * uses it from a single Compose `LaunchedEffect`.
 */
class PdfDocument private constructor(private var handle: Long) : AutoCloseable {
    val pageCount: Int get() = PdfNative.nativePageCount(handle)

    fun pageSize(index: Int): PdfPageSize {
        val arr = PdfNative.nativePageSize(handle, index)
        return PdfPageSize(arr.getOrElse(0) { 612f }, arr.getOrElse(1) { 792f }, arr.getOrElse(2) { 0f }.toInt())
    }

    fun renderPage(index: Int): PdfPage {
        val wire = PdfNative.nativeRenderPage(handle, index)
        return PdfPage(PdfWireFormat.decode(wire), this, index)
    }

    /** Case-insensitive substring search within one page; re-renders the page internally. */
    fun searchPage(index: Int, query: String): List<PdfRect> {
        if (query.isBlank()) return emptyList()
        val flat = PdfNative.nativeSearchPage(handle, index, query)
        return (flat.indices step 4)
            .filter { it + 3 < flat.size }
            .map { i -> PdfRect(flat[i], flat[i + 1], flat[i + 2], flat[i + 3]) }
    }

    internal fun imageBitmap(pageIndex: Int, imageIndex: Int): Bitmap? {
        val info = PdfNative.nativeImageInfo(handle, pageIndex, imageIndex)
        val width = info.getOrElse(0) { 0f }.toInt()
        val height = info.getOrElse(1) { 0f }.toInt()
        if (width <= 0 || height <= 0) return null
        val pixels = PdfNative.nativeImagePixels(handle, pageIndex, imageIndex)
        if (pixels.size < width * height * 4) return null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val intPixels = IntArray(width * height)
        for (i in intPixels.indices) {
            val o = i * 4
            val r = pixels[o].toInt() and 0xFF
            val g = pixels[o + 1].toInt() and 0xFF
            val b = pixels[o + 2].toInt() and 0xFF
            val a = pixels[o + 3].toInt() and 0xFF
            intPixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        bitmap.setPixels(intPixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    override fun close() {
        if (handle != 0L) {
            PdfNative.nativeClose(handle)
            handle = 0L
        }
    }

    companion object {
        /** Returns `null` if the bytes aren't a PDF this engine can parse at all (corrupt file,
         * unsupported encryption, etc.) rather than throwing, since a bad file on disk is an
         * expected case for a document reader, not a programming error. */
        fun open(bytes: ByteArray): PdfDocument? {
            val handle = PdfNative.nativeOpen(bytes)
            return if (handle == 0L) null else PdfDocument(handle)
        }
    }
}
