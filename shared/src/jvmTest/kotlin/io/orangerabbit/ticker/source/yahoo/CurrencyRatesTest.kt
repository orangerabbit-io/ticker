package io.orangerabbit.ticker.source.yahoo

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.orangerabbit.ticker.asset.CurrencyRate
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CurrencyRatesTest {

    private data class MockReply(
        val status: HttpStatusCode,
        val body: String = "",
    )

    private fun mockClient(
        replies: List<MockReply>,
        recorded: MutableList<HttpRequestData>,
    ): HttpClient {
        val queue = ArrayDeque(replies)
        return HttpClient(MockEngine { request ->
            recorded += request
            val reply = queue.removeFirst()
            respond(reply.body, reply.status)
        })
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/yahoo/$name")) { "missing fixture $name" }.readText()

    private fun seededStore(): InMemorySessionStore =
        InMemorySessionStore().apply {
            put(YahooSession(cookies = listOf("A3" to "seed-a3"), crumb = "seed.crumb"))
        }

    private fun quoteRequest(recorded: List<HttpRequestData>): HttpRequestData {
        val requests = recorded.filter { it.url.encodedPath == "/v7/finance/quote" }
        assertEquals(1, requests.size, "expected exactly one quote request but was ${requests.size}")
        return requests.single()
    }

    @Test
    fun `derives rates and scaled minor unit rates from currency pair quotes`() = runTest {
        val recorded = mutableListOf<HttpRequestData>()
        val replies = listOf(MockReply(HttpStatusCode.OK, body = fixture("currency-rates.json")))
        val client = YahooClient(mockClient(replies, recorded), seededStore())

        val result = client.getCurrencyRates(listOf("EUR", "GBP"), "USD")

        val rates = result.getOrThrow().rates
        assertEquals(setOf("EUR", "EUr", "GBP", "GBp"), rates.keys)
        assertEquals(CurrencyRate(from = "EUR", to = "USD", rate = 1.10), rates.getValue("EUR"))
        assertEquals(CurrencyRate(from = "GBP", to = "USD", rate = 1.25), rates.getValue("GBP"))
        assertEquals("EUr", rates.getValue("EUr").from)
        assertEquals("USD", rates.getValue("EUr").to)
        assertEquals(0.011, rates.getValue("EUr").rate, 1e-9)
        assertEquals("GBp", rates.getValue("GBp").from)
        assertEquals("USD", rates.getValue("GBp").to)
        assertEquals(0.0125, rates.getValue("GBp").rate, 1e-9)

        val request = quoteRequest(recorded)
        assertEquals("EURUSD=X,GBPUSD=X", request.url.parameters["symbols"])
        assertEquals("currency,regularMarketPrice", request.url.parameters["fields"])
    }

    @Test
    fun `omits the target currency from rates`() = runTest {
        val recorded = mutableListOf<HttpRequestData>()
        val replies = listOf(MockReply(HttpStatusCode.OK, body = fixture("currency-rates-eur.json")))
        val client = YahooClient(mockClient(replies, recorded), seededStore())

        val result = client.getCurrencyRates(listOf("EUR", "USD"), "USD")

        val rates = result.getOrThrow().rates
        assertFalse(rates.containsKey("USD"), "target currency must not be present in <$rates>")
        assertEquals(setOf("EUR", "EUr"), rates.keys)
        assertEquals("EURUSD=X", quoteRequest(recorded).url.parameters["symbols"])
    }

    @Test
    fun `adds no minor entry for currencies without a minor form`() = runTest {
        val recorded = mutableListOf<HttpRequestData>()
        val replies = listOf(MockReply(HttpStatusCode.OK, body = fixture("currency-rates-jpy.json")))
        val client = YahooClient(mockClient(replies, recorded), seededStore())

        val result = client.getCurrencyRates(listOf("JPY"), "USD")

        val rates = result.getOrThrow().rates
        assertEquals(setOf("JPY"), rates.keys)
        assertEquals(CurrencyRate(from = "JPY", to = "USD", rate = 150.0), rates.getValue("JPY"))
        assertFalse(rates.containsKey("JPy"), "JPY has no minor form but minor entry found in <$rates>")
    }

    @Test
    fun `skips empty and same-currency entries and dedupes pairs`() = runTest {
        val recorded = mutableListOf<HttpRequestData>()
        val replies = listOf(MockReply(HttpStatusCode.OK, body = fixture("currency-rates-eur.json")))
        val client = YahooClient(mockClient(replies, recorded), seededStore())

        val result = client.getCurrencyRates(listOf("", "USD", "eur", "EUR"), "USD")

        assertEquals(setOf("EUR", "EUr"), result.getOrThrow().rates.keys)
        assertEquals("EURUSD=X", quoteRequest(recorded).url.parameters["symbols"])
    }

    @Test
    fun `returns empty rates without a request when every entry is skipped`() = runTest {
        val recorded = mutableListOf<HttpRequestData>()
        val client = YahooClient(mockClient(emptyList(), recorded), seededStore())

        val result = client.getCurrencyRates(listOf("", "USD"), "USD")

        assertEquals(emptyMap(), result.getOrThrow().rates)
        assertEquals(0, recorded.size, "no request must be made but recorded $recorded")
    }

    @Test
    fun `defaults empty target currency to USD`() = runTest {
        val recorded = mutableListOf<HttpRequestData>()
        val replies = listOf(MockReply(HttpStatusCode.OK, body = fixture("currency-rates.json")))
        val client = YahooClient(mockClient(replies, recorded), seededStore())

        val result = client.getCurrencyRates(listOf("EUR", "GBP"), "")

        val rates = result.getOrThrow().rates
        assertTrue(rates.values.all { it.to == "USD" }, "expected all rates converted to USD but was <$rates>")
        assertEquals("EURUSD=X,GBPUSD=X", quoteRequest(recorded).url.parameters["symbols"])
    }

    @Test
    fun `transport failure messages do not leak request tokens`() = runTest {
        val http = HttpClient(MockEngine {
            throw IllegalStateException(
                "Client request(GET https://query1.finance.yahoo.com/v7/finance/quote?crumb=secret.crumb) failed",
            )
        })

        val result = YahooClient(http, seededStore()).getCurrencyRates(listOf("EUR"), "USD")

        val error = assertNotNull(result.exceptionOrNull())
        var current: Throwable? = error
        while (current != null) {
            val message = current.message.orEmpty()
            assertFalse(message.contains("secret"), "token leaked in <$message>")
            assertFalse(message.contains("crumb"), "token-bearing url leaked in <$message>")
            current = current.cause
        }
        assertTrue(error.message.orEmpty().contains("failed to get currency rates"), "was <${error.message}>")
    }
}