package io.orangerabbit.ticker.source.yahoo

import io.orangerabbit.ticker.asset.AssetClass
import io.orangerabbit.ticker.asset.ExchangeState
import io.orangerabbit.ticker.asset.QuoteSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YahooTransformTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/yahoo/$name")) { "missing fixture $name" }.readText()

    @Test
    fun `transforms regular session stock and crypto quotes`() {
        val quotes = transformResponseQuotes(parseQuoteResponse(fixture("quote-regular.json")).quoteResponse.result)

        assertEquals(2, quotes.size)

        val aapl = quotes[0]
        assertEquals("Apple Inc.", aapl.name)
        assertEquals("AAPL", aapl.symbol)
        assertEquals(AssetClass.STOCK, aapl.assetClass)
        assertEquals("USD", aapl.currency.from)
        assertEquals(262.24, aapl.quotePrice.price)
        assertEquals(2.38, aapl.quotePrice.change)
        assertEquals(0.9159, aapl.quotePrice.changePercent)
        assertEquals(259.86, aapl.quotePrice.prevClose)
        assertEquals(260.42, aapl.quotePrice.open)
        assertEquals(263.10, aapl.quotePrice.dayHigh)
        assertEquals(259.72, aapl.quotePrice.dayLow)
        assertFalse(aapl.meta.isVariablePrecision)
        assertEquals(QuoteSource.YAHOO, aapl.quoteSource)
        assertEquals("NasdaqGS", aapl.exchange.name)
        assertEquals(ExchangeState.OPEN, aapl.exchange.state)
        assertTrue(aapl.exchange.isActive)
        assertTrue(aapl.exchange.isRegularTradingSession)
        assertEquals("AAPL", aapl.meta.symbolInSourceApi)

        val btc = quotes[1]
        assertEquals("Bitcoin USD", btc.name)
        assertEquals("BTC-USD", btc.symbol)
        assertEquals(AssetClass.CRYPTOCURRENCY, btc.assetClass)
        assertEquals("USD", btc.currency.from)
        assertEquals(107350.1191406, btc.quotePrice.price)
        assertEquals(1250.3457031, btc.quotePrice.change)
        assertEquals(1.1782, btc.quotePrice.changePercent)
        assertTrue(btc.meta.isVariablePrecision)
    }

    @Test
    fun `transforms post market quote to post prices`() {
        val quotes = transformResponseQuotes(parseQuoteResponse(fixture("quote-post.json")).quoteResponse.result)

        assertEquals(1, quotes.size)

        val nvda = quotes[0]
        assertEquals(AssetClass.STOCK, nvda.assetClass)
        assertEquals(184.05, nvda.quotePrice.price)
        assertClose(0.83 + 1.34, nvda.quotePrice.change)
        assertClose(0.453 + 0.7368, nvda.quotePrice.changePercent)
        assertFalse(nvda.exchange.isRegularTradingSession)
        assertTrue(nvda.exchange.isActive)
    }

    @Test
    fun `maps quote types to asset classes`() {
        assertEquals(AssetClass.CRYPTOCURRENCY, getAssetClass("CRYPTOCURRENCY"))
        assertEquals(AssetClass.CURRENCY, getAssetClass("CURRENCY"))
        assertEquals(AssetClass.STOCK, getAssetClass("EQUITY"))
        assertEquals(AssetClass.UNKNOWN, getAssetClass("MUTUALFUND"))
        assertEquals(AssetClass.UNKNOWN, getAssetClass(""))
    }

    private fun assertClose(expected: Double, actual: Double) {
        assertTrue(kotlin.math.abs(expected - actual) < 1e-9, "expected <$expected> but was <$actual>")
    }
}
