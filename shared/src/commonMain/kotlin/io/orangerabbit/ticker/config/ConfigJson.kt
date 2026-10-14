package io.orangerabbit.ticker.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Normalized JSON storage format for the DataStore-backed config repository (Task 9):
// a DTO mirroring TickerConfig field-for-field so storage round-trips exactly. This is
// NOT the YAML representation — YAML only appears at the SAF boundaries (import parses
// it, export re-serializes with toYaml()).

@Serializable
private data class JsonLot(
    val symbol: String = "",
    val unitCost: Double = 0.0,
    val quantity: Double = 0.0,
    val fixedCost: Double = 0.0,
)

@Serializable
private data class JsonGroup(
    val name: String = "",
    val watchlist: List<String> = emptyList(),
    val lots: List<JsonLot> = emptyList(),
)

@Serializable
private data class JsonConfig(
    val interval: Int = 5,
    val watchlist: List<String> = emptyList(),
    val lots: List<JsonLot> = emptyList(),
    val groups: List<JsonGroup> = emptyList(),
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

private val jsonFormat = Json { ignoreUnknownKeys = true }

fun parseTickerConfigJson(json: String): Result<TickerConfig> =
    try {
        validateTickerConfig(jsonFormat.decodeFromString(JsonConfig.serializer(), json).toDomain())
    } catch (e: Exception) {
        Result.failure(
            if (e.message.isNullOrBlank()) IllegalStateException("failed to parse ticker configuration json", e) else e,
        )
    }

fun TickerConfig.toJson(): String = jsonFormat.encodeToString(JsonConfig.serializer(), toRaw())

private fun JsonLot.toDomain(): Lot =
    Lot(
        symbol = symbol,
        unitCost = unitCost,
        quantity = quantity,
        fixedCost = fixedCost,
    )

private fun JsonGroup.toDomain(): ConfigGroup =
    ConfigGroup(
        name = name,
        watchlist = watchlist,
        lots = lots.map(JsonLot::toDomain),
    )

private fun JsonConfig.toDomain(): TickerConfig =
    TickerConfig(
        interval = interval,
        watchlist = watchlist,
        lots = lots.map(JsonLot::toDomain),
        groups = groups.map(JsonGroup::toDomain),
        showTags = showTags,
        showFundamentals = showFundamentals,
        showSeparator = showSeparator,
        showSummary = showSummary,
        showPositions = showPositions,
        sort = sort,
        currency = currency,
        currencySummaryOnly = currencySummaryOnly,
        currencyDisableUnitCostConversion = currencyDisableUnitCostConversion,
    )

private fun Lot.toRaw(): JsonLot =
    JsonLot(symbol = symbol, unitCost = unitCost, quantity = quantity, fixedCost = fixedCost)

private fun ConfigGroup.toRaw(): JsonGroup =
    JsonGroup(name = name, watchlist = watchlist, lots = lots.map(Lot::toRaw))

private fun TickerConfig.toRaw(): JsonConfig =
    JsonConfig(
        interval = interval,
        watchlist = watchlist,
        lots = lots.map(Lot::toRaw),
        groups = groups.map(ConfigGroup::toRaw),
        showTags = showTags,
        showFundamentals = showFundamentals,
        showSeparator = showSeparator,
        showSummary = showSummary,
        showPositions = showPositions,
        sort = sort,
        currency = currency,
        currencySummaryOnly = currencySummaryOnly,
        currencyDisableUnitCostConversion = currencyDisableUnitCostConversion,
    )
