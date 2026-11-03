package org.prauga.documents.util

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The file type a document's icon is tinted by, same idea as the colored file badges in Google
 * Drive, Office and the system share sheet: a PDF reads red, a spreadsheet green, regardless of
 * app theme or dark/light mode. [color] is a fixed brand color, not a `MaterialTheme.colorScheme`
 * role — PvotLib's "tokens, not literals" rule is for UI chrome, and has no role for a file type;
 * [PvotExtendedColors][com.prauga.pvot.designsystem.theme.PvotExtendedColors] only covers success
 * and warning. [Other] is the one exception, reading the theme's primary so an unrecognized type
 * still looks like the rest of the app rather than carrying its own made-up color.
 */
enum class DocumentKind(private val fixedColor: Color?) {
    Pdf(Color(0xFFE53935)),
    Word(Color(0xFF4285F4)),
    Spreadsheet(Color(0xFF34A853)),
    Html(Color(0xFFFB8C00)),
    PlainText(Color(0xFF78909C)),
    Other(null);

    @Composable
    fun color(): Color = fixedColor ?: MaterialTheme.colorScheme.primary
}

/** [DocumentKind] for a document, read from its file extension — more reliable than [mimeType],
 * which a provider sometimes reports as the generic `application/octet-stream`. */
fun documentKind(name: String, mimeType: String): DocumentKind =
    when (name.substringAfterLast('.', missingDelimiterValue = "").lowercase()) {
        "pdf" -> DocumentKind.Pdf
        "doc", "docx" -> DocumentKind.Word
        "xls", "xlsx", "csv" -> DocumentKind.Spreadsheet
        "html", "htm" -> DocumentKind.Html
        "txt", "md", "markdown" -> DocumentKind.PlainText
        else -> mimeTypeKind(mimeType)
    }

private fun mimeTypeKind(mimeType: String): DocumentKind = when {
    mimeType == "application/pdf" -> DocumentKind.Pdf
    mimeType.contains("wordprocessingml") || mimeType == "application/msword" -> DocumentKind.Word
    mimeType.contains("spreadsheetml") || mimeType == "text/csv" ||
        mimeType == "application/csv" -> DocumentKind.Spreadsheet
    mimeType == "text/html" -> DocumentKind.Html
    mimeType.startsWith("text/") -> DocumentKind.PlainText
    else -> DocumentKind.Other
}
