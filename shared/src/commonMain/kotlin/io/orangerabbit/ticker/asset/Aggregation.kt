package io.orangerabbit.ticker.asset

import io.orangerabbit.ticker.config.ConfigGroup
import io.orangerabbit.ticker.config.Lot
import io.orangerabbit.ticker.config.TickerConfig

private data class AggregatedLot(
    val symbol: String,
    val cost: Double,
    val quantity: Double,
)

// conversion rate for each use case, ported from the CLI's asset/currency.go
private data class CurrencyRateByUse(
    val toCurrencyCode: String,
    val quotePrice: Double,
    val positionCost: Double,
    val summaryValue: Double,
    val summaryCost: Double,
)

fun getAssets(
    config: TickerConfig,
    group: ConfigGroup,
    quotes: List<AssetQuote>,
    rates: CurrencyRates,
): Portfolio {
    val lots = group.lots
    val lotsBySymbol = getLots(lots)
    val orderIndex = mutableMapOf<String, Int>()

    for ((i, lot) in lots.withIndex()) {
        orderIndex.putIfAbsent(lot.symbol.lowercase(), i)
    }
    for ((i, symbol) in group.watchlist.withIndex()) {
        orderIndex.putIfAbsent(symbol.lowercase(), i + lots.size)
    }

    var summary = PositionSummary()
    val assets = mutableListOf<Asset>()
    val summaryValues = mutableListOf<Double>()

    for (quote in quotes) {
        val rateEntry = rates.rates[quote.currency.from]
        val rate = rateEntry?.rate ?: quote.currency.rate
        val toCurrency = rateEntry?.to ?: quote.currency.to
        val currencyRateByUse = getCurrencyRateByUse(config, quote.assetClass, quote.currency.from, toCurrency, rate)

        val position = getPositionFromAssetQuote(quote, lotsBySymbol, currencyRateByUse)
        summary = addPositionToPositionSummary(summary, position, currencyRateByUse)
        summaryValues.add(position.value * currencyRateByUse.summaryValue)

        assets.add(
            Asset(
                name = quote.name,
                symbol = quote.symbol,
                assetClass = quote.assetClass,
                currency = Currency(from = quote.currency.from, to = currencyRateByUse.toCurrencyCode),
                position = position,
                quotePrice = convertAssetQuotePriceCurrency(currencyRateByUse, quote.quotePrice),
                quoteExtended = convertAssetQuoteExtendedCurrency(currencyRateByUse, quote.quoteExtended),
                quoteSource = quote.quoteSource,
                exchange = quote.exchange,
                meta = Meta(
                    isVariablePrecision = quote.meta.isVariablePrecision,
                    orderIndex = orderIndex[quote.symbol.lowercase()] ?: 0,
                ),
            ),
        )
    }

    return Portfolio(
        assets = updatePositionWeights(assets, summaryValues, summary),
        summary = summary,
    )
}

private fun convertAssetQuotePriceCurrency(currencyRateByUse: CurrencyRateByUse, quotePrice: QuotePrice): QuotePrice =
    QuotePrice(
        price = quotePrice.price * currencyRateByUse.quotePrice,
        prevClose = quotePrice.prevClose * currencyRateByUse.quotePrice,
        open = quotePrice.open * currencyRateByUse.quotePrice,
        dayHigh = quotePrice.dayHigh * currencyRateByUse.quotePrice,
        dayLow = quotePrice.dayLow * currencyRateByUse.quotePrice,
        change = quotePrice.change * currencyRateByUse.quotePrice,
        changePercent = quotePrice.changePercent,
    )

private fun convertAssetQuoteExtendedCurrency(currencyRateByUse: CurrencyRateByUse, quoteExtended: QuoteExtended): QuoteExtended =
    QuoteExtended(
        fiftyTwoWeekHigh = quoteExtended.fiftyTwoWeekHigh * currencyRateByUse.quotePrice,
        fiftyTwoWeekLow = quoteExtended.fiftyTwoWeekLow * currencyRateByUse.quotePrice,
        marketCap = quoteExtended.marketCap * currencyRateByUse.quotePrice,
        volume = quoteExtended.volume,
    )

