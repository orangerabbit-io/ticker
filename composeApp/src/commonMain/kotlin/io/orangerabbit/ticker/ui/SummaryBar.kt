package io.orangerabbit.ticker.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.orangerabbit.ticker.asset.PositionSummary

// SummaryBar is pinned under the group tabs when show-summary is set: total value plus
// day change and total change (amount + %) colored by direction.
@Composable
fun SummaryBar(
    summary: PositionSummary,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        SummaryCell(
            label = "Total Value",
            value = formatMoney(summary.value),
            valueColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        SummaryCell(
            label = "Day Change",
            value = changeText(summary.dayChange.amount, summary.dayChange.percent, false),
            valueColor = changeColor(summary.dayChange.percent),
            modifier = Modifier.weight(1f),
        )
        SummaryCell(
            label = "Total Change",
            value = changeText(summary.totalChange.amount, summary.totalChange.percent, false),
            valueColor = changeColor(summary.totalChange.percent),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SummaryCell(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
) {
    // table-like right-aligned column: muted sans label over the mono value
    Column(modifier = modifier, horizontalAlignment = Alignment.End) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = TickerMono,
            color = valueColor,
        )
    }
}
