package io.orangerabbit.ticker.config

import kotlin.test.Test
import kotlin.test.assertEquals

class SymbolNormalizationTest {

    @Test
    fun `normalizeSymbol trims and uppercases`() {
        assertEquals("BTC", normalizeSymbol(" btc "))
        assertEquals("BTC-USD", normalizeSymbol("btc-usd"))
    }

    @Test
    fun `withSymbolAdded normalizes and deduplicates case-insensitively`() {
        assertEquals(listOf("BTC"), listOf("BTC").withSymbolAdded(" btc "))
        assertEquals(listOf("ARKW"), listOf("ARKW").withSymbolAdded("arkw"))
        assertEquals(listOf("BTC"), emptyList<String>().withSymbolAdded("btc"))
    }

    @Test
    fun `normalized uppercases watchlist entries and lot symbols and preserves everything else`() {
        val config = TickerConfig(
            interval = 30,
            watchlist = listOf("btc-usd"),
            lots = listOf(Lot(symbol = "abnb", quantity = 1.0, unitCost = 2.0, fixedCost = 3.0)),
            groups = listOf(
                ConfigGroup(
                    name = "crypto",
                    watchlist = listOf("shib-usd"),
                    lots = listOf(Lot(symbol = "sol1-usd")),
                ),
            ),
            showTags = true,
            sort = "alpha",
            currency = "usd",
        )

        assertEquals(
            TickerConfig(
                interval = 30,
                watchlist = listOf("BTC-USD"),
                lots = listOf(Lot(symbol = "ABNB", quantity = 1.0, unitCost = 2.0, fixedCost = 3.0)),
                groups = listOf(
                    ConfigGroup(
                        name = "crypto",
                        watchlist = listOf("SHIB-USD"),
                        lots = listOf(Lot(symbol = "SOL1-USD")),
                    ),
                ),
                showTags = true,
                sort = "alpha",
                currency = "usd",
            ),
            config.normalized(),
        )
    }
}
