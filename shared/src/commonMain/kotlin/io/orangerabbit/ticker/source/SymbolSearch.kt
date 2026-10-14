package io.orangerabbit.ticker.source

import io.orangerabbit.ticker.asset.QuoteSource
import io.orangerabbit.ticker.source.coinbase.CoinbaseClient
import io.orangerabbit.ticker.source.coinbase.ProductCatalogEntry
import io.orangerabbit.ticker.source.yahoo.YahooClient

enum class SearchResultSource { YAHOO, COINBASE }
data class SymbolSearchResult(val symbol: String, val name: String, val exchange: String, val source: SearchResultSource)

// SymbolSearchOutcome is one merged search: rows in proposal order plus per-source
// failure flags so the sheet can note degradation without losing partial results.
data class SymbolSearchOutcome(
    val results: List<SymbolSearchResult>,
    val yahooFailed: Boolean,
    val catalogFailed: Boolean,
)

// SymbolSearch merges Yahoo symbol search with the Coinbase product catalog into one
// candidate list of proposed symbols. Yahoo hits are proposed verbatim first
// (server-ranked, canonical for that source); catalog products are proposed as the
// configured ".X" shorthand when the ticker-static map has one for the product id, else
// "<baseDisplaySymbol>.CB" — the same rule as transformProduct. Rows proposing the same
// symbol (case-insensitively) collapse to the first one. When the catalog cannot be
// fetched, matching falls back to the COINBASE rows of the symbol map keyed by source
// symbol only ("symbol match only" in the UI).
class SymbolSearch(
    private val yahoo: YahooClient,
    private val coinbase: CoinbaseClient,
    private val symbols: SymbolsClient,
) {

    suspend fun search(query: String): SymbolSearchOutcome {
        val yahooResult = yahoo.search(query)
        val symbolMap = symbols.fetchSymbolMap()
        val reverse = reverseSymbolMap(symbolMap)
        val catalogResult = coinbase.listProducts()

        val results = mutableListOf<SymbolSearchResult>()
        val seen = mutableSetOf<String>()

        fun offer(row: SymbolSearchResult) {
            if (results.size >= maxResults) return
            if (!seen.add(row.symbol.lowercase())) return
            results.add(row)
        }

        yahooResult.getOrNull()?.take(maxYahooResults)?.forEach(::offer)

        // a failed catalog degrades to the symbol map's Coinbase rows: one synthesized
        // entry per configured product id, matched against the product id alone since
        // the map carries no display names
        val catalogEntries = catalogResult.getOrNull()
        val entries = catalogEntries ?: symbolMap.values
            .filter { it.source == QuoteSource.COINBASE }
            .map { ProductCatalogEntry(productId = it.sourceSymbol, baseName = "", baseDisplaySymbol = "") }

        // an entry matches the raw query substring against the product id or display
        // name, and additionally the alphanumeric-stripped forms so a compacted query
        // ("btcusd") also hits hyphenated product ids ("BTC-USD")
        val compactQuery = query.filter { it.isLetterOrDigit() }
        for (entry in entries) {
            val matched = entry.productId.contains(query, ignoreCase = true) ||
                entry.baseName.contains(query, ignoreCase = true) ||
                (compactQuery.isNotEmpty() && (
                    entry.productId.filter { it.isLetterOrDigit() }
                        .contains(compactQuery, ignoreCase = true) ||
                        entry.baseName.filter { it.isLetterOrDigit() }
                            .contains(compactQuery, ignoreCase = true)
                    ))
            if (!matched) continue
            offer(
                SymbolSearchResult(
                    symbol = reverse[entry.productId] ?: entry.baseDisplaySymbol + ".CB",
                    name = entry.baseName,
                    exchange = coinbaseExchange,
                    source = SearchResultSource.COINBASE,
                ),
            )
        }

        return SymbolSearchOutcome(
            results = results,
            yahooFailed = yahooResult.isFailure,
            catalogFailed = catalogEntries == null,
        )
    }

    companion object {
        // the merged list keeps at most 15 proposals; Yahoo contributes at most its
        // server-side quotesCount=10 page
        private const val maxResults = 15
        private const val maxYahooResults = 10
        private const val coinbaseExchange = "Coinbase"
    }
}
