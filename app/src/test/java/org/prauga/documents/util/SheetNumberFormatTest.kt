package org.prauga.documents.util

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

/** What Excel shows for each value under each format, checked against Excel's own output. */
class SheetNumberFormatTest {

    @Before
    fun usLocale() {
        Locale.setDefault(Locale.US)
    }

    @Test
    fun general() {
        assertEquals("42", generalNumber(42.0))
        assertEquals("0.1", generalNumber(0.1))
        assertEquals("3.1415926536", generalNumber(Math.PI))
        assertEquals("-7.5", generalNumber(-7.5))
        assertEquals("1.23457E+11", generalNumber(123456789012.0))
    }

    @Test
    fun fixedDecimalsAndThousands() {
        assertEquals("1235", formatSheetNumber(1234.5, "0"))
        assertEquals("1234.50", formatSheetNumber(1234.5, "0.00"))
        assertEquals("1,234,568", formatSheetNumber(1234567.8, "#,##0"))
        assertEquals("1,234,567.80", formatSheetNumber(1234567.8, "#,##0.00"))
        assertEquals("-1,234.50", formatSheetNumber(-1234.5, "#,##0.00"))
    }

    @Test
    fun negativeSectionsAndParentheses() {
        assertEquals("(1,235)", formatSheetNumber(-1234.5, "#,##0 ;(#,##0)"))
        assertEquals("1,235 ", formatSheetNumber(1234.5, "#,##0 ;(#,##0)"))
        assertEquals("(12.30)", formatSheetNumber(-12.3, "#,##0.00;[Red](#,##0.00)"))
        assertEquals("-", formatSheetNumber(0.0, "#,##0;(#,##0);\"-\""))
    }

    @Test
    fun percentAndScientific() {
        assertEquals("12%", formatSheetNumber(0.123, "0%"))
        assertEquals("12.35%", formatSheetNumber(0.12345, "0.00%"))
        assertEquals("1.23E+04", formatSheetNumber(12345.0, "0.00E+00"))
    }

    @Test
    fun currencyAndLiterals() {
        assertEquals("₹1,250.00", formatSheetNumber(1250.0, "[\$₹-4009]#,##0.00"))
        assertEquals("$1,250.00", formatSheetNumber(1250.0, "\"$\"#,##0.00"))
        assertEquals("5 units", formatSheetNumber(5.0, "0 \"units\""))
        assertEquals("1,250.00 EUR", formatSheetNumber(1250.0, "#,##0.00 \"EUR\""))
    }

    @Test
    fun thousandsScaling() {
        assertEquals("12K", formatSheetNumber(12345.0, "#,##0,\"K\""))
    }

    @Test
    fun datesAndTimes() {
        // 45000 is 2023-03-15 in the 1900 system.
        assertEquals("15-03-2023", formatSheetNumber(45000.0, "dd-mm-yyyy"))
        assertEquals("15-Mar-23", formatSheetNumber(45000.0, "d-mmm-yy"))
        assertEquals("2023-03-15", formatSheetNumber(45000.0, "yyyy-mm-dd"))
        assertEquals("March 15, 2023", formatSheetNumber(45000.0, "mmmm d, yyyy"))
        assertEquals("Wed", formatSheetNumber(45000.0, "ddd"))
        // A quarter past six in the evening.
        assertEquals("18:15", formatSheetNumber(45000.0 + 18.25 / 24, "h:mm"))
        assertEquals("6:15 PM", formatSheetNumber(45000.0 + 18.25 / 24, "h:mm AM/PM"))
        assertEquals("18:15:00", formatSheetNumber(45000.0 + 18.25 / 24, "hh:mm:ss"))
        assertEquals("05:30", formatSheetNumber(5.5 / 1440, "mm:ss"))
        assertEquals("27:00:00", formatSheetNumber(27.0 / 24, "[h]:mm:ss"))
    }

    @Test
    fun date1904() {
        // In the 1904 system, day 0 is 1904-01-01.
        assertEquals("1904-01-02", formatSheetNumber(1.0, "yyyy-mm-dd", date1904 = true))
    }

    @Test
    fun builtInsAndText() {
        assertEquals("1,234.50", formatSheetNumber(1234.5, BuiltInNumberFormats.getValue(4)))
        assertEquals("42", formatSheetNumber(42.0, "@"))
        assertEquals("42", formatSheetNumber(42.0, "General"))
    }

    @Test
    fun columnNames() {
        assertEquals("A", columnName(0))
        assertEquals("Z", columnName(25))
        assertEquals("AA", columnName(26))
        assertEquals("XFD", columnName(16383))
        assertEquals(27, columnOf("AB12"))
        assertEquals(11, rowOf("AB12"))
        assertEquals(CellRange(0, 0, 2, 3), parseRange("A1:D3"))
    }
}
