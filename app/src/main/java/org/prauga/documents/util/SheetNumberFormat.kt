package org.prauga.documents.util

import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/** Excel's built-in number formats by id, the ones a file uses without spelling them out. */
internal val BuiltInNumberFormats: Map<Int, String> = mapOf(
    0 to "General", 1 to "0", 2 to "0.00", 3 to "#,##0", 4 to "#,##0.00",
    9 to "0%", 10 to "0.00%", 11 to "0.00E+00", 12 to "# ?/?", 13 to "# ??/??",
    14 to "dd-mm-yyyy", 15 to "d-mmm-yy", 16 to "d-mmm", 17 to "mmm-yy",
    18 to "h:mm AM/PM", 19 to "h:mm:ss AM/PM", 20 to "h:mm", 21 to "h:mm:ss", 22 to "dd-mm-yyyy h:mm",
    37 to "#,##0 ;(#,##0)", 38 to "#,##0 ;(#,##0)", 39 to "#,##0.00;(#,##0.00)", 40 to "#,##0.00;(#,##0.00)",
    45 to "mm:ss", 46 to "[h]:mm:ss", 47 to "mm:ss.0", 48 to "##0.0E+0", 49 to "@",
)

/**
 * [value] as a spreadsheet would show it under number format [code]: the common cases of Excel's
 * format language — General, fixed decimals, thousands separators, percentages, scientific,
 * currency symbols and literal text, and dates and times. Fractions and conditions fall back to
 * General rather than guessing. [date1904] is the workbook's date system.
 */
fun formatSheetNumber(value: Double, code: String, date1904: Boolean = false): String {
    if (code.isBlank() || code.equals("General", ignoreCase = true)) return generalNumber(value)
    val compiled = compiledCodes.get()!!.getOrPut(code) { splitSections(code).map(::cleanSection) }
    // Up to four sections: positive; negative; zero; text. A negative value in its own section
    // shows without its sign — the section carries its own, or parentheses.
    val (cleaned, shown) = when {
        value < 0 && compiled.size >= 2 -> compiled[1] to -value
        value == 0.0 && compiled.size >= 3 -> compiled[2] to value
        else -> compiled[0] to value
    }
    if (cleaned.isConditional) return generalNumber(value)
    return runCatching {
        when {
            isDateFormat(cleaned.text) -> formatDate(shown, cleaned.text, date1904)
            cleaned.text.contains('/') && cleaned.text.contains('?') -> generalNumber(value)
            cleaned.text == "@" -> generalNumber(value)
            else -> formatPattern(shown, cleaned.text, negativeSign = value < 0 && compiled.size < 2)
        }
    }.getOrElse { generalNumber(value) }
}

/** Each format code split into sections and cleaned, kept per thread: a workbook uses a handful
 * of codes across all its cells. */
private val compiledCodes = ThreadLocal.withInitial { HashMap<String, List<CleanSection>>() }

/** General format: up to 11 significant digits, no trailing zeros, scientific when very large or
 * very small — Excel's own rule of thumb. */
fun generalNumber(value: Double): String {
    if (value.isNaN() || value.isInfinite()) return value.toString()
    if (value == floor(value) && abs(value) < 1e11) return value.toLong().toString()
    val magnitude = abs(value)
    if (magnitude >= 1e11 || magnitude < 1e-9) {
        return DecimalFormat("0.#####E00", DecimalFormatSymbols(Locale.US)).format(value)
            .replace("E", "E+").replace("E+-", "E-")
    }
    val rounded = BigDecimal(value).round(java.math.MathContext(11)).stripTrailingZeros()
    return rounded.toPlainString()
}

private fun splitSections(code: String): List<String> {
    val sections = mutableListOf<String>()
    val current = StringBuilder()
    var quoted = false
    var i = 0
    while (i < code.length) {
        val ch = code[i]
        when {
            ch == '"' -> { quoted = !quoted; current.append(ch) }
            ch == '\\' && i + 1 < code.length -> { current.append(ch).append(code[i + 1]); i++ }
            ch == ';' && !quoted -> { sections.add(current.toString()); current.clear() }
            else -> current.append(ch)
        }
        i++
    }
    sections.add(current.toString())
    return sections
}

private class CleanSection(val text: String, val isConditional: Boolean)

/** A section without what doesn't print: colors ("[Red]"), locales ("[$-409]"), padding ("_)")
 * and fill ("*-"). A currency tag ("[$₹-4009]") keeps its symbol, as quoted literal text. */
