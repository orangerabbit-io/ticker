package io.orangerabbit.ticker.source.coinbase

import io.orangerabbit.ticker.asset.AssetClass
import io.orangerabbit.ticker.asset.QuoteSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoinbaseTransformTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/coinbase/$name")) { "missing fixture $name" }.readText()

    @Test
    fun `transforms spot product quotes`() {
        val quotes = transformProducts(parseProductsResponse(fixture("products.json")).products)

        assertEquals(3, quotes.size)

        val btc = quotes[0]
        assertEquals("Bitcoin", btc.name)
        assertEquals("BTC.CB", btc.symbol)
        assertEquals(AssetClass.CRYPTOCURRENCY, btc.assetClass)
        assertEquals("USD", btc.currency.from)
        assertEquals(107500.12, btc.quotePrice.price)
        assertEquals(-1.2345, btc.quotePrice.changePercent)
        assertClose(107500.12 * -1.2345 / 100, btc.quotePrice.change)
        assertEquals(8123.45, btc.quoteExtended.volume)
        assertTrue(btc.exchange.isActive)
        assertTrue(btc.meta.isVariablePrecision)
        assertEquals(QuoteSource.COINBASE, btc.quoteSource)

        val sol = quotes[1]
        assertEquals("Solana", sol.name)
        assertEquals("SOL.CB", sol.symbol)
        assertEquals(205.50, sol.quotePrice.price)
        assertEquals(3.25, sol.quotePrice.changePercent)
        assertTrue(sol.exchange.isActive)
    }

    @Test
    fun `skips future products`() {
        val quotes = transformProducts(parseProductsResponse(fixture("products.json")).products)

        assertTrue(quotes.none { it.symbol == "FTM.CB" }, "future product must not be emitted but was <$quotes>")
    }

    @Test
    fun `missing price fields parse to zero without error`() {
        val quotes = transformProducts(parseProductsResponse(fixture("products.json")).products)

        val eth = quotes[2]
        assertEquals("ETH.CB", eth.symbol)
        assertEquals(0.0, eth.quotePrice.price)
        assertEquals(0.0, eth.quotePrice.changePercent)
        assertEquals(0.0, eth.quotePrice.change)
        assertEquals(0.0, eth.quoteExtended.volume)
    }

    @Test
    fun `maps product status to active state`() {
        val quotes = transformProducts(parseProductsResponse(fixture("products.json")).products)

        assertTrue(quotes[0].exchange.isActive)
        assertTrue(!quotes[2].exchange.isActive, "expected ETH.CB inactive but was <${quotes[2].exchange}>")
    }

    @Test
    fun `uppercases quote currency id`() {
        val quote = transformProduct(
            ResponseProduct(quoteCurrencyId = "eur"),
        )

        assertEquals("EUR", quote?.currency?.from)
    }

    private fun assertClose(expected: Double, actual: Double) {
        assertTrue(kotlin.math.abs(expected - actual) < 1e-9, "expected <$expected> but was <$actual>")
    }
}
