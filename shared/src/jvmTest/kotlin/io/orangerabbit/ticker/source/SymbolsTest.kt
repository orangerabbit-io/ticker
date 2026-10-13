package io.orangerabbit.ticker.source

import io.orangerabbit.ticker.asset.QuoteSource
import io.orangerabbit.ticker.config.ConfigGroup
import io.orangerabbit.ticker.config.Lot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SymbolsTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/symbols/$name")) { "missing fixture $name" }.readText()

    private fun symbolMap(): TickerSymbolMap = parseSymbolsCsv(fixture("symbols.csv"))

    @Test
    fun `parses symbols csv keeping first duplicate row`() {
        val map = symbolMap()

        assertEquals(
            SymbolSourceMap("ACH.X", "ACH-USD", QuoteSource.COINBASE),
            map["ACH.X"],
        )
        assertEquals(
            SymbolSourceMap("10SET.X", "", QuoteSource.UNKNOWN),
            map["10SET.X"],
        )
        assertEquals(
            SymbolSourceMap("DUP.X", "FIRST-USD", QuoteSource.COINBASE),
            map["DUP.X"],
        )
        assertEquals(6, map.size)
    }

    @Test
    fun `parses quoted commas and stray quotes without failing`() {
        val map = symbolMap()

        assertEquals("QTE-USD,EXTRA", map["QTE.X"]?.sourceSymbol)
        assertEquals("AB\"CD", map["STRAY.X"]?.sourceSymbol)
    }

    @Test
    fun `empty csv body parses to empty map`() {
        assertEquals(emptyMap(), parseSymbolsCsv(""))
    }

    @Test
    fun `routes plain symbols to yahoo`() {
        val resolved = resolveGroup(
            ConfigGroup(name = "g", watchlist = listOf("AAPL", "BTC-USD")),
            symbolMap(),
        )

        assertEquals(listOf("AAPL", "BTC-USD"), resolved.yahooSymbols)
        assertEquals(emptyList(), resolved.coinbaseProducts)
        assertEquals(emptyList(), resolved.unresolved)
    }

    @Test
    fun `routes CB suffix to coinbase product with suffix stripped`() {
        val resolved = resolveGroup(
            ConfigGroup(name = "g", watchlist = listOf("BTC.CB")),
            symbolMap(),
        )

        assertEquals(listOf("BTC"), resolved.coinbaseProducts)
        assertEquals(emptyList(), resolved.yahooSymbols)
        assertEquals(emptyList(), resolved.unresolved)
    }

    @Test
    fun `routes X suffix via symbol map to coinbase product`() {
        val resolved = resolveGroup(
            ConfigGroup(name = "g", watchlist = listOf("ACH.X")),
            symbolMap(),
        )

        assertEquals(listOf("ACH-USD"), resolved.coinbaseProducts)
        assertEquals(emptyList(), resolved.yahooSymbols)
        assertEquals(emptyList(), resolved.unresolved)
    }

    @Test
    fun `routes X suffix with unknown source or missing key to unresolved`() {
        // ach.X pins exact case-sensitive map lookup: the map key is ACH.X and must not match.
        val resolved = resolveGroup(
            ConfigGroup(name = "g", watchlist = listOf("10SET.X", "NOPE.X", "ach.X")),
            symbolMap(),
        )

        assertEquals(listOf("10SET.X", "NOPE.X", "ach.X"), resolved.unresolved)
        assertEquals(emptyList(), resolved.yahooSymbols)
        assertEquals(emptyList(), resolved.coinbaseProducts)
    }

    @Test
    fun `implicit lot symbol is added once`() {
        val resolved = resolveGroup(
            ConfigGroup(
                name = "g",
                watchlist = listOf("ABC", "DEF"),
                lots = listOf(Lot(symbol = "ABC")),
            ),
            symbolMap(),
        )

        assertEquals(listOf("ABC", "DEF"), resolved.group.watchlist)
    }

    @Test
    fun `implicit lot symbols append after watchlist in lot order and buckets keep watchlist order`() {
        val resolved = resolveGroup(
            ConfigGroup(
                name = "g",
                watchlist = listOf("AAPL", "ACH.X", "10SET.X"),
                lots = listOf(Lot(symbol = "MSFT"), Lot(symbol = "GOOG")),
            ),
            symbolMap(),
        )

        assertEquals(listOf("AAPL", "ACH.X", "10SET.X", "MSFT", "GOOG"), resolved.group.watchlist)
        assertEquals(listOf("AAPL", "MSFT", "GOOG"), resolved.yahooSymbols)
        assertEquals(listOf("ACH-USD"), resolved.coinbaseProducts)
        assertEquals(listOf("10SET.X"), resolved.unresolved)
    }

    @Test
    fun `implicit add dedupe is case-insensitive and first lot form wins`() {
        val resolved = resolveGroup(
            ConfigGroup(
                name = "g",
                watchlist = listOf("ABC", "DEF"),
                lots = listOf(Lot(symbol = "Xyz"), Lot(symbol = "XYZ"), Lot(symbol = "Def"), Lot(symbol = "ABC")),
            ),
            symbolMap(),
        )

        assertEquals(listOf("ABC", "DEF", "Xyz"), resolved.group.watchlist)
        assertEquals(listOf("ABC", "DEF", "Xyz"), resolved.yahooSymbols)
    }

    @Test
    fun `resolve group keeps name and lots`() {
        val group = ConfigGroup(
            name = "crypto",
            watchlist = listOf("AAPL"),
            lots = listOf(Lot(symbol = "MSFT", quantity = 2.0)),
        )
        val resolved = resolveGroup(group, symbolMap())

        assertEquals("crypto", resolved.group.name)
        assertEquals(group.lots, resolved.group.lots)
        assertTrue(resolved.group !== group)
    }
}