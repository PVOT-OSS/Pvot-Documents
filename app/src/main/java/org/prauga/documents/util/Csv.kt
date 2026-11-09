package org.prauga.documents.util

/**
 * [text] as rows of fields, RFC 4180 style: a quoted field may contain commas, newlines, and `""`
 * for a literal quote. Parses the whole text as one pass rather than splitting by line first, so a
 * quoted field spanning several lines doesn't get cut apart.
 */
fun parseCsv(text: String): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val field = StringBuilder()
    var inQuotes = false
    var index = 0

    fun endField() {
        row.add(field.toString())
        field.clear()
    }

    fun endRow() {
        endField()
        rows.add(row)
        row = mutableListOf()
    }

    while (index < text.length) {
        val char = text[index]
        if (inQuotes) {
            when {
                char == '"' && index + 1 < text.length && text[index + 1] == '"' -> {
                    field.append('"')
                    index++
                }
                char == '"' -> inQuotes = false
                else -> field.append(char)
            }
        } else {
            when (char) {
                '"' -> inQuotes = true
                ',' -> endField()
                '\r' -> Unit
                '\n' -> endRow()
                else -> field.append(char)
            }
        }
        index++
    }
    if (field.isNotEmpty() || row.isNotEmpty()) endRow()
    return rows
}
