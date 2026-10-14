package io.orangerabbit.ticker.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YamlConfigTest {

    private val readmeExample = """
        show-summary: true
        show-tags: true
        show-fundamentals: true
        show-separator: true
        show-positions: true
        interval: 5
        currency: USD
        currency-summary-only: false
        watchlist:
          - NET
          - TEAM
          - ESTC
          - BTC-USD
          - SOL.X
          - BIT-30MAY25-CDE.CB
        lots:
          - symbol: "ABNB"
            quantity: 35.0
            unit_cost: 146.00
          - symbol: "ARKW"
            quantity: 20.0
            unit_cost: 152.25
          - symbol: "ARKW"
            quantity: 20.0
            unit_cost: 145.35
            fixed_cost: 7.00
        groups:
          - name: crypto
            watchlist:
              - SHIB-USD
              - VGX-USD
            lots:
              - symbol: SOL1-USD
                quantity: 17
                unit_cost: 159.10
    """.trimIndent()

    @Test
    fun `parses README example`() {
        val config = parseTickerConfig(readmeExample).getOrThrow()

        assertEquals(5, config.interval)
        assertEquals("USD", config.currency)
        assertNull(config.sort)
        assertEquals(false, config.currencySummaryOnly)
        assertEquals(false, config.currencyDisableUnitCostConversion)
        assertEquals(true, config.showSummary)
        assertEquals(true, config.showTags)
        assertEquals(true, config.showFundamentals)
        assertEquals(true, config.showSeparator)
        assertEquals(true, config.showPositions)
        assertEquals(
            listOf("NET", "TEAM", "ESTC", "BTC-USD", "SOL.X", "BIT-30MAY25-CDE.CB"),
            config.watchlist,
        )
        assertEquals(
            listOf(
                Lot(symbol = "ABNB", unitCost = 146.00, quantity = 35.0),
                Lot(symbol = "ARKW", unitCost = 152.25, quantity = 20.0),
                Lot(symbol = "ARKW", unitCost = 145.35, quantity = 20.0, fixedCost = 7.00),
            ),
            config.lots,
        )
        assertEquals(7.00, config.lots[2].fixedCost)
        assertEquals(
            listOf(
                ConfigGroup(
                    name = "crypto",
                    watchlist = listOf("SHIB-USD", "VGX-USD"),
                    lots = listOf(Lot(symbol = "SOL1-USD", unitCost = 159.10, quantity = 17.0)),
                ),
            ),
            config.groups,
        )
    }

    @Test
    fun `deprecated aliases map`() {
        val config = parseTickerConfig(
            """
            show-holdings: true
            holdings:
              - symbol: ABNB
                quantity: 35
                unit_cost: 146
            groups:
              - name: tech
                holdings:
                  - symbol: SOL1-USD
                    quantity: 17
                    unit_cost: 159.10
            """.trimIndent(),
        ).getOrThrow()

        assertEquals(true, config.showPositions)
        assertEquals(listOf(Lot(symbol = "ABNB", unitCost = 146.0, quantity = 35.0)), config.lots)
        assertEquals(
            listOf(
                ConfigGroup(
                    name = "tech",
                    lots = listOf(Lot(symbol = "SOL1-USD", unitCost = 159.10, quantity = 17.0)),
                ),
            ),
            config.groups,
        )
    }

    @Test
    fun `round trip preserves semantics`() {
        val config = parseTickerConfig(readmeExample).getOrThrow()
        val reparsed = parseTickerConfig(config.toYaml()).getOrThrow()

        assertEquals(config.withDefaultGroup(), reparsed.withDefaultGroup())
    }

    @Test
    fun `malformed yaml returns failure`() {
        val result = parseTickerConfig("watchlist: [unclosed")

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message
        assertTrue(!message.isNullOrBlank(), "expected a non-blank error message but was: $message")
    }

    @Test
    fun `negative quantity preserved`() {
        val config = parseTickerConfig(
            """
            lots:
              - symbol: ABNB
                quantity: -5
            """.trimIndent(),
        ).getOrThrow()

        assertEquals(-5.0, config.lots.single().quantity)
    }

    @Test
    fun `negative interval rejected with a clear message`() {
        val result = parseTickerConfig("interval: -1\n")

        assertTrue(result.isFailure)
        val message = assertNotNull(result.exceptionOrNull()?.message)
        assertTrue("interval" in message, "message should name the interval but was: $message")
    }

    @Test
    fun `lot without a symbol rejected with a clear message`() {
        val result = parseTickerConfig(
            """
            lots:
              - quantity: 5
            """.trimIndent(),
        )

        assertTrue(result.isFailure)
        val message = assertNotNull(result.exceptionOrNull()?.message)
        assertTrue("symbol" in message, "message should name the symbol but was: $message")
    }

    @Test
    fun `blank lot symbol rejected`() {
        val result = parseTickerConfig(
            """
            lots:
              - symbol: ""
                quantity: 5
            """.trimIndent(),
        )

        assertTrue(result.isFailure)
    }

    @Test
    fun `group lot with blank symbol rejected`() {
        val result = parseTickerConfig(
            """
            groups:
              - name: crypto
                lots:
                  - symbol: "   "
                    quantity: 5
            """.trimIndent(),
        )

        assertTrue(result.isFailure)
    }

    @Test
    fun `non-finite lot values rejected`() {
        // overflowed numeric literals land as non-finite doubles and non-finite YAML
        // scalars decode to them; neither may become a lot
        assertTrue(parseTickerConfig("lots:\n  - symbol: ABNB\n    quantity: 1e309\n").isFailure)
        assertTrue(parseTickerConfig("lots:\n  - symbol: ABNB\n    unit_cost: .inf\n").isFailure)
        assertTrue(parseTickerConfig("lots:\n  - symbol: ABNB\n    fixed_cost: .nan\n").isFailure)
    }

    @Test
    fun `zero interval accepted`() {
        val config = parseTickerConfig("interval: 0\n").getOrThrow()

        assertEquals(0, config.interval)
    }

    @Test
    fun `unknown keys are ignored`() {
        val config = parseTickerConfig(
            """
            colors:
              text: "#fff"
            cache: true
            debug: false
            watchlist:
              - NET
            """.trimIndent(),
        ).getOrThrow()

        assertEquals(listOf("NET"), config.watchlist)
    }

    @Test
    fun `withDefaultGroup promotes top-level entries`() {
        val config = parseTickerConfig(readmeExample).getOrThrow()
        val grouped = config.withDefaultGroup()

        assertEquals(
            listOf(
                ConfigGroup(name = "default", watchlist = config.watchlist, lots = config.lots),
                ConfigGroup(
                    name = "crypto",
                    watchlist = listOf("SHIB-USD", "VGX-USD"),
                    lots = listOf(Lot(symbol = "SOL1-USD", unitCost = 159.10, quantity = 17.0)),
                ),
            ),
            grouped.groups,
        )
        assertTrue(grouped.watchlist.isEmpty())
        assertTrue(grouped.lots.isEmpty())

        val groupsOnly = TickerConfig(groups = listOf(ConfigGroup(name = "crypto")))
        assertEquals(groupsOnly, groupsOnly.withDefaultGroup())
    }
}
