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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.orangerabbit.ticker.asset.Asset

// PositionsSection lists the held positions below the quote rows: one block per asset
// with a non-zero quantity showing weight, average cost, quantity, day change, and
// total gain/loss (amount + %).
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
        Row {
            Text(
                text = asset.symbol,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
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
            Text(
                text = "Weight " + formatPercent(position.weight),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row {
            Text(
                text = "Avg. Cost " + formatPrice(position.unitCost, variablePrecision),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = "Quantity " + formatAmount(position.quantity, variablePrecision),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row {
            Text(
                text = "Day " + changeText(
                    position.dayChange.amount,
                    position.dayChange.percent,
                    variablePrecision,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = changeColor(position.dayChange.percent),
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = "Total " + changeText(
                    position.totalChange.amount,
                    position.totalChange.percent,
                    variablePrecision,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = changeColor(position.totalChange.percent),
            )
        }
    }
}
