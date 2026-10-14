package io.orangerabbit.ticker.asset

import kotlin.test.Test
import kotlin.test.assertEquals

class SorterTest {

    private fun asset(
        symbol: String,
        value: Double = 0.0,
        changePercent: Double = 0.0,
        isActive: Boolean = true,
        orderIndex: Int = 0,
    ): Asset = Asset(
        name = symbol,
        symbol = symbol,
        assetClass = AssetClass.STOCK,
        currency = Currency(from = "USD", to = "USD"),
        position = Position(value = value),
        quotePrice = QuotePrice(changePercent = changePercent),
        quoteExtended = QuoteExtended(),
        quoteSource = QuoteSource.YAHOO,
        exchange = ExchangeInfo(isActive = isActive),
        meta = Meta(orderIndex = orderIndex),
    )

    private fun symbols(assets: List<Asset>): List<String> = assets.map { it.symbol }

    @Test
    fun `change sort puts inactive last`() {
        val a = asset("A", changePercent = 1.0)
        val b = asset("B", changePercent = 50.0, isActive = false)
        val c = asset("C", changePercent = 5.0)
        val d = asset("D", changePercent = 99.0, isActive = false)

        // actives sorted by changePercent desc; inactives sorted the same way, after them
        assertEquals(listOf("C", "A", "D", "B"), symbols(newSorter(null)(listOf(a, b, c, d))))
    }

    @Test
    fun `alpha sort`() {
        val sorted = newSorter("alpha")(
            listOf(
                asset("MSFT", value = 100.0),
                asset("AAPL", value = 300.0),
                asset("GOOG", value = 200.0),
            ),
        )

        assertEquals(listOf("AAPL", "GOOG", "MSFT"), symbols(sorted))
    }

    @Test
    fun `value sort desc`() {
        val sorted = newSorter("value")(
            listOf(
                asset("A", value = 100.0),
                asset("B", value = 300.0),
                asset("C", value = 200.0),
                asset("D", value = 500.0, isActive = false),
                asset("E", value = 999.0, isActive = false),
            ),
        )

        // actives by value desc; inactives sorted by value desc after them
        assertEquals(listOf("B", "C", "A", "E", "D"), symbols(sorted))
    }

    @Test
    fun `user sort by orderIndex`() {
        val sorted = newSorter("user")(
            listOf(
                asset("X", orderIndex = 2),
                asset("Y", orderIndex = 0),
                asset("Z", orderIndex = 1),
                asset("W", orderIndex = 1),
            ),
        )

        // ties keep input order (stable sort)
        assertEquals(listOf("Y", "Z", "W", "X"), symbols(sorted))
    }

    @Test
    fun `unknown sort string falls back to change sort`() {
        val a = asset("A", changePercent = 1.0)
        val b = asset("B", changePercent = 50.0, isActive = false)
        val c = asset("C", changePercent = 5.0)
        val d = asset("D", changePercent = 99.0, isActive = false)

        assertEquals(listOf("C", "A", "D", "B"), symbols(newSorter("bogus")(listOf(a, b, c, d))))
        assertEquals(listOf("C", "A", "D", "B"), symbols(newSorter("")(listOf(a, b, c, d))))
    }
}
