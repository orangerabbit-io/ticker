package io.orangerabbit.ticker.config

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
private data class RawLot(
    @SerialName("symbol") val symbol: String? = null,
    @SerialName("unit_cost") val unitCost: Double? = null,
    @SerialName("quantity") val quantity: Double? = null,
    @SerialName("fixed_cost") val fixedCost: Double? = null,
)

@Serializable
private data class RawGroup(
    @SerialName("name") val name: String? = null,
    @SerialName("watchlist") val watchlist: List<String>? = null,
    @SerialName("lots") val lots: List<RawLot>? = null,
    @SerialName("holdings") val holdings: List<RawLot>? = null,
)

@Serializable
private data class RawConfig(
    @SerialName("interval") val interval: Int? = null,
    @SerialName("watchlist") val watchlist: List<String>? = null,
    @SerialName("lots") val lots: List<RawLot>? = null,
    @SerialName("holdings") val holdings: List<RawLot>? = null,
    @SerialName("groups") val groups: List<RawGroup>? = null,
    @SerialName("show-tags") val showTags: Boolean? = null,
    @SerialName("show-fundamentals") val showFundamentals: Boolean? = null,
    @SerialName("show-separator") val showSeparator: Boolean? = null,
    @SerialName("show-summary") val showSummary: Boolean? = null,
    @SerialName("show-positions") val showPositions: Boolean? = null,
    @SerialName("show-holdings") val showHoldings: Boolean? = null,
    @SerialName("sort") val sort: String? = null,
    @SerialName("currency") val currency: String? = null,
    @SerialName("currency-summary-only") val currencySummaryOnly: Boolean? = null,
    @SerialName("currency-disable-unit-cost-conversion") val currencyDisableUnitCostConversion: Boolean? = null,
)

private val yamlFormat = Yaml(configuration = YamlConfiguration(strictMode = false))

fun parseTickerConfig(yaml: String): Result<TickerConfig> =
    try {
        Result.success(yamlFormat.decodeFromString(RawConfig.serializer(), yaml).toDomain())
    } catch (e: Exception) {
        Result.failure(
            if (e.message.isNullOrBlank()) IllegalStateException("failed to parse ticker configuration", e) else e,
        )
    }

fun TickerConfig.toYaml(): String = yamlFormat.encodeToString(RawConfig.serializer(), toRaw())

private fun RawLot.toDomain(): Lot =
    Lot(
        symbol = symbol.orEmpty(),
        unitCost = unitCost ?: 0.0,
        quantity = quantity ?: 0.0,
        fixedCost = fixedCost ?: 0.0,
    )

private fun RawGroup.toDomain(): ConfigGroup =
    ConfigGroup(
        name = name.orEmpty(),
        watchlist = watchlist.orEmpty(),
        lots = (lots ?: holdings).orEmpty().map(RawLot::toDomain),
    )

private fun RawConfig.toDomain(): TickerConfig =
    TickerConfig(
        interval = interval ?: 5,
        watchlist = watchlist.orEmpty(),
        lots = (lots ?: holdings).orEmpty().map(RawLot::toDomain),
        groups = groups.orEmpty().map(RawGroup::toDomain),
        showTags = showTags ?: false,
        showFundamentals = showFundamentals ?: false,
        showSeparator = showSeparator ?: false,
        showSummary = showSummary ?: false,
        showPositions = showPositions ?: showHoldings ?: false,
        sort = sort,
        currency = currency,
        currencySummaryOnly = currencySummaryOnly ?: false,
        currencyDisableUnitCostConversion = currencyDisableUnitCostConversion ?: false,
    )

private fun Lot.toRaw(): RawLot =
    RawLot(symbol = symbol, unitCost = unitCost, quantity = quantity, fixedCost = fixedCost)

private fun ConfigGroup.toRaw(): RawGroup =
    RawGroup(name = name, watchlist = watchlist, lots = lots.map(Lot::toRaw))

private fun TickerConfig.toRaw(): RawConfig =
    RawConfig(
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
