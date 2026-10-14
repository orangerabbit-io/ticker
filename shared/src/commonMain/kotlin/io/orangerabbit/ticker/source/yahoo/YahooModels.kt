package io.orangerabbit.ticker.source.yahoo

import io.orangerabbit.ticker.asset.AssetClass
import io.orangerabbit.ticker.asset.AssetQuote
import io.orangerabbit.ticker.asset.Currency
import io.orangerabbit.ticker.asset.CurrencyRate
import io.orangerabbit.ticker.asset.CurrencyRates
import io.orangerabbit.ticker.asset.ExchangeInfo
import io.orangerabbit.ticker.asset.ExchangeState
import io.orangerabbit.ticker.asset.Meta
import io.orangerabbit.ticker.asset.QuoteExtended
import io.orangerabbit.ticker.asset.QuotePrice
import io.orangerabbit.ticker.asset.QuoteSource
import io.orangerabbit.ticker.asset.minorUnitForCurrencyCode
import io.orangerabbit.ticker.source.SearchResultSource
import io.orangerabbit.ticker.source.SymbolSearchResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.pow

// Response models ported from the CLI's models.go.

@Serializable
data class Response(
    val quoteResponse: ResponseQuoteResponse = ResponseQuoteResponse(),
)

@Serializable
data class ResponseQuoteResponse(
    val result: List<ResponseQuote> = emptyList(),
)

@Serializable
data class ResponseQuote(
    val shortName: String = "",
    val symbol: String = "",
    val marketState: String = "",
    val currency: String = "",
    @SerialName("fullExchangeName") val exchangeName: String = "",
    @SerialName("exchangeDataDelayedBy") val exchangeDelay: Double = 0.0,
    val regularMarketChange: ResponseFieldFloat = ResponseFieldFloat(),
    val regularMarketChangePercent: ResponseFieldFloat = ResponseFieldFloat(),
    val regularMarketPrice: ResponseFieldFloat = ResponseFieldFloat(),
    val regularMarketPreviousClose: ResponseFieldFloat = ResponseFieldFloat(),
    val regularMarketOpen: ResponseFieldFloat = ResponseFieldFloat(),
    val regularMarketDayRange: ResponseFieldString = ResponseFieldString(),
    val regularMarketDayHigh: ResponseFieldFloat = ResponseFieldFloat(),
    val regularMarketDayLow: ResponseFieldFloat = ResponseFieldFloat(),
    val regularMarketVolume: ResponseFieldFloat = ResponseFieldFloat(),
    val postMarketChange: ResponseFieldFloat = ResponseFieldFloat(),
    val postMarketChangePercent: ResponseFieldFloat = ResponseFieldFloat(),
    val postMarketPrice: ResponseFieldFloat = ResponseFieldFloat(),
    val preMarketChange: ResponseFieldFloat = ResponseFieldFloat(),
    val preMarketChangePercent: ResponseFieldFloat = ResponseFieldFloat(),
    val preMarketPrice: ResponseFieldFloat = ResponseFieldFloat(),
    val fiftyTwoWeekHigh: ResponseFieldFloat = ResponseFieldFloat(),
    val fiftyTwoWeekLow: ResponseFieldFloat = ResponseFieldFloat(),
    val quoteType: String = "",
    val marketCap: ResponseFieldFloat = ResponseFieldFloat(),
)

@Serializable
data class ResponseFieldFloat(
    val raw: Double = 0.0,
    val fmt: String = "",
)

@Serializable
data class ResponseFieldString(
    val raw: String = "",
    val fmt: String = "",
)

private val quoteJson = Json { ignoreUnknownKeys = true }

fun parseQuoteResponse(body: String): Response = quoteJson.decodeFromString(Response.serializer(), body)

private val postMarketStatuses = setOf("POST", "POSTPOST")

// getAssetClass determines the asset class based on the quote type returned by the API
fun getAssetClass(quoteType: String): AssetClass = when (quoteType) {
    "CRYPTOCURRENCY" -> AssetClass.CRYPTOCURRENCY
    "CURRENCY" -> AssetClass.CURRENCY
    "EQUITY" -> AssetClass.STOCK
    else -> AssetClass.UNKNOWN
}

