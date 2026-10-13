package io.orangerabbit.ticker.poll

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.orangerabbit.ticker.asset.AssetClass
import io.orangerabbit.ticker.asset.AssetQuote
import io.orangerabbit.ticker.asset.Currency
import io.orangerabbit.ticker.asset.CurrencyRate
import io.orangerabbit.ticker.asset.CurrencyRates
import io.orangerabbit.ticker.asset.ExchangeInfo
import io.orangerabbit.ticker.asset.Meta
import io.orangerabbit.ticker.asset.QuoteExtended
import io.orangerabbit.ticker.asset.QuotePrice
import io.orangerabbit.ticker.asset.QuoteSource
import io.orangerabbit.ticker.config.ConfigGroup
import io.orangerabbit.ticker.config.Lot
import io.orangerabbit.ticker.config.TickerConfig
import io.orangerabbit.ticker.source.SymbolsClient
import io.orangerabbit.ticker.source.coinbase.CoinbaseQuoteSource
import io.orangerabbit.ticker.source.yahoo.YahooQuoteSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeYahoo(
    private val quotes: suspend (List<String>) -> Result<List<AssetQuote>> = { Result.success(emptyList()) },
    private val rates: suspend (List<String>, String) -> Result<CurrencyRates> = { _, _ -> Result.success(CurrencyRates(emptyMap())) },
) : YahooQuoteSource {

    val quoteCalls = mutableListOf<List<String>>()
    val rateCalls = mutableListOf<Pair<List<String>, String>>()

    override suspend fun getQuotes(symbols: List<String>): Result<List<AssetQuote>> {
        quoteCalls.add(symbols)
        return quotes(symbols)
    }

    override suspend fun getCurrencyRates(fromCurrencies: List<String>, toCurrency: String): Result<CurrencyRates> {
        rateCalls.add(fromCurrencies to toCurrency)
        return rates(fromCurrencies, toCurrency)
    }
}

private class FakeCoinbase(
    private val quotes: suspend (List<String>) -> Result<List<AssetQuote>> = { Result.success(emptyList()) },
) : CoinbaseQuoteSource {

    val quoteCalls = mutableListOf<List<String>>()

    override suspend fun getQuotes(productIds: List<String>): Result<List<AssetQuote>> {
        quoteCalls.add(productIds)
        return quotes(productIds)
    }
}

private fun fakeSymbols(csv: String = ""): SymbolsClient =
    SymbolsClient(HttpClient(MockEngine { respond(csv, HttpStatusCode.OK) }))

private fun quote(
    symbol: String,
    price: Double = 100.0,
    from: String = "USD",
    symbolInSourceApi: String = "",
    source: QuoteSource = QuoteSource.YAHOO,
    assetClass: AssetClass = AssetClass.STOCK,
): AssetQuote = AssetQuote(
    name = symbol,
    symbol = symbol,
    assetClass = assetClass,
    currency = Currency(from = from, to = ""),
    quotePrice = QuotePrice(price = price),
    quoteExtended = QuoteExtended(),
    quoteSource = source,
    exchange = ExchangeInfo(),
    meta = Meta(isVariablePrecision = source == QuoteSource.COINBASE, symbolInSourceApi = symbolInSourceApi),
)

@OptIn(ExperimentalCoroutinesApi::class)
class QuotePollerTest {

    @Test
    fun `initial refresh then interval ticks`() = runTest {
        val yahoo = FakeYahoo(quotes = { symbols -> Result.success(symbols.map { quote(it) }) })
        val poller = QuotePoller(yahoo, FakeCoinbase(), fakeSymbols(), clock = { testScheduler.currentTime })

        poller.start(TickerConfig(interval = 30, groups = listOf(ConfigGroup(name = "g", watchlist = listOf("AAPL")))))

        assertEquals(listOf(listOf("AAPL")), yahoo.quoteCalls)
        assertNull(poller.state.value.summary)

        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(2, yahoo.quoteCalls.size)

        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(3, yahoo.quoteCalls.size)

        poller.stop()
    }

    @Test
    fun `interval floor keeps tick spacing at 30 seconds`() = runTest {
        val callTimes = mutableListOf<Long>()
        val yahoo = FakeYahoo(quotes = { symbols ->
            callTimes.add(testScheduler.currentTime)
            Result.success(symbols.map { quote(it) })
        })
        val poller = QuotePoller(yahoo, FakeCoinbase(), fakeSymbols(), clock = { testScheduler.currentTime })

        poller.start(TickerConfig(interval = 5, groups = listOf(ConfigGroup(name = "g", watchlist = listOf("AAPL")))))

        assertEquals(listOf(0L), callTimes)

        advanceTimeBy(25_000)
        runCurrent()
        assertEquals(listOf(0L), callTimes)

        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf(0L, 30_000L), callTimes)

        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(listOf(0L, 30_000L, 60_000L), callTimes)