private fun cleanSection(section: String): CleanSection {
    val out = StringBuilder()
    var conditional = false
    var i = 0
    while (i < section.length) {
        val ch = section[i]
        when {
            ch == '[' -> {
                val end = section.indexOf(']', i).takeIf { it > i } ?: section.length
                val tag = section.substring(i + 1, end)
                when {
                    tag.startsWith("$") -> {
                        val symbol = tag.drop(1).substringBefore('-')
                        if (symbol.isNotEmpty()) out.append('"').append(symbol).append('"')
                    }
                    tag.startsWith("<") || tag.startsWith(">") || tag.startsWith("=") -> conditional = true
                    // Elapsed time ("[h]") is kept for the date formatter.
                    tag.all { it == 'h' || it == 'H' || it == 'm' || it == 'M' || it == 's' || it == 'S' } ->
                        out.append('[').append(tag).append(']')
                }
                i = end + 1
                continue
            }
            ch == '_' || ch == '*' -> { if (ch == '_') out.append(' '); i += 2; continue }
            else -> out.append(ch)
        }
        i++
    }
    // Not trimmed: a trailing space (or "_)" padding) lines positive numbers up with negative ones
    // in parentheses.
    return CleanSection(out.toString(), conditional)
}

private fun isDateFormat(text: String): Boolean {
    var quoted = false
    var i = 0
    while (i < text.length) {
        val ch = text[i]
        if (ch == '"') quoted = !quoted
        else if (ch == '\\') i++
        else if (!quoted && ch.lowercaseChar() in "ymdhs") return true
        i++
    }
    return false
}

/** A numeric section: the run of placeholders in the middle ("#,##0.00", "0%", "0.00E+00") is
 * handed to [DecimalFormat], which reads the same notation; literal text before and after is kept. */
private fun formatPattern(value: Double, text: String, negativeSign: Boolean): String {
    val prefix = StringBuilder()
    val suffix = StringBuilder()
    val pattern = StringBuilder()
    var inPattern = false
    var pastPattern = false
    var i = 0
    while (i < text.length) {
        val ch = text[i]
        val target = if (pastPattern) suffix else if (inPattern) pattern else prefix
        when {
            ch == '"' -> {
                val end = text.indexOf('"', i + 1).takeIf { it > i } ?: text.length
                (if (inPattern) suffix.also { pastPattern = true; inPattern = false } else target)
                    .append(text, i + 1, end)
                i = end + 1
                continue
            }
            ch == '\\' && i + 1 < text.length -> {
                (if (inPattern) suffix.also { pastPattern = true; inPattern = false } else target).append(text[i + 1])
                i += 2
                continue
            }
            !pastPattern && (ch in "0#?.," || (inPattern && (ch == 'E' || ch == 'e'))) -> {
                inPattern = true
                pattern.append(if (ch == '?') '#' else if (ch == 'e') 'E' else ch)
                if ((ch == 'E' || ch == 'e') && i + 1 < text.length && (text[i + 1] == '+' || text[i + 1] == '-')) i++
            }
            ch == '%' -> {
                if (inPattern) { pastPattern = true; inPattern = false }
                (if (pastPattern) suffix else prefix).append('%')
            }
            else -> {
                if (inPattern) { pastPattern = true; inPattern = false }
                (if (pastPattern) suffix else prefix).append(ch)
            }
        }
        i++
    }
    if (pattern.isEmpty()) return (prefix.toString() + suffix).ifEmpty { generalNumber(value) }
    val percent = prefix.contains('%') || suffix.contains('%')
    var numberPattern = pattern.toString().trimEnd(',')
    // Trailing commas scale by thousands ("#,##0," shows 12345 as 12).
    val scaleBy = pattern.length - pattern.toString().trimEnd(',').length
    if (numberPattern.startsWith('.')) numberPattern = "#$numberPattern"
    val scaled = (if (percent) value * 100 else value) / Math.pow(1000.0, scaleBy.toDouble())
    val formatted = decimalFormat(numberPattern)
        .format(abs(scaled))
        .let { if (numberPattern.contains('E')) it.replace("E", "E+").replace("E+-", "E-") else it }
    val sign = if (negativeSign && scaled != 0.0 && formatted.any { it in '1'..'9' }) "-" else ""
    return sign + prefix + formatted + suffix
}

/** [DecimalFormat]s by pattern, kept per thread: a workbook formats hundreds of thousands of
 * cells with a handful of patterns, and building a [DecimalFormat] is the slow part. */
private val decimalFormats = ThreadLocal.withInitial { HashMap<String, DecimalFormat>() }

