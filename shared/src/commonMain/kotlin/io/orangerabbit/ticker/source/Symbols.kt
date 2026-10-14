package io.orangerabbit.ticker.source

import io.orangerabbit.ticker.asset.QuoteSource
import io.orangerabbit.ticker.config.ConfigGroup

data class SymbolSourceMap(
    val tickerSymbol: String,
    val sourceSymbol: String,
    val source: QuoteSource,
)

typealias TickerSymbolMap = Map<String, SymbolSourceMap>

data class ResolvedGroup(
    val group: ConfigGroup,
    val yahooSymbols: List<String>,
    val coinbaseProducts: List<String>,
    val unresolved: List<String>,
    // source-qualified maps from the source-native identity (yahoo symbol, coinbase product
    // id) back to EVERY configured ticker symbol so returned quotes can be re-keyed to all
    // of the user's config entries (a group may watch SOL.X and hold a SOL-USD.CB lot that
    // both resolve to one product); qualification keeps a coinbase-configured ticker from
    // ever claiming a yahoo symbol or vice versa, and identities keep config order
    val tickerByYahooSymbol: Map<String, List<String>> = emptyMap(),
    val tickerByCoinbaseProduct: Map<String, List<String>> = emptyMap(),
)

// Port of the CLI's ticker-static symbol CSV parse: rows [tickerSymbol, sourceSymbol, sourceId],
// sourceId "cb" maps to Coinbase and anything else to Unknown. The first row wins for a
// duplicated ticker symbol. Parsing is quote-tolerant (Go csv LazyQuotes) and never errors.
fun parseSymbolsCsv(body: String): TickerSymbolMap {
    val map = LinkedHashMap<String, SymbolSourceMap>()
    for (record in parseCsvRecords(body)) {
        if (record.size < 3) continue
        val tickerSymbol = record[0]
        if (tickerSymbol.isEmpty()) continue
        if (map.containsKey(tickerSymbol)) continue
        map[tickerSymbol] = SymbolSourceMap(
            tickerSymbol = tickerSymbol,
            sourceSymbol = record[1],
            source = when (record[2]) {
                "cb" -> QuoteSource.COINBASE
                else -> QuoteSource.UNKNOWN
            },
        )
    }
    return map
}

// Port of the CLI's group symbol routing. Symbols without a suffix go to Yahoo; a ".CB"
// suffix is stripped to a Coinbase product id; a ".X" suffix is resolved through the
// ticker-static map (exact key, the map key keeps the ".X" suffix) to its source-native
// symbol. Symbols in lots that are not on the watchlist (case-insensitive) are implicitly
// appended after the watchlist entries, in lot order, keeping the first lot form for
// duplicates (CLI getAssetGroups: watchlist loop first, then the lots loop appends).
fun resolveGroup(group: ConfigGroup, symbolMap: TickerSymbolMap): ResolvedGroup {
    val watchlist = implicitWatchlist(group)
    val unresolved = mutableListOf<String>()
    val tickerByYahooSymbol = LinkedHashMap<String, List<String>>()
    val tickerByCoinbaseProduct = LinkedHashMap<String, List<String>>()

    // addIdentity retains every configured ticker symbol per source-native identity in
    // config order so one product quote can be reconciled to each of them
    fun addIdentity(map: LinkedHashMap<String, List<String>>, native: String, ticker: String) {
        val tickers = map[native]
        map[native] = when {
            tickers == null -> listOf(ticker)
            ticker in tickers -> tickers
            else -> tickers + ticker
        }
    }

    for (symbol in watchlist) {
        when {
            symbol.endsWith(".X") -> {
                val mapped = symbolMap[symbol]
                if (mapped != null && mapped.source == QuoteSource.COINBASE) {
                    addIdentity(tickerByCoinbaseProduct, mapped.sourceSymbol, symbol)
                } else {
                    unresolved.add(symbol)
                }
            }
            symbol.endsWith(".CB") -> addIdentity(tickerByCoinbaseProduct, symbol.removeSuffix(".CB"), symbol)
            else -> addIdentity(tickerByYahooSymbol, symbol, symbol)
        }
    }

    return ResolvedGroup(
        group = group.copy(watchlist = watchlist),
        yahooSymbols = tickerByYahooSymbol.keys.toList(),
        coinbaseProducts = tickerByCoinbaseProduct.keys.toList(),
        unresolved = unresolved,
        tickerByYahooSymbol = tickerByYahooSymbol,
        tickerByCoinbaseProduct = tickerByCoinbaseProduct,
    )
}

private fun implicitWatchlist(group: ConfigGroup): List<String> {
    val seen = group.watchlist.mapTo(HashSet()) { it.lowercase() }
    val additions = mutableListOf<String>()
    for (lot in group.lots) {
        if (seen.add(lot.symbol.lowercase())) {
            additions.add(lot.symbol)
        }
    }
    return group.watchlist + additions
}

// Splits CSV records the way encoding/csv does with LazyQuotes: blank lines are skipped,
// doubled quotes inside a quoted field produce one quote, quotes not at a field start are
// literal, and an unterminated quoted field is kept verbatim.
private fun parseCsvRecords(body: String): List<List<String>> {
    val records = mutableListOf<List<String>>()
    var record = mutableListOf<String>()
    val field = StringBuilder()
    var inQuotes = false
    var i = 0

    fun endField() {
        record.add(field.toString())
        field.clear()
    }

    fun endRecord() {
        endField()
        records.add(record)
        record = mutableListOf()
    }

    while (i < body.length) {
        val c = body[i]
        when {
            inQuotes && c == '"' && i + 1 < body.length && body[i + 1] == '"' -> {
                field.append('"')
                i++
            }
            inQuotes && c == '"' -> inQuotes = false
            inQuotes -> field.append(c)
            c == '"' && field.isEmpty() -> inQuotes = true
            c == ',' -> endField()
            c == '\r' -> {
                if (i + 1 < body.length && body[i + 1] == '\n') i++
                endRecord()
            }
            c == '\n' -> endRecord()
            else -> field.append(c)
        }
        i++
    }
    if (field.isNotEmpty() || record.isNotEmpty()) {
        endRecord()
    }
    return records.filter { it.size >= 3 }
}