// transformResponseQuote transforms a single quote returned by the API into an AssetQuote
fun transformResponseQuote(responseQuote: ResponseQuote): AssetQuote {
    val assetClass = getAssetClass(responseQuote.quoteType)
    val isVariablePrecision = assetClass == AssetClass.CRYPTOCURRENCY

    val assetQuote = AssetQuote(
        name = responseQuote.shortName,
        symbol = responseQuote.symbol,
        assetClass = assetClass,
        currency = Currency(from = responseQuote.currency, to = ""),
        quotePrice = QuotePrice(
            price = responseQuote.regularMarketPrice.raw,
            prevClose = responseQuote.regularMarketPreviousClose.raw,
            open = responseQuote.regularMarketOpen.raw,
            dayHigh = responseQuote.regularMarketDayHigh.raw,
            dayLow = responseQuote.regularMarketDayLow.raw,
            change = responseQuote.regularMarketChange.raw,
            changePercent = responseQuote.regularMarketChangePercent.raw,
        ),
        quoteExtended = QuoteExtended(
            fiftyTwoWeekHigh = responseQuote.fiftyTwoWeekHigh.raw,
            fiftyTwoWeekLow = responseQuote.fiftyTwoWeekLow.raw,
            marketCap = responseQuote.marketCap.raw,
            volume = responseQuote.regularMarketVolume.raw,
        ),
        quoteSource = QuoteSource.YAHOO,
        exchange = ExchangeInfo(
            name = responseQuote.exchangeName,
            delay = responseQuote.exchangeDelay,
            state = ExchangeState.OPEN,
            isActive = true,
            isRegularTradingSession = true,
        ),
        meta = Meta(
            isVariablePrecision = isVariablePrecision,
            symbolInSourceApi = responseQuote.symbol,
        ),
    )

    if (responseQuote.marketState == "REGULAR") {
        return assetQuote
    }

    if (responseQuote.marketState in postMarketStatuses && responseQuote.postMarketPrice.raw == 0.0) {
        return assetQuote.copy(
            exchange = assetQuote.exchange.copy(isRegularTradingSession = false),
        )
    }

    if (responseQuote.marketState == "PRE" && responseQuote.preMarketPrice.raw == 0.0) {
        return assetQuote.copy(
            exchange = assetQuote.exchange.copy(isActive = false, isRegularTradingSession = false),
        )
    }

    if (responseQuote.marketState in postMarketStatuses) {
        return assetQuote.copy(
            quotePrice = assetQuote.quotePrice.copy(
                price = responseQuote.postMarketPrice.raw,
                change = responseQuote.postMarketChange.raw + responseQuote.regularMarketChange.raw,
                changePercent = responseQuote.postMarketChangePercent.raw + responseQuote.regularMarketChangePercent.raw,
            ),
            exchange = assetQuote.exchange.copy(isRegularTradingSession = false),
        )
    }

    if (responseQuote.marketState == "PRE") {
        return assetQuote.copy(
            quotePrice = assetQuote.quotePrice.copy(
                price = responseQuote.preMarketPrice.raw,
                change = responseQuote.preMarketChange.raw,
                changePercent = responseQuote.preMarketChangePercent.raw,
            ),
            exchange = assetQuote.exchange.copy(isRegularTradingSession = false),
        )
    }

    if (responseQuote.postMarketPrice.raw != 0.0) {
        return assetQuote.copy(
            quotePrice = assetQuote.quotePrice.copy(
                price = responseQuote.postMarketPrice.raw,
                change = responseQuote.postMarketChange.raw + responseQuote.regularMarketChange.raw,
                changePercent = responseQuote.postMarketChangePercent.raw + responseQuote.regularMarketChangePercent.raw,
            ),
            exchange = assetQuote.exchange.copy(isActive = false, isRegularTradingSession = false),
        )
    }

    return assetQuote.copy(
        exchange = assetQuote.exchange.copy(isActive = false, isRegularTradingSession = false),
    )
}

// transformResponseQuotes transforms the quotes returned by the API into a list of AssetQuote
fun transformResponseQuotes(responseQuotes: List<ResponseQuote>): List<AssetQuote> =
    responseQuotes.map(::transformResponseQuote)

// transformCurrencyRateQuotes transforms currency pair quotes (e.g. "EURUSD=X") into currency
// rates: the from currency is derived by stripping the "=X" pair suffix and then the target
// currency suffix and, when the currency has a minor form (e.g. GBP -> GBp), a rate for the
// minor currency is added scaled by its minor unit. Ported from the CLI's GetCurrencyRates.
fun transformCurrencyRateQuotes(responseQuotes: List<ResponseQuote>, toCurrency: String): CurrencyRates {
    val rates = mutableMapOf<String, CurrencyRate>()

    for (responseQuote in responseQuotes) {
        val fromCurrency = responseQuote.symbol.removeSuffix("=X").removeSuffix(toCurrency)
        rates[fromCurrency] = CurrencyRate(
            from = fromCurrency,
            to = toCurrency,
            rate = responseQuote.regularMarketPrice.raw,
        )

        val minorUnit = minorUnitForCurrencyCode(fromCurrency)
        if (minorUnit != null) {
            rates[minorUnit.code] = CurrencyRate(
                from = minorUnit.code,
                to = toCurrency,
                rate = responseQuote.regularMarketPrice.raw * 10.0.pow(-minorUnit.unit),
            )
        }
    }

    return CurrencyRates(rates)
}

// Search response models for the symbol search API. Quote keys are camelCase exactly
// as observed in live responses (shortname, longname, exchDisp), so no SerialName
// annotations are needed.

@Serializable
data class SearchResponse(
    val quotes: List<SearchResponseQuote> = emptyList(),
)

@Serializable
data class SearchResponseQuote(
    val exchange: String = "",
    val shortname: String = "",
    val longname: String = "",
    val exchDisp: String = "",
    val quoteType: String = "",
    val symbol: String = "",
)

private val searchJson = Json { ignoreUnknownKeys = true }

fun parseSearchResponse(body: String): SearchResponse = searchJson.decodeFromString(SearchResponse.serializer(), body)

// transformSearchQuotes transforms search hits into symbol search results: the display
// name comes from the short name and the exchange from the display exchange name.
fun transformSearchQuotes(response: SearchResponse): List<SymbolSearchResult> =
    response.quotes.map {
        SymbolSearchResult(
            symbol = it.symbol,
            name = it.shortname,
            exchange = it.exchDisp,
            source = SearchResultSource.YAHOO,
        )
    }
