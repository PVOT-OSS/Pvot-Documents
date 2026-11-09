package org.prauga.documents.util

/** A format [org.prauga.documents.viewer.DocumentViewerActivity] can actually open, each with a
 * real renderer rather than a guess at one. */
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
    Pdf,

    /** A Word document, parsed into paragraphs, lists, tables and pictures, shown as flowing text. */
    Docx,

    /** An OpenDocument text file, shown the same way as [Docx]. */
    Odt,

    /** An Excel (`.xlsx`) or OpenDocument (`.ods`) workbook, shown sheet by sheet as a grid. */
    Spreadsheet,

    /** A PowerPoint (`.pptx`) or OpenDocument (`.odp`) presentation, each slide drawn as laid out. */
    Slides
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
        extension == "docx" -> ViewerFormat.Docx
        extension == "odt" -> ViewerFormat.Odt
        extension == "pptx" || extension == "odp" -> ViewerFormat.Slides
        extension == "xlsx" || extension == "xlsm" || extension == "ods" -> ViewerFormat.Spreadsheet
        extension in CodeExtensions -> ViewerFormat.Code
        mimeType == "application/json" -> ViewerFormat.Json
        mimeType == "text/csv" || mimeType == "application/csv" -> ViewerFormat.Csv
        mimeType == "text/markdown" -> ViewerFormat.Markdown
        mimeType == "text/html" -> ViewerFormat.Html
        mimeType == "application/pdf" -> ViewerFormat.Pdf
        mimeType.contains("wordprocessingml") -> ViewerFormat.Docx
        mimeType == "application/vnd.oasis.opendocument.text" -> ViewerFormat.Odt
        mimeType.contains("presentationml.presentation") ||
            mimeType == "application/vnd.oasis.opendocument.presentation" -> ViewerFormat.Slides
        mimeType.contains("spreadsheetml") || mimeType.equals("application/vnd.ms-excel.sheet.macroEnabled.12", ignoreCase = true) ||
            mimeType == "application/vnd.oasis.opendocument.spreadsheet" -> ViewerFormat.Spreadsheet
        mimeType.startsWith("text/") -> ViewerFormat.PlainText
        else -> null
    }
}
