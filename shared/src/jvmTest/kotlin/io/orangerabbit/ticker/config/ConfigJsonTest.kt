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
