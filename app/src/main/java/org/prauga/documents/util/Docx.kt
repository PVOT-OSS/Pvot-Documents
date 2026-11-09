package org.prauga.documents.util

import android.content.Context
import android.net.Uri
import android.util.Xml
import java.io.InputStream
import java.util.zip.ZipInputStream
import org.xmlpull.v1.XmlPullParser

/** One run of text inside a [DocxParagraph], with the character formatting Word actually applies
 * run by run rather than paragraph by paragraph. */
data class DocxRun(val text: String, val bold: Boolean, val italic: Boolean, val underline: Boolean)

/** [runs] in reading order; [headingLevel] comes from the paragraph style name (`Heading1`..
 * `Heading9`), null for body text. */
data class DocxParagraph(val runs: List<DocxRun>, val headingLevel: Int?)

/** [uri]'s `word/document.xml` entry — a `.docx` is a zip of XML parts — walked paragraph by
 * paragraph, run by run. Tables, images, lists, headers and footers aren't v1's scope: this reads
 * body text and its bold/italic/underline/heading formatting only. */
fun parseDocx(context: Context, uri: Uri): List<DocxParagraph> {
    val input = context.contentResolver.openInputStream(uri) ?: error("Could not open $uri for reading")
    input.use { stream ->
        ZipInputStream(stream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") return parseDocumentXml(zip)
                entry = zip.nextEntry
            }
        }
    }
    error("word/document.xml not found in $uri — is this a valid .docx?")
}

private fun headingLevelFromStyle(style: String?): Int? {
    if (style == null || !style.startsWith("Heading", ignoreCase = true)) return null
    return style.filter { it.isDigit() }.toIntOrNull()?.coerceIn(1, 6)
}

private fun parseDocumentXml(input: InputStream): List<DocxParagraph> {
    val parser = Xml.newPullParser()
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
    parser.setInput(input, "UTF-8")

    val paragraphs = mutableListOf<DocxParagraph>()
    var runs = mutableListOf<DocxRun>()
    var headingLevel: Int? = null
    var bold = false
    var italic = false
    var underline = false
    var insideText = false

    fun isOn(value: String?) = value != "false" && value != "0"

    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        when (event) {
            XmlPullParser.START_TAG -> when (parser.name) {
                "w:p" -> {
                    runs = mutableListOf()
                    headingLevel = null
                }
                "w:r" -> {
                    bold = false
                    italic = false
                    underline = false
                }
                "w:b" -> bold = isOn(parser.getAttributeValue(null, "w:val"))
                "w:i" -> italic = isOn(parser.getAttributeValue(null, "w:val"))
                "w:u" -> underline = parser.getAttributeValue(null, "w:val") != "none"
                "w:pStyle" -> headingLevel = headingLevelFromStyle(parser.getAttributeValue(null, "w:val"))
                "w:t" -> insideText = true
                "w:tab" -> runs.add(DocxRun("\t", bold, italic, underline))
                "w:br" -> runs.add(DocxRun("\n", bold, italic, underline))
            }

            XmlPullParser.TEXT -> if (insideText && parser.text.isNotEmpty()) {
                runs.add(DocxRun(parser.text, bold, italic, underline))
            }

            XmlPullParser.END_TAG -> when (parser.name) {
                "w:t" -> insideText = false
                "w:p" -> paragraphs.add(DocxParagraph(runs, headingLevel))
            }
        }
        event = parser.next()
    }
    return paragraphs
}
