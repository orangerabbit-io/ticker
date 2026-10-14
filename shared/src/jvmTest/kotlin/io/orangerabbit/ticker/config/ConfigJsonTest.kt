package io.orangerabbit.ticker.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigJsonTest {

    @Test
    fun `round trip preserves groups, lots, and every flag`() {
        val config = TickerConfig(
            interval = 30,
            watchlist = listOf("NET", "TEAM", "BTC-USD"),
            lots = listOf(
                Lot(symbol = "ABNB", unitCost = 146.0, quantity = 35.0, fixedCost = 0.0),
                Lot(symbol = "ARKW", unitCost = 145.35, quantity = 20.0, fixedCost = 7.0),
            ),
            groups = listOf(
                ConfigGroup(
                    name = "crypto",
                    watchlist = listOf("SHIB-USD"),
                    lots = listOf(Lot(symbol = "SOL1-USD", unitCost = 159.10, quantity = 17.0)),
                ),
                ConfigGroup(name = "empty"),
            ),
            showTags = true,
            showFundamentals = true,
            showSeparator = true,
            showSummary = true,
            showPositions = true,
            sort = "alpha",
            currency = "USD",
            currencySummaryOnly = true,
            currencyDisableUnitCostConversion = true,
        )

        assertEquals(config, parseTickerConfigJson(config.toJson()).getOrThrow())
    }

    @Test
    fun `round trip preserves default config with null optional fields`() {
        val config = TickerConfig()

        assertEquals(config, parseTickerConfigJson(config.toJson()).getOrThrow())
        assertEquals(null, parseTickerConfigJson(config.toJson()).getOrThrow().sort)
        assertEquals(null, parseTickerConfigJson(config.toJson()).getOrThrow().currency)
    }

    @Test
    fun `parse normalizes symbols to uppercase everywhere`() {
        val config = parseTickerConfigJson(
            """
            {
              "watchlist": ["btc-usd"],
              "lots": [{"symbol": "abnb", "quantity": 1.0}],
              "groups": [
                {"name": "default", "watchlist": ["eth-usd"]},
                {"name": "crypto", "watchlist": ["shib-usd"], "lots": [{"symbol": "sol1-usd", "quantity": 2.0}]}
              ]
            }
            """.trimIndent(),
        ).getOrThrow()

        assertEquals(listOf("BTC-USD", "ETH-USD"), config.watchlist)
        assertEquals(listOf(Lot(symbol = "ABNB", quantity = 1.0)), config.lots)
        assertEquals(
            listOf(
                ConfigGroup(
                    name = "crypto",
                    watchlist = listOf("SHIB-USD"),
                    lots = listOf(Lot(symbol = "SOL1-USD", quantity = 2.0)),
                ),
            ),
            config.groups,
        )
    }

    @Test
    fun `round trip re-exports normalized symbols`() {
        val config = parseTickerConfigJson(
            """{"watchlist": ["btc-usd"], "lots": [{"symbol": "abnb", "quantity": 1.0}]}""",
        ).getOrThrow()

        val json = config.toJson()
        assertTrue("BTC-USD" in json, "expected the uppercase watchlist symbol in the export but was: $json")
        assertTrue("ABNB" in json, "expected the uppercase lot symbol in the export but was: $json")
        assertTrue("btc-usd" !in json, "expected no lowercase symbol in the export but was: $json")
        assertEquals(config, parseTickerConfigJson(json).getOrThrow())
    }

    @Test
    fun `parse of empty string returns failure`() {
        assertTrue(parseTickerConfigJson("").isFailure)
    }

    @Test
    fun `semantic errors are rejected on the stored-config path too`() {
        assertTrue(parseTickerConfigJson("""{"interval":-1}""").isFailure)
        assertTrue(parseTickerConfigJson("""{"lots":[{"quantity":5.0}]}""").isFailure)
        assertTrue(parseTickerConfigJson("""{"groups":[{"name":"a"},{"name":"a"}]}""").isFailure)
    }
}
