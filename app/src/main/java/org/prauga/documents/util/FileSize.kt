package org.prauga.documents.util

/** A byte count as "12 KB", "3.4 MB" and so on, to the nearest common unit. */
fun formatFileSize(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return if (unitIndex == 0) "$bytes ${units[0]}" else "%.1f %s".format(value, units[unitIndex])
}
