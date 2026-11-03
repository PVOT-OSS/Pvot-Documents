package org.prauga.documents.util

/**
 * A format [org.prauga.documents.viewer.DocumentViewerActivity] can actually open. Markdown, HTML,
 * PDF and DOCX come next, each as its own slice once it has a real renderer rather than a guess at
 * one.
 */
enum class ViewerFormat {
    /** Shown as-is, in the body font. */
    PlainText,

    /** Shown in monospace, no syntax highlighting yet. */
    Code,

    /** Pretty-printed to 2-space indentation if it parses, shown in monospace. */
    Json,

    /** Parsed into rows and shown as a scrollable table. */
    Csv,

    /** Parsed into blocks and shown as rendered headings, lists, code fences and the rest. */
    Markdown,

    /** Rendered through the platform's own HTML parser, shown as styled, scrollable text. */
    Html,

    /** Rasterized page by page through the platform's own renderer. */
    Pdf
}

private val CodeExtensions = setOf(
    "kt", "kts", "java", "py", "js", "ts", "jsx", "tsx", "c", "cpp", "h", "hpp", "cs", "go", "rs",
    "rb", "php", "swift", "sh", "bash", "yml", "yaml", "toml", "xml", "gradle", "properties", "ini",
    "css", "scss", "sql"
)

/** [ViewerFormat] for a document, read from its extension first and its MIME type as a fallback —
 * the same ordering [documentKind] uses, for the same reason. */
fun viewerFormat(name: String, mimeType: String): ViewerFormat? {
    val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return when {
        extension == "txt" -> ViewerFormat.PlainText
        extension == "json" -> ViewerFormat.Json
        extension == "csv" -> ViewerFormat.Csv
        extension == "md" || extension == "markdown" -> ViewerFormat.Markdown
        extension == "html" || extension == "htm" -> ViewerFormat.Html
        extension == "pdf" -> ViewerFormat.Pdf
        extension in CodeExtensions -> ViewerFormat.Code
        mimeType == "application/json" -> ViewerFormat.Json
        mimeType == "text/csv" || mimeType == "application/csv" -> ViewerFormat.Csv
        mimeType == "text/markdown" -> ViewerFormat.Markdown
        mimeType == "text/html" -> ViewerFormat.Html
        mimeType == "application/pdf" -> ViewerFormat.Pdf
        mimeType.startsWith("text/") -> ViewerFormat.PlainText
        else -> null
    }
}
