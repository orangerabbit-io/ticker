package io.orangerabbit.ticker.asset

enum class AssetClass {
    CASH,
    STOCK,
    CRYPTOCURRENCY,
    CURRENCY,
    UNKNOWN,
}

enum class QuoteSource {
    YAHOO,
    COINBASE,
    UNKNOWN,
}

enum class ExchangeState {
    OPEN,
    PREMARKET,
    POSTMARKET,
    CLOSED,
}

data class Currency(
    val from: String,
    val to: String,
    val rate: Double = 0.0,
)

data class QuotePrice(
    val price: Double = 0.0,
    val prevClose: Double = 0.0,
    val open: Double = 0.0,
    val dayHigh: Double = 0.0,
    val dayLow: Double = 0.0,
    val change: Double = 0.0,
    val changePercent: Double = 0.0,
)

data class QuoteExtended(
    val fiftyTwoWeekHigh: Double = 0.0,
    val fiftyTwoWeekLow: Double = 0.0,
    val marketCap: Double = 0.0,
    val volume: Double = 0.0,
)

data class ExchangeInfo(
    val name: String = "",
    val delay: Double = 0.0,
    val state: ExchangeState = ExchangeState.OPEN,
    val isActive: Boolean = true,
    val isRegularTradingSession: Boolean = true,
)

data class Meta(
    val isVariablePrecision: Boolean = false,
    val orderIndex: Int = 0,
    val symbolInSourceApi: String = "",
)

data class AssetQuote(
    val name: String,
    val symbol: String,
    val assetClass: AssetClass,
    val currency: Currency,
    val quotePrice: QuotePrice,
    val quoteExtended: QuoteExtended,
    val quoteSource: QuoteSource,
    val exchange: ExchangeInfo,
    val meta: Meta,
)

data class PositionChange(
    val amount: Double = 0.0,
    val percent: Double = 0.0,
)

data class Position(
    val value: Double = 0.0,
    val cost: Double = 0.0,
    val quantity: Double = 0.0,
    val unitValue: Double = 0.0,
    val unitCost: Double = 0.0,
    val dayChange: PositionChange = PositionChange(),
    val totalChange: PositionChange = PositionChange(),
    val weight: Double = 0.0,
)

data class PositionSummary(
    val value: Double = 0.0,
    val cost: Double = 0.0,
    val totalChange: PositionChange = PositionChange(),
    val dayChange: PositionChange = PositionChange(),
)

data class Asset(
    val name: String,
    val symbol: String,
    val assetClass: AssetClass,
    val currency: Currency,
    val position: Position,
    val quotePrice: QuotePrice,
    val quoteExtended: QuoteExtended,
    val quoteSource: QuoteSource,
    val exchange: ExchangeInfo,
    val meta: Meta,
)

data class CurrencyRate(
    val from: String,
    val to: String,
    val rate: Double,
)

data class CurrencyRates(
    val rates: Map<String, CurrencyRate>,
)

data class Portfolio(
    val assets: List<Asset>,
    val summary: PositionSummary,
)