private fun getCurrencyRateByUse(
    config: TickerConfig,
    assetClass: AssetClass,
    fromCurrency: String,
    toCurrency: String,
    rate: Double,
): CurrencyRateByUse {
    // skip conversion for currency pairs (e.g., KRW=X) and when no rate is available
    if (assetClass == AssetClass.CURRENCY || rate == 0.0) {
        return CurrencyRateByUse(
            toCurrencyCode = fromCurrency,
            quotePrice = 1.0,
            positionCost = 1.0,
            summaryValue = 1.0,
            summaryCost = 1.0,
        )
    }

    val currencyRateCost = if (config.currencyDisableUnitCostConversion) 1.0 else rate
    val hasTargetCurrency = !config.currency.isNullOrEmpty()

    return when {
        // convert only the summary currency to the configured currency
        hasTargetCurrency && config.currencySummaryOnly ->
            CurrencyRateByUse(
                toCurrencyCode = fromCurrency,
                quotePrice = 1.0,
                positionCost = 1.0,
                summaryValue = rate,
                summaryCost = currencyRateCost,
            )

        // convert all quotes and positions to the target currency; the summary is implicitly converted
        hasTargetCurrency ->
            CurrencyRateByUse(
                toCurrencyCode = toCurrency,
                quotePrice = rate,
                positionCost = currencyRateCost,
                summaryValue = 1.0,
                summaryCost = 1.0,
            )

        // convert only the summary currency when currency conversion is not enabled
        else ->
            CurrencyRateByUse(
                toCurrencyCode = toCurrency,
                quotePrice = 1.0,
                positionCost = 1.0,
                summaryValue = rate,
                summaryCost = currencyRateCost,
            )
    }
}

private fun getPositionFromAssetQuote(
    assetQuote: AssetQuote,
    lotsBySymbol: Map<String, AggregatedLot>,
    currencyRateByUse: CurrencyRateByUse,
): Position {
    val aggregatedLot = lotsBySymbol[assetQuote.symbol] ?: return Position()

    val value = aggregatedLot.quantity * assetQuote.quotePrice.price * currencyRateByUse.quotePrice
    val cost = aggregatedLot.cost * currencyRateByUse.positionCost
    val totalChangeAmount = value - cost

    var unitValue = 0.0
    var unitCost = 0.0
    if (aggregatedLot.quantity != 0.0) {
        unitValue = value / aggregatedLot.quantity
        unitCost = cost / aggregatedLot.quantity
    }

    return Position(
        value = value,
        cost = cost,
        quantity = aggregatedLot.quantity,
        unitValue = unitValue,
        unitCost = unitCost,
        dayChange = PositionChange(
            amount = assetQuote.quotePrice.change * aggregatedLot.quantity * currencyRateByUse.quotePrice,
            percent = assetQuote.quotePrice.changePercent,
        ),
        totalChange = PositionChange(
            amount = totalChangeAmount,
            percent = calculateChangePercent(totalChangeAmount, cost),
        ),
        weight = 0.0,
    )
}

private fun getLots(lots: List<Lot>): Map<String, AggregatedLot> {
    val aggregatedLots = mutableMapOf<String, AggregatedLot>()

    for (lot in lots) {
        val existing = aggregatedLots[lot.symbol]
        if (existing == null) {
            aggregatedLots[lot.symbol] = AggregatedLot(
                symbol = lot.symbol,
                cost = (lot.unitCost * lot.quantity) + lot.fixedCost,
                quantity = lot.quantity,
            )
        } else {
            aggregatedLots[lot.symbol] = existing.copy(
                quantity = existing.quantity + lot.quantity,
                cost = existing.cost + lot.quantity * lot.unitCost,
            )
        }
    }

    return aggregatedLots
}

private fun calculateChangePercent(changeAmount: Double, base: Double): Double {
    if (base == 0.0) {
        return 0.0
    }

    return (changeAmount / base) * 100
}

private fun addPositionToPositionSummary(
    positionSummary: PositionSummary,
    position: Position,
    currencyRateByUse: CurrencyRateByUse,
): PositionSummary {
    if (position.value == 0.0) {
        return positionSummary
    }

    val value = positionSummary.value + (position.value * currencyRateByUse.summaryValue)
    val cost = positionSummary.cost + (position.cost * currencyRateByUse.summaryCost)
    val dayChange = positionSummary.dayChange.amount + (position.dayChange.amount * currencyRateByUse.summaryValue)
    val totalChange = value - cost

    return PositionSummary(
        value = value,
        cost = cost,
        totalChange = PositionChange(
            amount = totalChange,
            percent = calculateChangePercent(totalChange, cost),
        ),
        dayChange = PositionChange(
            amount = dayChange,
            percent = (dayChange / value) * 100,
        ),
    )
}

private fun updatePositionWeights(
    assets: List<Asset>,
    summaryValues: List<Double>,
    positionSummary: PositionSummary,
): List<Asset> {
    if (positionSummary.value == 0.0) {
        return assets
    }

    // weight uses each position's value expressed in the summary/display currency so the
    // numerator and denominator share a currency (e.g. a GBp-quoted position in pence)
    return assets.mapIndexed { i, asset ->
        asset.copy(
            position = asset.position.copy(
                weight = (summaryValues[i] / positionSummary.value) * 100,
            ),
        )
    }
}
