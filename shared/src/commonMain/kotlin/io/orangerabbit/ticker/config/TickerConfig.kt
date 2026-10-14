package io.orangerabbit.ticker.config

data class Lot(
    val symbol: String,
    val unitCost: Double = 0.0,
    val quantity: Double = 0.0,
    val fixedCost: Double = 0.0,
)

data class ConfigGroup(
    val name: String,
    val watchlist: List<String> = emptyList(),
    val lots: List<Lot> = emptyList(),
)

data class TickerConfig(
    val interval: Int = 5,
    val watchlist: List<String> = emptyList(),
    val lots: List<Lot> = emptyList(),
    val groups: List<ConfigGroup> = emptyList(),
    val showTags: Boolean = false,
    val showFundamentals: Boolean = false,
    val showSeparator: Boolean = false,
    val showSummary: Boolean = false,
    val showPositions: Boolean = false,
    val sort: String? = null,
    val currency: String? = null,
    val currencySummaryOnly: Boolean = false,
    val currencyDisableUnitCostConversion: Boolean = false,
)

fun TickerConfig.withDefaultGroup(): TickerConfig =
    if (watchlist.isEmpty() && lots.isEmpty()) {
        this
    } else {
        copy(
            watchlist = emptyList(),
            lots = emptyList(),
            groups = listOf(ConfigGroup(name = "default", watchlist = watchlist, lots = lots)) + groups,
        )
    }

// validateTickerConfig rejects semantically invalid configurations after decoding:
// negative intervals and malformed lots (blank symbol, non-finite values). Negative
// quantities are valid (shorts/netting) and missing fields keep their documented
// defaults. Success is reported as the validated config so callers can chain.
fun validateTickerConfig(config: TickerConfig): Result<TickerConfig> {
    if (config.interval < 0) {
        return Result.failure(IllegalArgumentException("interval must not be negative"))
    }
    validateLots("lot", config.lots)?.let { return Result.failure(it) }
    config.groups.forEachIndexed { index, group ->
        validateLots("group ${index + 1} lot", group.lots)?.let { return Result.failure(it) }
    }
    return Result.success(config)
}

private fun validateLots(label: String, lots: List<Lot>): IllegalArgumentException? {
    lots.forEachIndexed { index, lot ->
        val at = "$label ${index + 1}"
        if (lot.symbol.isBlank()) {
            return IllegalArgumentException("$at: symbol must not be blank")
        }
        if (!lot.quantity.isFinite() || !lot.unitCost.isFinite() || !lot.fixedCost.isFinite()) {
            return IllegalArgumentException("$at: quantity, unit cost, and fixed cost must be finite numbers")
        }
    }
    return null
}