        poller.stop()
    }

    @Test
    fun `group switch refreshes immediately with the new group symbols`() = runTest {
        val yahoo = FakeYahoo(quotes = { symbols -> Result.success(symbols.map { quote(it) }) })
        val poller = QuotePoller(yahoo, FakeCoinbase(), fakeSymbols(), clock = { testScheduler.currentTime })
        val groups = listOf(
            ConfigGroup(name = "a", watchlist = listOf("AAPL")),
            ConfigGroup(name = "b", watchlist = listOf("MSFT", "GOOG")),
        )

        poller.start(TickerConfig(interval = 30, groups = groups), groupIndex = 0)
        assertEquals(listOf(listOf("AAPL")), yahoo.quoteCalls)

        poller.selectGroup(1)

        assertEquals(listOf(listOf("AAPL"), listOf("MSFT", "GOOG")), yahoo.quoteCalls)
        assertEquals(groups[1], poller.state.value.group)
        assertEquals(setOf("MSFT", "GOOG"), poller.state.value.assets.map { it.symbol }.toSet())

        poller.stop()
    }

    @Test
    fun `source failure keeps stale quotes and sets a sanitized error`() = runTest {
        var call = 0
        val yahoo = FakeYahoo(quotes = {
            call++
            if (call == 1) {
                Result.success(listOf(quote("AAPL")))
            } else {
                Result.failure(Exception("boom http://leak.example"))
            }
        })
        val poller = QuotePoller(yahoo, FakeCoinbase(), fakeSymbols(), clock = { testScheduler.currentTime })

        poller.start(TickerConfig(interval = 30, groups = listOf(ConfigGroup(name = "g", watchlist = listOf("AAPL")))))
        assertEquals(listOf("AAPL"), poller.state.value.assets.map { it.symbol })
        assertNull(poller.state.value.error)

        advanceTimeBy(30_000)
        runCurrent()

        val state = poller.state.value
        assertEquals(listOf("AAPL"), state.assets.map { it.symbol })
        val error = assertNotNull(state.error)
        assertTrue(error.startsWith("yahoo:"))
        assertTrue("boom" !in error)
        assertTrue("leak.example" !in error)

        poller.stop()
    }

    @Test
    fun `missing symbol lands in unavailable`() = runTest {
        val yahoo = FakeYahoo(quotes = { Result.success(listOf(quote("AAPL"))) })
        val poller = QuotePoller(yahoo, FakeCoinbase(), fakeSymbols(), clock = { testScheduler.currentTime })

        poller.start(TickerConfig(interval = 30, groups = listOf(ConfigGroup(name = "g", watchlist = listOf("AAPL", "MSFT")))))

        assertEquals(listOf("AAPL"), poller.state.value.assets.map { it.symbol })
        assertEquals(listOf("MSFT"), poller.state.value.unavailable)

        poller.stop()
    }

    @Test
    fun `rates are merged into the portfolio summary`() = runTest {
        val yahoo = FakeYahoo(
            quotes = { Result.success(listOf(quote("AAPL", price = 100.0, from = "USD"))) },
            rates = { _, to ->
                Result.success(CurrencyRates(mapOf("USD" to CurrencyRate(from = "USD", to = to, rate = 2.0))))
            },
        )
        val poller = QuotePoller(yahoo, FakeCoinbase(), fakeSymbols(), clock = { testScheduler.currentTime })

        poller.start(
            TickerConfig(
                interval = 30,
                currency = "EUR",
                groups = listOf(ConfigGroup(name = "g", lots = listOf(Lot(symbol = "AAPL", quantity = 2.0, unitCost = 50.0)))),
            ),
        )

        assertEquals(listOf(listOf("USD") to "EUR"), yahoo.rateCalls)
        assertEquals(200.0, poller.state.value.assets.single().quotePrice.price)
        assertEquals(400.0, poller.state.value.summary!!.value)

        poller.stop()
    }

    @Test
    fun `stop halts the loop and retains the last state`() = runTest {
        val yahoo = FakeYahoo(quotes = { Result.success(listOf(quote("AAPL"))) })
        val poller = QuotePoller(yahoo, FakeCoinbase(), fakeSymbols(), clock = { testScheduler.currentTime })

        poller.start(TickerConfig(interval = 30, groups = listOf(ConfigGroup(name = "g", watchlist = listOf("AAPL")))))
        poller.stop()

        advanceTimeBy(120_000)
        runCurrent()

        assertEquals(1, yahoo.quoteCalls.size)
        assertEquals(listOf("AAPL"), poller.state.value.assets.map { it.symbol })

        poller.stop()
    }

    @Test
    fun `start after stop resumes polling cleanly`() = runTest {
        val yahoo = FakeYahoo(quotes = { Result.success(listOf(quote("AAPL"))) })
        val poller = QuotePoller(yahoo, FakeCoinbase(), fakeSymbols(), clock = { testScheduler.currentTime })
        val config = TickerConfig(interval = 30, groups = listOf(ConfigGroup(name = "g", watchlist = listOf("AAPL"))))

        poller.start(config)
        poller.stop()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, yahoo.quoteCalls.size)

        poller.start(config)
        assertEquals(2, yahoo.quoteCalls.size)

        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(3, yahoo.quoteCalls.size)

        poller.stop()
    }

    @Test
    fun `stop during suspended startup prevents the poll loop from installing`() = runTest {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val yahoo = FakeYahoo(quotes = { symbols ->
            started.complete(Unit)
            gate.await()
            Result.success(symbols.map { quote(it) })
        })
        val poller = QuotePoller(yahoo, FakeCoinbase(), fakeSymbols(), clock = { testScheduler.currentTime })

        val starter = launch {
            poller.start(TickerConfig(interval = 30, groups = listOf(ConfigGroup(name = "g", watchlist = listOf("AAPL")))))
        }
        started.await()
        assertEquals(listOf(listOf("AAPL")), yahoo.quoteCalls)
        assertTrue(poller.state.value.isLoading)

        poller.stop()
        runCurrent()
        gate.complete(Unit)
        starter.join()

        assertFalse(poller.state.value.isLoading)
        assertTrue(poller.state.value.assets.isEmpty())

        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, yahoo.quoteCalls.size)
    }

    @Test
    fun `a superseded start cannot install a second poll loop`() = runTest {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        var call = 0
        val yahoo = FakeYahoo(quotes = { symbols ->
            call++
            if (call == 1) {
                started.complete(Unit)
                gate.await()
            }
            Result.success(symbols.map { quote(it) })
        })
        val poller = QuotePoller(yahoo, FakeCoinbase(), fakeSymbols(), clock = { testScheduler.currentTime })
        val groupsA = listOf(ConfigGroup(name = "a", watchlist = listOf("AAPL")))
        val groupsB = listOf(ConfigGroup(name = "b", watchlist = listOf("MSFT")))

        val starter1 = launch { poller.start(TickerConfig(interval = 30, groups = groupsA)) }
        started.await()
        val starter2 = launch { poller.start(TickerConfig(interval = 30, groups = groupsB)) }
        runCurrent()
        gate.complete(Unit)
        starter1.join()
        starter2.join()

        assertEquals(listOf(listOf("AAPL"), listOf("MSFT")), yahoo.quoteCalls)

        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(3, yahoo.quoteCalls.size)
        assertEquals(listOf("MSFT"), poller.state.value.assets.map { it.symbol })

        poller.stop()
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(3, yahoo.quoteCalls.size)
    }

    @Test
    fun `stop during a suspended request resets loading and retains state`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var call = 0
        val yahoo = FakeYahoo(quotes = { symbols ->
            call++
            if (call == 2) {
                gate.await()
            }
            Result.success(symbols.map { quote(it) })
        })
        val poller = QuotePoller(yahoo, FakeCoinbase(), fakeSymbols(), clock = { testScheduler.currentTime })

        poller.start(TickerConfig(interval = 30, groups = listOf(ConfigGroup(name = "g", watchlist = listOf("AAPL")))))
        assertEquals(listOf("AAPL"), poller.state.value.assets.map { it.symbol })

        advanceTimeBy(30_000)
        runCurrent()
        assertTrue(poller.state.value.isLoading)

        poller.stop()
        runCurrent()

        assertFalse(poller.state.value.isLoading)
        assertEquals(listOf("AAPL"), poller.state.value.assets.map { it.symbol })

        gate.complete(Unit)
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(2, yahoo.quoteCalls.size)
    }

    @Test
    fun `X shorthand lot is matched to its coinbase quote before aggregation`() = runTest {
        val coinbase = FakeCoinbase(quotes = {
            Result.success(
                listOf(
                    quote(
                        symbol = "SOL.CB",
                        price = 100.0,
                        from = "USD",
                        symbolInSourceApi = "SOL-USD",
                        source = QuoteSource.COINBASE,
                        assetClass = AssetClass.CRYPTOCURRENCY,
                    ),
                ),
            )
        })
        val poller = QuotePoller(FakeYahoo(), coinbase, fakeSymbols("SOL.X,SOL-USD,cb\n"), clock = { testScheduler.currentTime })

        poller.start(
            TickerConfig(
                interval = 30,
                groups = listOf(ConfigGroup(name = "g", lots = listOf(Lot(symbol = "SOL.X", quantity = 2.0, unitCost = 50.0)))),
            ),
        )

        assertEquals(listOf(listOf("SOL-USD")), coinbase.quoteCalls)
        val asset = poller.state.value.assets.single()
        assertEquals("SOL.X", asset.symbol)
        assertEquals(2.0, asset.position.quantity)
        assertEquals(100.0, asset.position.cost)
        assertEquals(200.0, asset.position.value)
        assertEquals(200.0, poller.state.value.summary!!.value)
        assertTrue(poller.state.value.unavailable.isEmpty())

        poller.stop()
    }
}
