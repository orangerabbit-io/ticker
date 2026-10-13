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
