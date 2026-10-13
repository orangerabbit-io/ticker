package io.orangerabbit.ticker.source.coinbase

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CoinbaseClientTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/coinbase/$name")) { "missing fixture $name" }.readText()

    private fun failingBody(cause: Throwable): ByteReadChannel {
        val channel = ByteChannel()
        channel.cancel(cause)
        return channel
    }

    @Test
    fun `fetches market products for product ids`() = runTest {
        val recorded = mutableListOf<HttpRequestData>()
        val http = HttpClient(MockEngine { request ->
            recorded += request
            respond(fixture("products.json"), HttpStatusCode.OK)
        })
        val client = CoinbaseClient(http)

        val result = client.getQuotes(listOf("BTC-USD", "SOL-USD"))

        val quotes = result.getOrThrow()
        assertEquals(3, quotes.size)
        assertEquals("BTC.CB", quotes[0].symbol)
        assertEquals(1, recorded.size)
        val request = recorded[0]
        assertEquals(HttpMethod.Get, request.method)
        assertEquals("/api/v3/brokerage/market/products", request.url.encodedPath)
        assertEquals("api.coinbase.com", request.url.host)
        assertEquals(listOf("BTC-USD", "SOL-USD"), request.url.parameters.getAll("product_ids"))
    }

    @Test
    fun `empty product id list returns empty quotes without request`() = runTest {
        val http = HttpClient(MockEngine { error("request must not be made") })
        val client = CoinbaseClient(http)

        val result = client.getQuotes(emptyList())

        assertEquals(emptyList(), result.getOrThrow())
    }

    @Test
    fun `non-200 response fails with sanitized message`() = runTest {
        val http = HttpClient(MockEngine {
            respond("upstream boom", HttpStatusCode.BadGateway)
        })
        val client = CoinbaseClient(http)

        val result = client.getQuotes(listOf("BTC-USD"))

        val message = assertNotNull(result.exceptionOrNull()).message.orEmpty()
        assertTrue(message.contains("failed to get quotes"), "was <$message>")
        assertTrue(message.contains("502"), "expected status code in message but was <$message>")
        assertFalse(message.contains("upstream boom"), "response body leaked in <$message>")
    }

    @Test
    fun `transport failure message is sanitized`() = runTest {
        val http = HttpClient(MockEngine {
            throw IllegalStateException(
                "Client request(GET https://api.coinbase.com/api/v3/brokerage/market/products?product_ids=BTC-USD) failed",
            )
        })
        val client = CoinbaseClient(http)

        val result = client.getQuotes(listOf("BTC-USD"))

        val message = assertNotNull(result.exceptionOrNull()).message.orEmpty()
        assertTrue(message.contains("failed to make request"), "was <$message>")
        assertFalse(message.contains("api.coinbase.com"), "request url leaked in <$message>")
        assertFalse(message.contains("product_ids"), "request parameters leaked in <$message>")
    }

    @Test
    fun `cancellation during body read is rethrown`() = runTest {
        val http = HttpClient(MockEngine {
            respond(failingBody(CancellationException("cancelled while reading body")), HttpStatusCode.OK)
        })
        val client = CoinbaseClient(http)

        assertFailsWith<CancellationException> {
            client.getQuotes(listOf("BTC-USD"))
        }
    }

    @Test
    fun `body read failure message is sanitized`() = runTest {
        val http = HttpClient(MockEngine {
            respond(failingBody(IllegalStateException("connection reset by secret-peer")), HttpStatusCode.OK)
        })
        val client = CoinbaseClient(http)

        val result = client.getQuotes(listOf("BTC-USD"))

        val error = assertNotNull(result.exceptionOrNull())
        val message = error.message.orEmpty()
        assertTrue(message.contains("failed to get quotes"), "was <$message>")
        assertTrue(
            message.contains("failed to make request") || message.contains("failed to read response"),
            "expected fixed transport text but was <$message>",
        )
        assertFalse(message.contains("secret-peer"), "transport message leaked in <$message>")
        var cause = error.cause
        while (cause != null) {
            assertFalse(cause.message.orEmpty().contains("secret-peer"), "transport message leaked in cause <$cause>")
            cause = cause.cause
        }
    }
}
