package io.orangerabbit.ticker.asset

import io.orangerabbit.ticker.config.ConfigGroup
import io.orangerabbit.ticker.config.Lot
import io.orangerabbit.ticker.config.TickerConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AggregationTest {

    private val tolerance = 1e-9

    private fun quote(
        symbol: String,
        price: Double,
        change: Double = 0.0,
        changePercent: Double = 0.0,
        assetClass: AssetClass = AssetClass.STOCK,
        from: String = "USD",
        to: String = "USD",
    ): AssetQuote = AssetQuote(
        name = symbol,
        symbol = symbol,
        assetClass = assetClass,
        currency = Currency(from = from, to = to),
        quotePrice = QuotePrice(price = price, change = change, changePercent = changePercent),
        quoteExtended = QuoteExtended(),
        quoteSource = QuoteSource.YAHOO,
        exchange = ExchangeInfo(),
        meta = Meta(),
    )

    private fun assertNoNaN(portfolio: Portfolio) {
        val doubles = mutableListOf<Pair<String, Double>>()
        for (asset in portfolio.assets) {
            val p = asset.position
            doubles += listOf(
                "position.value" to p.value,
                "position.cost" to p.cost,
                "position.quantity" to p.quantity,
                "position.unitValue" to p.unitValue,
                "position.unitCost" to p.unitCost,
                "position.dayChange.amount" to p.dayChange.amount,
                "position.dayChange.percent" to p.dayChange.percent,
                "position.totalChange.amount" to p.totalChange.amount,
                "position.totalChange.percent" to p.totalChange.percent,
                "position.weight" to p.weight,
                "quotePrice.price" to asset.quotePrice.price,
                "quotePrice.change" to asset.quotePrice.change,
                "quotePrice.changePercent" to asset.quotePrice.changePercent,
                "quoteExtended.marketCap" to asset.quoteExtended.marketCap,
            )
        }
        doubles += listOf(
            "summary.value" to portfolio.summary.value,
            "summary.cost" to portfolio.summary.cost,
            "summary.totalChange.amount" to portfolio.summary.totalChange.amount,
            "summary.totalChange.percent" to portfolio.summary.totalChange.percent,
            "summary.dayChange.amount" to portfolio.summary.dayChange.amount,
            "summary.dayChange.percent" to portfolio.summary.dayChange.percent,
        )
        for ((label, value) in doubles) {
            assertTrue(!value.isNaN(), "$label is NaN")
        }
    }

    @Test
    fun `two lots aggregate and weight`() {
        val config = TickerConfig()
        val group = ConfigGroup(
            name = "default",
            lots = listOf(
                Lot(symbol = "ARKW", unitCost = 152.25, quantity = 20.0),
                Lot(symbol = "ARKW", unitCost = 145.35, quantity = 20.0),
            ),
        )
        val portfolio = getAssets(
            config,
            group,
            listOf(quote("ARKW", price = 160.00, change = 2.50, changePercent = 1.585)),
            CurrencyRates(emptyMap()),
        )
        val position = portfolio.assets.single().position

        assertEquals(40.0, position.quantity, tolerance)
        assertEquals(5952.00, position.cost, tolerance)
        assertEquals(6400.00, position.value, tolerance)
        assertEquals(448.00, position.totalChange.amount, tolerance)
        assertEquals(448.00 / 5952.00 * 100.0, position.totalChange.percent, tolerance)
        assertEquals(160.00, position.unitValue, tolerance)
        assertEquals(148.80, position.unitCost, tolerance)
        assertEquals(100.0, position.weight, tolerance)

        assertEquals(100.00, position.dayChange.amount, tolerance)
        assertEquals(1.585, position.dayChange.percent, tolerance)

        assertEquals(6400.00, portfolio.summary.value, tolerance)
        assertEquals(5952.00, portfolio.summary.cost, tolerance)
        assertEquals(448.00, portfolio.summary.totalChange.amount, tolerance)
        assertEquals(448.00 / 5952.00 * 100.0, portfolio.summary.totalChange.percent, tolerance)
    }

    @Test
    fun `fixed cost on first lot`() {
        val config = TickerConfig()
        val group = ConfigGroup(
            name = "default",
            lots = listOf(
                Lot(symbol = "TWLO", unitCost = 100.00, quantity = 10.0, fixedCost = 7.00),
                Lot(symbol = "TWLO", unitCost = 200.00, quantity = 5.0, fixedCost = 99.00),
            ),
        )
        val portfolio = getAssets(
            config,
            group,
            listOf(quote("TWLO", price = 150.00)),
            CurrencyRates(emptyMap()),
        )
        val position = portfolio.assets.single().position

        // first lot: (100 * 10) + 7 = 1007; later lot: + 5 * 200 = 1000 (its fixedCost is ignored)
        assertEquals(2007.00, position.cost, tolerance)
        assertEquals(15.0, position.quantity, tolerance)
        assertEquals(2250.00, position.value, tolerance)
    }

    @Test
    fun `negative quantity nets`() {
        val config = TickerConfig()
        val group = ConfigGroup(
            name = "default",
            lots = listOf(
                Lot(symbol = "ABNB", unitCost = 146.00, quantity = 35.0),
                Lot(symbol = "ABNB", unitCost = 150.00, quantity = -5.0),
            ),
        )
        val portfolio = getAssets(
            config,
            group,
            listOf(quote("ABNB", price = 160.00)),
            CurrencyRates(emptyMap()),
        )
        val position = portfolio.assets.single().position

        assertEquals(30.0, position.quantity, tolerance)
        assertEquals(146.00 * 35.0 + (-5.0 * 150.0), position.cost, tolerance)
        assertEquals(4360.00, position.cost, tolerance)
        assertEquals(4800.00, position.value, tolerance)
        assertEquals(160.00, position.unitValue, tolerance)
        assertEquals(4360.00 / 30.0, position.unitCost, tolerance)
        assertEquals(440.00, position.totalChange.amount, tolerance)
    }

    @Test
    fun `zero quantity no division`() {
        val config = TickerConfig()
        val group = ConfigGroup(
            name = "default",
            lots = listOf(Lot(symbol = "NET", unitCost = 150.00, quantity = 0.0)),
        )
        val portfolio = getAssets(
            config,
            group,
            listOf(quote("NET", price = 160.00, change = 2.50, changePercent = 1.585)),
            CurrencyRates(emptyMap()),
        )
        val position = portfolio.assets.single().position

        assertEquals(0.0, position.unitValue, tolerance)
        assertEquals(0.0, position.unitCost, tolerance)
        assertEquals(0.0, position.totalChange.amount, tolerance)
        assertEquals(0.0, position.totalChange.percent, tolerance)
        assertNoNaN(portfolio)
    }

    @Test
    fun `weights sum to 100`() {
        val config = TickerConfig()
        val group = ConfigGroup(
            name = "default",
            lots = listOf(
                Lot(symbol = "AAPL", unitCost = 90.00, quantity = 1.0),
                Lot(symbol = "MSFT", unitCost = 180.00, quantity = 2.0),
            ),
        )
        val portfolio = getAssets(
            config,
            group,
            listOf(
                quote("AAPL", price = 100.00),
                quote("MSFT", price = 200.00),
                quote("GOOG", price = 300.00),
            ),
            CurrencyRates(emptyMap()),
        )
        val weights = portfolio.assets.associate { it.symbol to it.position.weight }

        assertEquals(20.0, weights.getValue("AAPL"), tolerance)
        assertEquals(80.0, weights.getValue("MSFT"), tolerance)
        assertEquals(0.0, weights.getValue("GOOG"), tolerance)
        assertEquals(100.0, weights.values.sum(), tolerance)
    }

    @Test
    fun `currency-summary-only converts summary only`() {
        val config = TickerConfig(currency = "USD", currencySummaryOnly = true)
        val group = ConfigGroup(
            name = "default",
            lots = listOf(Lot(symbol = "SHEL", unitCost = 50.00, quantity = 10.0)),
        )
        val rates = CurrencyRates(mapOf("GBP" to CurrencyRate(from = "GBP", to = "USD", rate = 1.25)))
        val portfolio = getAssets(
            config,
            group,
            listOf(quote("SHEL", price = 100.00, from = "GBP", to = "USD")),
            rates,
        )
        val asset = portfolio.assets.single()

        // quote price stays in pence; only the summary is converted
        assertEquals(100.00, asset.quotePrice.price, tolerance)
        assertEquals(1000.00, asset.position.value, tolerance)
        assertEquals(500.00, asset.position.cost, tolerance)
        assertEquals("GBP", asset.currency.to)
        assertEquals(1250.00, portfolio.summary.value, tolerance)
        assertEquals(625.00, portfolio.summary.cost, tolerance)
    }

    @Test
    fun `currency-disable-unit-cost-conversion keeps cost unconverted`() {
        val config = TickerConfig(currency = "USD", currencyDisableUnitCostConversion = true)
        val group = ConfigGroup(
            name = "default",
            lots = listOf(Lot(symbol = "SHEL", unitCost = 50.00, quantity = 10.0)),
        )
        val rates = CurrencyRates(mapOf("GBP" to CurrencyRate(from = "GBP", to = "USD", rate = 1.25)))
        val portfolio = getAssets(
            config,
            group,
            listOf(quote("SHEL", price = 100.00, from = "GBP", to = "USD")),
            rates,
        )
        val asset = portfolio.assets.single()

        // value converts at 1.25 while cost stays in GBP
        assertEquals(1250.00, asset.position.value, tolerance)
        assertEquals(500.00, asset.position.cost, tolerance)
        assertEquals(125.00, asset.quotePrice.price, tolerance)
        assertEquals(125.00, asset.position.unitValue, tolerance)
        assertEquals(50.00, asset.position.unitCost, tolerance)
    }

    @Test
    fun `currency class skips conversion`() {
        val config = TickerConfig(currency = "USD")
        val group = ConfigGroup(
            name = "default",
            lots = listOf(Lot(symbol = "KRW=X", unitCost = 1200.00, quantity = 10.0)),
        )
        val rates = CurrencyRates(mapOf("KRW" to CurrencyRate(from = "KRW", to = "USD", rate = 0.00075)))
        val portfolio = getAssets(
            config,
            group,
            listOf(quote("KRW=X", price = 1300.00, assetClass = AssetClass.CURRENCY, from = "KRW", to = "USD")),
            rates,
        )
        val asset = portfolio.assets.single()

        assertEquals(1300.00, asset.quotePrice.price, tolerance)
        assertEquals(13000.00, asset.position.value, tolerance)
        assertEquals(12000.00, asset.position.cost, tolerance)
        assertEquals(13000.00, portfolio.summary.value, tolerance)
        assertEquals(12000.00, portfolio.summary.cost, tolerance)
        assertEquals("KRW", asset.currency.to)
    }
}