private fun decimalFormat(pattern: String): DecimalFormat =
    decimalFormats.get()!!.getOrPut(pattern) {
        DecimalFormat(pattern, DecimalFormatSymbols.getInstance(Locale.getDefault()))
            .apply { roundingMode = java.math.RoundingMode.HALF_UP }
    }

/** Two digits, zero-padded, without the cost of [String.format]. */
private fun pad2(n: Int): String = if (n in 0..9) "0$n" else n.toString()

/** A date or time from Excel's serial number: days since 1899-12-30 (1904-01-01 in the 1904
 * system), the fraction being the time of day. */
private fun formatDate(serial: Double, text: String, date1904: Boolean): String {
    val base = if (date1904) LocalDateTime.of(1904, 1, 1, 0, 0) else LocalDateTime.of(1899, 12, 30, 0, 0)
    val totalSeconds = (serial * 86_400.0).roundToLong()
    val dateTime = base.plusSeconds(totalSeconds)
    val out = StringBuilder()
    val hasAmPm = text.contains("AM/PM", ignoreCase = true) || text.contains("A/P", ignoreCase = true)
    var i = 0
    // "m" means month unless it follows an hour or precedes seconds, when it means minutes.
    var lastWasHour = false
    while (i < text.length) {
        val ch = text[i]
        val lower = ch.lowercaseChar()
        fun run(): Int { var n = 0; while (i + n < text.length && text[i + n].lowercaseChar() == lower) n++; return n }
        when {
            ch == '"' -> {
                val end = text.indexOf('"', i + 1).takeIf { it > i } ?: text.length
                out.append(text, i + 1, end); i = end + 1; continue
            }
            ch == '\\' && i + 1 < text.length -> { out.append(text[i + 1]); i += 2; continue }
            ch == '[' -> {
                val end = text.indexOf(']', i).takeIf { it > i } ?: text.length
                val tag = text.substring(i + 1, end).lowercase()
                when (tag.firstOrNull()) {
                    'h' -> out.append(totalSeconds / 3600)
                    'm' -> out.append(totalSeconds / 60)
                    's' -> out.append(totalSeconds)
                }
                i = end + 1; continue
            }
            text.startsWith("AM/PM", i, ignoreCase = true) -> {
                out.append(if (dateTime.hour < 12) "AM" else "PM"); i += 5; continue
            }
            text.startsWith("A/P", i, ignoreCase = true) -> {
                out.append(if (dateTime.hour < 12) "A" else "P"); i += 3; continue
            }
            lower == 'y' -> {
                val n = run()
                out.append(if (n <= 2) pad2(dateTime.year % 100) else dateTime.year.toString()); i += n
                lastWasHour = false; continue
            }
            lower == 'd' -> {
                val n = run()
                out.append(
                    when (n) {
                        1 -> dateTime.dayOfMonth.toString()
                        2 -> pad2(dateTime.dayOfMonth)
                        3 -> dateTime.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                        else -> dateTime.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
                    }
                ); i += n; lastWasHour = false; continue
            }
            lower == 'h' -> {
                val n = run()
                val hour = if (hasAmPm) (dateTime.hour % 12).let { if (it == 0) 12 else it } else dateTime.hour
                out.append(if (n >= 2) pad2(hour) else hour.toString()); i += n
                lastWasHour = true; continue
            }
            lower == 'm' -> {
                val n = run()
                val nextIsSeconds = text.substring(i + n).trimStart(':', ' ', '.').firstOrNull()?.lowercaseChar() == 's'
                if ((lastWasHour || nextIsSeconds) && n <= 2) {
                    out.append(if (n == 2) pad2(dateTime.minute) else dateTime.minute.toString())
                } else {
                    out.append(
                        when (n) {
                            1 -> dateTime.monthValue.toString()
                            2 -> pad2(dateTime.monthValue)
                            3 -> dateTime.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                            5 -> dateTime.month.getDisplayName(TextStyle.NARROW, Locale.getDefault())
                            else -> dateTime.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
                        }
                    )
                }
                i += n; lastWasHour = false; continue
            }
            lower == 's' -> {
                val n = run()
                out.append(if (n >= 2) pad2(dateTime.second) else dateTime.second.toString()); i += n
                lastWasHour = false; continue
            }
            ch == '.' && i + 1 < text.length && text[i + 1] == '0' -> {
                var n = 0
                while (i + 1 + n < text.length && text[i + 1 + n] == '0') n++
                val fraction = (serial * 86_400.0) - floor(serial * 86_400.0)
                out.append('.').append("%0${n}d".format((fraction * Math.pow(10.0, n.toDouble())).toLong()))
                i += 1 + n; continue
            }
            else -> out.append(ch)
        }
        i++
    }
    return out.toString()
}
