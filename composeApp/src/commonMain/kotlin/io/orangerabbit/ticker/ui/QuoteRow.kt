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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.orangerabbit.ticker.asset.Asset

// changeColor follows the green/red convention by change sign: up is the theme's
// positive color, down is its negative color, and flat stays the neutral onSurface.
// Colors come from the theme (not system-dark detection) so a forced light/dark theme
// mode never disagrees with the row colors.
@Composable
internal fun changeColor(changePercent: Double): Color {
    val tickerColors = LocalTickerColors.current
    return when {
        changePercent > 0 -> tickerColors.positive
        changePercent < 0 -> tickerColors.negative
        else -> MaterialTheme.colorScheme.onSurface
    }
}

@Composable
internal fun symbolColor(symbol: String): Color =
    if (symbol.startsWith("BTC")) BtcOrange else MaterialTheme.colorScheme.onSurface

// QuoteRow renders one quote: mono semibold symbol (BTC orange for Bitcoin symbols), muted
// truncated name, optional tag and fundamentals lines, and the right-aligned price
// with a signed day change colored by direction.
@Composable
fun QuoteRow(
    asset: Asset,
    showTags: Boolean,
    showFundamentals: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = asset.symbol.uppercase(),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = TickerMono,
                fontWeight = FontWeight.SemiBold,
                color = symbolColor(asset.symbol),
            )
            if (asset.name.isNotBlank()) {
                Text(
                    text = asset.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (showTags) {
                Text(
                    text = tagsText(asset),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showFundamentals) {
                fundamentalsText(asset)?.let { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = formatPrice(asset.quotePrice.price, asset.meta.isVariablePrecision),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = TickerMono,
            )
            Text(
                text = changeText(
                    asset.quotePrice.change,
                    asset.quotePrice.changePercent,
                    asset.meta.isVariablePrecision,
                ),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = TickerMono,
                color = changeColor(asset.quotePrice.changePercent),
            )
        }
    }
}

// tagsText renders the show-tags line as "currency · exchange · delay-text", with the
// currency shown as "FROM → TO" when the quote was converted.
internal fun tagsText(asset: Asset): String {
    val currency = if (asset.currency.to.isNotBlank() && asset.currency.to != asset.currency.from) {
        asset.currency.from + " → " + asset.currency.to
    } else {
        asset.currency.from
    }
    return listOf(currency, asset.exchange.name, exchangeDelayText(asset.exchange.delay))
        .filter { it.isNotBlank() }
        .joinToString(" · ")
}

// exchangeDelayText mirrors the desktop ticker tags: "Live" for undelayed quotes and
// "Delayed Nmin" otherwise.
internal fun exchangeDelayText(delay: Double): String =
    if (delay <= 0) "Live" else "Delayed " + formatDecimal(delay, 0) + "min"

// fundamentalsText renders the show-fundamentals line "open / prev close / day range";
// fields without data (zero, e.g. Coinbase quotes) are omitted and the line is dropped
// entirely when nothing is available.
internal fun fundamentalsText(asset: Asset): String? {
    val variablePrecision = asset.meta.isVariablePrecision
    val parts = mutableListOf<String>()
    if (asset.quotePrice.open != 0.0) {
        parts.add("Open " + formatPrice(asset.quotePrice.open, variablePrecision))
    }
    if (asset.quotePrice.prevClose != 0.0) {
        parts.add("Prev. Close " + formatPrice(asset.quotePrice.prevClose, variablePrecision))
    }
    if (asset.quotePrice.dayLow != 0.0 && asset.quotePrice.dayHigh != 0.0) {
        parts.add(
            "Day Range " + formatPrice(asset.quotePrice.dayLow, variablePrecision) +
                " - " + formatPrice(asset.quotePrice.dayHigh, variablePrecision),
        )
    }
    return if (parts.isEmpty()) null else parts.joinToString(" · ")
}
