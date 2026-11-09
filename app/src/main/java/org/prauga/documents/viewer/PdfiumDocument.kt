package org.prauga.documents.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfPage
import io.legere.pdfiumandroid.PdfiumCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** A page box in page-relative fractions: 0..1 across the page's displayed width and height, top
 * to bottom. Independent of how big the page is drawn, so it survives resizing and zoom. */
internal data class PageRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** A page's text as Pdfium extracted it: [text] has one UTF-16 unit per character index, and
 * [boxes] holds that character's box (null for characters Pdfium generated, like the spaces and
 * line breaks it inserts between runs, which have no box of their own). */
internal class PageText(val text: String, val boxes: List<PageRect?>)

internal data class SearchMatch(val pageIndex: Int, val rects: List<PageRect>)

/** One open PDF, rendered and searched in-process by Pdfium. Every Pdfium call runs on one
 * serial background dispatcher: Pdfium isn't thread-safe, and serialising also keeps a long search
 * from racing the renders the user is waiting on. [pageAspects] is width / height per page, as
 * displayed (Pdfium applies the page's own /Rotate). */
internal class PdfiumDocument private constructor(
    private val fd: ParcelFileDescriptor,
    private val doc: PdfDocument,
    val pageAspects: List<Float>,
) {
    private val pdfium = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + pdfium)

    // Opening a page parses its content stream, so keep the few most recently used ones open.
    private val openPages = object : LruCache<Int, PdfPage>(OpenPageCount) {
        override fun entryRemoved(evicted: Boolean, key: Int, oldValue: PdfPage, newValue: PdfPage?) {
            oldValue.close()
        }
    }

    // Full-page renders at list width, kept across scrolling so a page coming back into view
    // doesn't re-render. Bounded by bytes, not count: one page bitmap is several megabytes.
    private val pageBitmaps = object : LruCache<Int, Bitmap>(pageCacheBytes()) {
        override fun sizeOf(key: Int, value: Bitmap) = value.allocationByteCount
    }

    private val pageTexts = LruCache<Int, PageText>(TextCacheCount)

    val pageCount: Int get() = pageAspects.size

    /** The cached full-page render at [widthPx], if there is one — lets a page that scrolls back
     * into view draw immediately instead of flashing blank while [renderPage] runs. */
    fun cachedPage(index: Int, widthPx: Int): Bitmap? =
        pageBitmaps.get(index)?.takeIf { it.width == widthPx }

    /** The whole page at [widthPx] by [heightPx], form fields and annotations included. */
    suspend fun renderPage(index: Int, widthPx: Int, heightPx: Int): Bitmap = withContext(pdfium) {
        cachedPage(index, widthPx)?.let { return@withContext it }
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        page(index).renderPageBitmap(bitmap, 0, 0, widthPx, heightPx, renderAnnot = true)
        pageBitmaps.put(index, bitmap)
        bitmap
    }

    /** Just the part of the page that's on screen, rendered as if the whole page were
     * [fullWidthPx] by [fullHeightPx]. This is what keeps a zoomed-in page sharp: a whole page at
     * 4x would be hundreds of megabytes, but the visible part is never bigger than the screen. */
    suspend fun renderRegion(
        index: Int,
        fullWidthPx: Int,
        fullHeightPx: Int,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
    ): Bitmap = withContext(pdfium) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        page(index).renderPageBitmap(bitmap, -left, -top, fullWidthPx, fullHeightPx, renderAnnot = true)
        bitmap
    }

    suspend fun pageText(index: Int): PageText = withContext(pdfium) {
        pageTexts.get(index)?.let { return@withContext it }
        val page = page(index)
        val toPage = pageMapper(page)
        val text = page.openTextPage().use { textPage ->
            val count = textPage.textPageCountChars().coerceAtLeast(0)
            val chars = if (count > 0) textPage.textPageGetText(0, count).orEmpty() else ""
            val boxes = List(count) { i ->
                textPage.textPageGetCharBox(i)
                    ?.takeIf { it.width() != 0f || it.height() != 0f }
                    ?.let(toPage)
            }
            PageText(chars, boxes)
        }
        pageTexts.put(index, text)
        text
    }

    /** Every case-insensitive match of [query], in page order, each with the rectangles it covers
     * (more than one when a match wraps onto the next line). */
    suspend fun search(query: String): List<SearchMatch> = withContext(pdfium) {
        if (query.isBlank()) return@withContext emptyList()
        buildList {
            for (index in 0 until pageCount) {
                val page = page(index)
                val toPage = pageMapper(page)
                page.openTextPage().use { textPage ->
                    textPage.findStart(query, emptySet(), 0)?.use { find ->
                        while (find.findNext()) {
                            val start = find.getSchResultIndex()
                            val length = find.getSchCount()
                            val rects = (0 until textPage.textPageCountRects(start, length))
                                .mapNotNull { textPage.textPageGetRect(it) }
                                .map(toPage)
                            if (rects.isNotEmpty()) add(SearchMatch(index, rects))
                        }
                    }
                }
            }
        }
    }

    /** Releases the document once any render or search already queued has finished. */
    fun close() {
        scope.launch {
            openPages.evictAll()
            pageBitmaps.evictAll()
            doc.close()
            fd.close()
            scope.cancel()
        }
    }

    private fun page(index: Int): PdfPage =
        openPages.get(index) ?: (doc.openPage(index) ?: throw IOException("Page $index failed to load"))
            .also { openPages.put(index, it) }

    /** Maps Pdfium page-space rectangles (points, y up, possibly rotated or offset by the crop
     * box) to [PageRect] fractions. Pdfium's own page-to-device mapping is integer-valued, so it's
     * sampled once at three points on a huge virtual device and the affine transform reused for
     * every rectangle, rather than calling into native code per character. */
    private fun pageMapper(page: PdfPage): (RectF) -> PageRect {
        val size = MapperDeviceSize
        val origin = page.mapPageCoordsToDevice(0, 0, size, size, 0, 0.0, 0.0)
        val alongX = page.mapPageCoordsToDevice(0, 0, size, size, 0, MapperStep, 0.0)
        val alongY = page.mapPageCoordsToDevice(0, 0, size, size, 0, 0.0, MapperStep)
        val xx = (alongX.x - origin.x) / MapperStep
        val xy = (alongX.y - origin.y) / MapperStep
        val yx = (alongY.x - origin.x) / MapperStep
        val yy = (alongY.y - origin.y) / MapperStep
        fun mapX(x: Float, y: Float) = ((origin.x + x * xx + y * yx) / size).toFloat()
        fun mapY(x: Float, y: Float) = ((origin.y + x * xy + y * yy) / size).toFloat()
        return { r ->
            val xs = floatArrayOf(mapX(r.left, r.top), mapX(r.right, r.bottom))
            val ys = floatArrayOf(mapY(r.left, r.top), mapY(r.right, r.bottom))
            PageRect(xs.min(), ys.min(), xs.max(), ys.max())
        }
    }

    companion object {
        private const val OpenPageCount = 4
        private const val TextCacheCount = 12
        private const val MapperDeviceSize = 1_000_000
        private const val MapperStep = 1000.0

        private fun pageCacheBytes(): Int =
            (Runtime.getRuntime().maxMemory() / 6).coerceAtMost(96L * 1024 * 1024).toInt()

        /** Opens [uri] through its file descriptor rather than reading it into memory, so a large
         * scanned document isn't copied whole into the heap just to be viewed. */
        suspend fun open(context: Context, uri: Uri): PdfiumDocument = withContext(Dispatchers.IO) {
            val fd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IOException("Could not open $uri")
            try {
                val doc = PdfiumCore(context).newDocument(fd)
                // Sizes in tenths of a point, read without opening any page; only the ratio is used.
                val aspects = doc.getPageSizes(PageSizeDpi).map { size ->
                    (size.width.toFloat() / size.height).takeIf { it.isFinite() && it > 0f } ?: DefaultAspect
                }
                PdfiumDocument(fd, doc, aspects)
            } catch (e: Exception) {
                fd.close()
                throw e
            }
        }

        private const val PageSizeDpi = 720
        private const val DefaultAspect = 0.7071f
    }
}
