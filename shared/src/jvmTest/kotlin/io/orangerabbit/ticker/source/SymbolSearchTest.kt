package io.orangerabbit.ticker.source

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.orangerabbit.ticker.source.coinbase.CoinbaseClient
import io.orangerabbit.ticker.source.yahoo.InMemorySessionStore
import io.orangerabbit.ticker.source.yahoo.YahooClient
import io.orangerabbit.ticker.source.yahoo.YahooSession
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SymbolSearchTest {

    private fun yahooClient(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): YahooClient {
        val store = InMemorySessionStore()
        store.put(YahooSession(cookies = listOf("A3" to "seed-a3"), crumb = "seed.crumb"))
        return YahooClient(HttpClient(MockEngine { respond(body, status) }), store)
    }

    private fun coinbaseClient(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): CoinbaseClient = CoinbaseClient(HttpClient(MockEngine { respond(body, status) }))

    private fun symbolsClient(csv: String): SymbolsClient =
        SymbolsClient(HttpClient(MockEngine { respond(csv, HttpStatusCode.OK) }))

    private suspend fun search(
        query: String,
        yahoo: String = yahooBody(),
        yahooStatus: HttpStatusCode = HttpStatusCode.OK,
        catalog: String = catalogBody(),
        catalogStatus: HttpStatusCode = HttpStatusCode.OK,
        csv: String = "",
    ): SymbolSearchOutcome = SymbolSearch(
        yahooClient(yahoo, yahooStatus),
        coinbaseClient(catalog, catalogStatus),
        symbolsClient(csv),
    ).search(query)

    private fun yahooQuote(symbol: String, name: String = "Name of $symbol", exchange: String = "NASDAQ"): String =
        """{"symbol":"$symbol","shortname":"$name","exchDisp":"$exchange"}"""

    private fun yahooBody(vararg quotes: String): String =
        """{"quotes":[${quotes.joinToString(",")}]}"""

    private fun product(id: String, name: String = "Name of $id", symbol: String = id.substringBefore("-")): String =
        """{"product_id":"$id","base_name":"$name","base_display_symbol":"$symbol","product_type":"SPOT","status":"online"}"""

    private fun catalogBody(vararg products: String): String =
        """{"products":[${products.joinToString(",")}],"pagination":{"next_cursor":"","has_next":false}}"""

    @Test
    fun `merges yahoo results first then coinbase proposals`() = runTest {
        val outcome = search(
            query = "usd",
            yahoo = yahooBody(yahooQuote("AAPL", "Apple Inc."), yahooQuote("MSFT", "Microsoft")),
            catalog = catalogBody(product("BTC-USD", "Bitcoin", "BTC"), product("ETH-USD", "Ethereum", "ETH")),
            csv = "BTC.X,BTC-USD,cb\n",
        )

        assertEquals(
            listOf(
                SymbolSearchResult("AAPL", "Apple Inc.", "NASDAQ", SearchResultSource.YAHOO),
                SymbolSearchResult("MSFT", "Microsoft", "NASDAQ", SearchResultSource.YAHOO),
                SymbolSearchResult("BTC.X", "Bitcoin", "Coinbase", SearchResultSource.COINBASE),
                SymbolSearchResult("ETH.CB", "Ethereum", "Coinbase", SearchResultSource.COINBASE),
            ),
            outcome.results,
        )
        assertFalse(outcome.yahooFailed)
        assertFalse(outcome.catalogFailed)
    }

    @Test
    fun `dedupe keeps one row when both sources propose the same symbol`() = runTest {
        val outcome = search(
            query = "btc",
            yahoo = yahooBody(yahooQuote("BTC.X", "Bitcoin", "CCC")),
            catalog = catalogBody(product("BTC-USD", "Bitcoin", "BTC")),
            csv = "BTC.X,BTC-USD,cb\n",
        )

        assertEquals(
            listOf(SymbolSearchResult("BTC.X", "Bitcoin", "CCC", SearchResultSource.YAHOO)),
            outcome.results,
        )
    }

    @Test
    fun `dedupe is case-insensitive on the proposed symbol`() = runTest {
        val outcome = search(
            query = "btc",
            yahoo = yahooBody(yahooQuote("btc.x", "Bitcoin", "CCC")),
            catalog = catalogBody(product("BTC-USD", "Bitcoin", "BTC")),
            csv = "BTC.X,BTC-USD,cb\n",
        )

        assertEquals(listOf("btc.x"), outcome.results.map { it.symbol })
    }

    @Test
    fun `caps merged results at fifteen keeping yahoo order`() = runTest {
        val yahooQuotes = (1..10).map { yahooQuote("Y%02d".format(it)) }.toTypedArray()
        val products = (1..10).map { product("C%02d-USD".format(it), symbol = "C%02d".format(it)) }.toTypedArray()
        val outcome = search(
            query = "usd",
            yahoo = yahooBody(*yahooQuotes),
            catalog = catalogBody(*products),
        )

        assertEquals(15, outcome.results.size)
        assertEquals((1..10).map { "Y%02d".format(it) }, outcome.results.take(10).map { it.symbol })
        assertEquals(
            listOf("C01.CB", "C02.CB", "C03.CB", "C04.CB", "C05.CB"),
            outcome.results.drop(10).map { it.symbol },
        )
    }

    @Test
    fun `caps yahoo results at ten per quotesCount`() = runTest {
        val yahooQuotes = (1..12).map { yahooQuote("Y%02d".format(it)) }.toTypedArray()
        val outcome = search(
            query = "usd",
            yahoo = yahooBody(*yahooQuotes),
            catalog = catalogBody(product("ETH-USD", "Ethereum", "ETH")),
        )

        assertEquals(
            (1..10).map { "Y%02d".format(it) } + listOf("ETH.CB"),
            outcome.results.map { it.symbol },
        )
    }

    @Test
    fun `proposes CB suffix when no shorthand maps the product`() = runTest {
        val outcome = search(
            query = "eth",
            catalog = catalogBody(product("ETH-USD", "Ethereum", "ETH")),
            csv = "BTC.X,BTC-USD,cb\n",
        )

        assertEquals(
            listOf(SymbolSearchResult("ETH.CB", "Ethereum", "Coinbase", SearchResultSource.COINBASE)),
            outcome.results,
        )
    }

    @Test
    fun `catalog failure falls back to csv entries with catalogFailed`() = runTest {
        val outcome = search(
            query = "btc",
            catalogStatus = HttpStatusCode.BadGateway,
            csv = "BTC.X,BTC-USD,cb\nSOL.X,SOL-USD,cb\n",
        )

        assertEquals(
            listOf(SymbolSearchResult("BTC.X", "", "Coinbase", SearchResultSource.COINBASE)),
            outcome.results,
        )
        assertTrue(outcome.catalogFailed)
        assertFalse(outcome.yahooFailed)
    }

    @Test
    fun `fallback matches product ids only not proposed tickers`() = runTest {
        val outcome = search(
            query = "btc.x",
            catalogStatus = HttpStatusCode.BadGateway,
            csv = "BTC.X,BTC-USD,cb\n",
        )

        assertEquals(emptyList(), outcome.results)
        assertTrue(outcome.catalogFailed)
    }

    @Test
    fun `yahoo failure sets the flag and contributes no rows`() = runTest {
        val outcome = search(
            query = "eth",
            yahooStatus = HttpStatusCode.BadGateway,
            catalog = catalogBody(product("ETH-USD", "Ethereum", "ETH")),
        )

        assertEquals(
            listOf(SymbolSearchResult("ETH.CB", "Ethereum", "Coinbase", SearchResultSource.COINBASE)),
            outcome.results,
        )
        assertTrue(outcome.yahooFailed)
        assertFalse(outcome.catalogFailed)
    }

    @Test
    fun `catalog fallback survives a yahoo failure`() = runTest {
        val outcome = search(
            query = "btc",
            yahooStatus = HttpStatusCode.BadGateway,
            catalogStatus = HttpStatusCode.BadGateway,
            csv = "BTC.X,BTC-USD,cb\n",
        )

        assertEquals(
            listOf(SymbolSearchResult("BTC.X", "", "Coinbase", SearchResultSource.COINBASE)),
            outcome.results,
        )
        assertTrue(outcome.yahooFailed)
        assertTrue(outcome.catalogFailed)
    }

    @Test
    fun `both sources failing with no map rows returns empty results`() = runTest {
        val outcome = search(
            query = "btc",
            yahooStatus = HttpStatusCode.BadGateway,
            catalogStatus = HttpStatusCode.BadGateway,
        )

        assertEquals(emptyList(), outcome.results)
        assertTrue(outcome.yahooFailed)
        assertTrue(outcome.catalogFailed)
    }

    @Test
    fun `query with no matches returns empty results`() = runTest {
        val outcome = search(
            query = "zzz",
            catalog = catalogBody(product("BTC-USD", "Bitcoin", "BTC")),
        )

        assertEquals(emptyList(), outcome.results)
        assertFalse(outcome.yahooFailed)
        assertFalse(outcome.catalogFailed)
    }
}
