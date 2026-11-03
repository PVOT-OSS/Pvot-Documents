package org.prauga.documents.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prauga.pvot.designsystem.theme.PvotSpacing

/**
 * [rows]' first row as a header, the rest below it, scrollable both ways since a real CSV is
 * rarely narrow enough to fit a phone either axis. Every column gets the same width: this is a
 * reader, not a spreadsheet, and there's no column data to size them by without laying out the
 * whole table first. The whole table loads into one scrollable `Column` rather than a lazy one, the
 * same tradeoff the plain-text viewer makes — no virtualizing for a very large file, in exchange
 * for a layout simple enough to reason about.
 */
@Composable
fun CsvTable(rows: List<List<String>>, topPadding: Dp, modifier: Modifier = Modifier) {
    if (rows.isEmpty()) return
    Column(
        modifier = modifier
            .padding(top = topPadding)
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState())
    ) {
        rows.forEachIndexed { index, cells ->
            CsvRow(cells = cells, header = index == 0)
        }
    }
}

@Composable
private fun CsvRow(cells: List<String>, header: Boolean) {
    Row(
        modifier = if (header) {
            Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
        } else {
            Modifier
        }
    ) {
        cells.forEach { cell ->
            Text(
                text = cell,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .width(CellWidth)
                    .padding(horizontal = PvotSpacing.S, vertical = PvotSpacing.S)
            )
        }
    }
}

private val CellWidth = 120.dp
