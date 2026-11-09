package org.prauga.pdfengine

import android.graphics.Bitmap

data class PdfPageSize(val width: Float, val height: Float, val rotateDegrees: Int)

data class PdfRect(val x: Float, val y: Float, val width: Float, val height: Float)

/** A decoded page: its draw program plus a lazy, cached accessor for the images it referenced.
 * Without the cache, an image-heavy page (a scanned document, say) would cross the JNI boundary
 * and rebuild a full-resolution [Bitmap] from raw pixel bytes on *every* draw call — recomposition,
 * scroll, zoom, a search highlight blinking, anything — rather than once per page load. */
class PdfPage internal constructor(
    val ops: List<PdfDrawOp>,
    private val doc: PdfDocument,
    private val pageIndex: Int,
) {
    private val bitmapCache = HashMap<Int, Bitmap?>()

    fun imageBitmap(imageIndex: Int): Bitmap? =
        bitmapCache.getOrPut(imageIndex) { doc.imageBitmap(pageIndex, imageIndex) }
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

        // Scanned-page images routinely arrive at full camera/scanner resolution (e.g. 4000x3000,
        // a 48MB ARGB_8888 bitmap) even though a phone screen only ever shows them at a fraction
        // of that. Keeping several such bitmaps alive at once (one per cached page) is enough
        // retained memory to cause GC-driven jank while scrolling, on top of the per-frame cost of
        // drawing them — so subsample down to a resolution the display can actually use.
        val sample = maxOf(1, (maxOf(width, height) + MaxImageDimension - 1) / MaxImageDimension)
        val outWidth = width / sample
        val outHeight = height / sample
        if (outWidth <= 0 || outHeight <= 0) return null

        val bitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val intPixels = IntArray(outWidth * outHeight)
        for (oy in 0 until outHeight) {
            val rowBase = (oy * sample) * width
            for (ox in 0 until outWidth) {
                val o = (rowBase + ox * sample) * 4
                val r = pixels[o].toInt() and 0xFF
                val g = pixels[o + 1].toInt() and 0xFF
                val b = pixels[o + 2].toInt() and 0xFF
                val a = pixels[o + 3].toInt() and 0xFF
                intPixels[oy * outWidth + ox] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        bitmap.setPixels(intPixels, 0, outWidth, 0, 0, outWidth, outHeight)
        return bitmap
    }

    override fun close() {
        if (handle != 0L) {
            PdfNative.nativeClose(handle)
            handle = 0L
        }
    }

    companion object {
        private const val MaxImageDimension = 2048

        /** Returns `null` if the bytes aren't a PDF this engine can parse at all (corrupt file,
         * unsupported encryption, etc.) rather than throwing, since a bad file on disk is an
         * expected case for a document reader, not a programming error. */
        fun open(bytes: ByteArray): PdfDocument? {
            val handle = PdfNative.nativeOpen(bytes)
            return if (handle == 0L) null else PdfDocument(handle)
        }
    }
}
