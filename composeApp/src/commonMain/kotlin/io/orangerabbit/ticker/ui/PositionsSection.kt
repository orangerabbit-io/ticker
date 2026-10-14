package io.orangerabbit.ticker.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.orangerabbit.ticker.asset.Asset

// PositionsSection lists the held positions below the quote rows: one block per asset
// with a non-zero quantity showing weight, average cost, quantity, day change, and
// total gain/loss (amount + %). Numeric columns are mono and right-aligned.
@Composable
fun PositionsSection(
    assets: List<Asset>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Positions",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        assets.forEach { asset ->
            PositionRow(asset = asset)
        }
    }
}

@Composable
private fun PositionRow(
    asset: Asset,
    modifier: Modifier = Modifier,
) {
    val position = asset.position
    val variablePrecision = asset.meta.isVariablePrecision
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = asset.symbol.uppercase(),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = TickerMono,
                fontWeight = FontWeight.SemiBold,
                color = symbolColor(asset.symbol),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = asset.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Weight",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = formatPercent(position.weight),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = TickerMono,
            )
        }
        Row {
            Metric(
                label = "Avg. Cost",
                value = formatPrice(position.unitCost, variablePrecision),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(16.dp))
            Metric(
                label = "Quantity",
                value = formatAmount(position.quantity, variablePrecision),
                modifier = Modifier.weight(1f),
            )
        }
        Row {
            Metric(
                label = "Day",
                value = changeText(
                    position.dayChange.amount,
                    position.dayChange.percent,
                    variablePrecision,
                ),
                valueColor = changeColor(position.dayChange.percent),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(16.dp))
            Metric(
                label = "Total",
                value = changeText(
                    position.totalChange.amount,
                    position.totalChange.percent,
                    variablePrecision,
                ),
                valueColor = changeColor(position.totalChange.percent),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// Metric renders one label/value pair as a table column: the label stays in the muted
// default sans, the numeric value is mono and right-aligned within the column.
@Composable
private fun Metric(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = TickerMono,
            color = valueColor,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}
