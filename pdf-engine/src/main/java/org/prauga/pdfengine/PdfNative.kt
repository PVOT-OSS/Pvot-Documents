package org.prauga.pdfengine

/**
 * JNI bridge to `libpvot_pdf_engine.so` (see `pdf-engine/src/main/rust`). A handle is an opaque
 * pointer to the Rust-side parsed document; callers own its lifecycle via [PdfDocument] below
 * rather than touching these raw functions directly.
 */
internal object PdfNative {
    init {
        System.loadLibrary("pvot_pdf_engine")
    }

    external fun nativeOpen(bytes: ByteArray): Long
    external fun nativeClose(handle: Long)
    external fun nativePageCount(handle: Long): Int

    /** `[width, height, rotateDegrees]` in PDF user-space units (1/72in). */
    external fun nativePageSize(handle: Long, pageIndex: Int): FloatArray

    /** The page's drawing program — see `pdf-engine/src/main/rust/src/wire.rs` for the format. */
    external fun nativeRenderPage(handle: Long, pageIndex: Int): ByteArray

    external fun nativeImageCount(handle: Long, pageIndex: Int): Int

    /** `[width, height]` in pixels for the given image, decoded by the last `nativeRenderPage`
     * or `nativeSearchPage` call for that page. */
    external fun nativeImageInfo(handle: Long, pageIndex: Int, imageIndex: Int): FloatArray
    external fun nativeImagePixels(handle: Long, pageIndex: Int, imageIndex: Int): ByteArray

    /** Flat `[x, y, w, h, x, y, w, h, ...]` hit rects in page user-space. */
    external fun nativeSearchPage(handle: Long, pageIndex: Int, query: String): FloatArray
}
