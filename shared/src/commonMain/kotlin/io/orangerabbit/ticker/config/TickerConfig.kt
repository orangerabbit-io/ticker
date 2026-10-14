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

// defaultGroupName is the name withDefaultGroup gives the promoted top-level group; a
// stored group of that name is where the promoted entries materialize when a group edit
// normalizes the config.
const val defaultGroupName = "default"

// foldDefaultGroup merges a stored "default" group's entries into the promoted
// top-level entries — promoted entries keep their order first, watchlist duplicates
// are dropped case-insensitively, lots are appended — so promotion never produces two
// groups named "default". This mirrors withDefaultGroup's materialization: the default
// group is always shown first and keeps every entry.
private fun foldDefaultGroup(config: TickerConfig): TickerConfig {
    if (config.watchlist.isEmpty() && config.lots.isEmpty()) return config
    val defaults = config.groups.filter { it.name == defaultGroupName }
    if (defaults.isEmpty()) return config

    val watchlist = config.watchlist.toMutableList()
    val seen = config.watchlist.mapTo(HashSet()) { it.lowercase() }
    for (group in defaults) {
        for (symbol in group.watchlist) {
            if (seen.add(symbol.lowercase())) {
                watchlist.add(symbol)
            }
        }
    }
    return config.copy(
        watchlist = watchlist,
        lots = config.lots + defaults.flatMap { it.lots },
        groups = config.groups.filter { it.name != defaultGroupName },
    )
}

fun TickerConfig.withDefaultGroup(): TickerConfig {
    val config = foldDefaultGroup(this)
    return if (config.watchlist.isEmpty() && config.lots.isEmpty()) {
        config
    } else {
        config.copy(
            watchlist = emptyList(),
            lots = emptyList(),
            groups = listOf(ConfigGroup(name = defaultGroupName, watchlist = config.watchlist, lots = config.lots)) + config.groups,
        )
    }
}

// validateTickerConfig rejects semantically invalid configurations after decoding:
// negative intervals, duplicate group names (names identify groups across edits), and
// malformed lots (blank symbol, non-finite values). Negative quantities are valid
// (shorts/netting) and missing fields keep their documented defaults. A stored
// "default" group merges into the promoted top-level entries and the merged config is
// returned, so success is a validated, unambiguous configuration.
fun validateTickerConfig(config: TickerConfig): Result<TickerConfig> {
    if (config.interval < 0) {
        return Result.failure(IllegalArgumentException("interval must not be negative"))
    }
    val names = HashSet<String>()
    for (group in config.groups) {
        if (!names.add(group.name)) {
            return Result.failure(IllegalArgumentException("group names must be unique: \"${group.name}\" is duplicated"))
        }
    }
    val merged = foldDefaultGroup(config)
    validateLots("lot", merged.lots)?.let { return Result.failure(it) }
    merged.groups.forEachIndexed { index, group ->
        validateLots("group ${index + 1} lot", group.lots)?.let { return Result.failure(it) }
    }
    return Result.success(merged)
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
