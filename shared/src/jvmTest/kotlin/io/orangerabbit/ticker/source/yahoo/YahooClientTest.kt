package io.orangerabbit.ticker.source.yahoo

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.decodeURLQueryComponent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// PreloadingSessionStore models a persisted session that only becomes visible after a
// suspending load: get() before preload() completes sees nothing, like the DataStore
// mirror before its asynchronous read lands on a cold start.
private class PreloadingSessionStore(
    private val persisted: YahooSession,
) : YahooSessionStore {

    var loaded = false
    var getBeforePreloadCompleted = false
    private var written: YahooSession? = null

    override fun get(): YahooSession? {
        if (!loaded) {
            getBeforePreloadCompleted = true
            return written
        }
        return written ?: persisted
    }

    override fun put(session: YahooSession) {
        written = session
    }

    override suspend fun preload() {
        yield()
        loaded = true
    }
}

class YahooClientTest {

    private data class MockReply(
        val status: HttpStatusCode,
        val body: String = "",
        val headers: Headers = Headers.Empty,
    )

    private fun setCookieHeaders(vararg cookies: String): Headers = Headers.build {
        cookies.forEach { append(HttpHeaders.SetCookie, it) }
    }

    private fun mockClient(
        replies: List<MockReply>,
        recorded: MutableList<HttpRequestData>,
        followRedirects: Boolean = false,
        expectSuccess: Boolean = false,
    ): HttpClient {
        val queue = ArrayDeque(replies)
        return HttpClient(MockEngine { request ->
            recorded += request
            val reply = queue.removeFirst()
            respond(reply.body, reply.status, reply.headers)
        }) {
            this.followRedirects = followRedirects
            this.expectSuccess = expectSuccess
        }
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/yahoo/$name")) { "missing fixture $name" }.readText()

    private fun cookieHeader(request: HttpRequestData): String = request.headers[HttpHeaders.Cookie] ?: ""

    private fun formParams(request: HttpRequestData): Map<String, String> =
        (request.body as TextContent).text.split("&").associate {
            val (key, value) = it.split("=", limit = 2)
            key.decodeURLQueryComponent() to value.decodeURLQueryComponent()
        }

    @Test
    fun `first request establishes session`() = runTest {
        val recorded = mutableListOf<HttpRequestData>()
        val store = InMemorySessionStore()
        val replies = listOf(
            MockReply(HttpStatusCode.OK, headers = setCookieHeaders("A3=cookie-a3-value; Path=/; Domain=.yahoo.com", "F=d; Path=/")),
            MockReply(HttpStatusCode.OK, body = "foo.bar"),
            MockReply(HttpStatusCode.OK, body = fixture("quote-regular.json")),
        )
        val client = YahooClient(mockClient(replies, recorded), store)

        val result = client.getQuotes(listOf("AAPL", "BTC-USD"))

        val quotes = result.getOrThrow()
        assertEquals(2, quotes.size)
        assertEquals(3, recorded.size)
        assertEquals("https://finance.yahoo.com", recorded[0].url.toString())
        assertTrue(recorded[1].url.toString().contains("/v1/test/getcrumb"))
        val quoteRequest = recorded[2]
        assertTrue(quoteRequest.url.toString().contains("/v7/finance/quote"))
        assertTrue(quoteRequest.url.toString().contains("crumb=foo.bar"))
        assertTrue(cookieHeader(quoteRequest).contains("A3=cookie-a3-value"))
        val session = assertNotNull(store.get())
        assertEquals("foo.bar", session.crumb)
        assertTrue(session.cookies.contains("A3" to "cookie-a3-value"))
    }

    @Test
    fun `persisted session is awaited through preload before first use`() = runTest {
        val recorded = mutableListOf<HttpRequestData>()
        val store = PreloadingSessionStore(YahooSession(cookies = listOf("A3" to "seed-a3"), crumb = "seed.crumb"))
        val replies = listOf(
            MockReply(HttpStatusCode.OK, body = fixture("quote-regular.json")),
        )
        val client = YahooClient(mockClient(replies, recorded), store)

        val result = client.getQuotes(listOf("AAPL", "BTC-USD"))

        assertEquals(2, result.getOrThrow().size)
        assertFalse(store.getBeforePreloadCompleted, "get() must not run before the preload completes")
        assertEquals(1, recorded.size)
        val quoteRequest = recorded[0]
        assertTrue(quoteRequest.url.toString().contains("/v7/finance/quote"))
        assertTrue(quoteRequest.url.toString().contains("crumb=seed.crumb"))
        assertTrue(cookieHeader(quoteRequest).contains("A3=seed-a3"))
    }

    @Test
    fun `session reuse skips handshake`() = runTest {
        val recorded = mutableListOf<HttpRequestData>()
        val store = InMemorySessionStore()
        store.put(YahooSession(cookies = listOf("A3" to "seed-a3"), crumb = "seed.crumb"))
        val replies = listOf(
            MockReply(HttpStatusCode.OK, body = fixture("quote-regular.json")),
        )
        val client = YahooClient(mockClient(replies, recorded), store)

        val result = client.getQuotes(listOf("AAPL", "BTC-USD"))

        assertEquals(2, result.getOrThrow().size)
        assertEquals(1, recorded.size)
        val quoteRequest = recorded[0]
        assertTrue(quoteRequest.url.toString().contains("/v7/finance/quote"))
        assertTrue(quoteRequest.url.toString().contains("crumb=seed.crumb"))
        assertTrue(cookieHeader(quoteRequest).contains("A3=seed-a3"))
    }

    @Test
    fun `unauthorized refreshes and retries once`() = runTest {
        val recorded = mutableListOf<HttpRequestData>()
        val store = InMemorySessionStore()
        store.put(YahooSession(cookies = listOf("A3" to "stale-a3"), crumb = "stale.crumb"))
        val replies = listOf(
            MockReply(HttpStatusCode.Unauthorized),
            MockReply(HttpStatusCode.OK, headers = setCookieHeaders("A3=fresh-a3; Path=/")),
            MockReply(HttpStatusCode.OK, body = "fresh.crumb"),
            MockReply(HttpStatusCode.OK, body = fixture("quote-regular.json")),
        )
        val client = YahooClient(mockClient(replies, recorded), store)

        val result = client.getQuotes(listOf("AAPL", "BTC-USD"))

        assertEquals(2, result.getOrThrow().size)
        val quoteRequests = recorded.filter { it.url.encodedPath == "/v7/finance/quote" }
        assertEquals(2, quoteRequests.size)
        assertTrue(quoteRequests[1].url.toString().contains("crumb=fresh.crumb"))
        assertEquals("fresh.crumb", store.get()?.crumb)
    }

    @Test
    fun `retry exhaustion surfaces error`() = runTest {
        val recorded = mutableListOf<HttpRequestData>()
        val store = InMemorySessionStore()
        store.put(YahooSession(cookies = listOf("A3" to "stale-a3"), crumb = "stale.crumb"))
        val replies = listOf(
            MockReply(HttpStatusCode.Unauthorized),
            MockReply(HttpStatusCode.OK, headers = setCookieHeaders("A3=fresh-a3; Path=/")),
            MockReply(HttpStatusCode.OK, body = "fresh.crumb"),
            MockReply(HttpStatusCode.Unauthorized),
        )
        val client = YahooClient(mockClient(replies, recorded), store)

        val result = client.getQuotes(listOf("AAPL", "BTC-USD"))

        val message = assertNotNull(result.exceptionOrNull()).message.orEmpty()
        assertTrue(message.contains("401"), "expected status code in message but was <$message>")
        assertFalse(message.contains("crumb"), "token-bearing url leaked in <$message>")
        val quoteRequests = recorded.filter { it.url.encodedPath == "/v7/finance/quote" }
        assertEquals(2, quoteRequests.size)
    }

    @Test
    fun `missing A3 cookie fails`() = runTest {
        val recorded = mutableListOf<HttpRequestData>()
        val store = InMemorySessionStore()
        val replies = listOf(
            MockReply(HttpStatusCode.OK, headers = setCookieHeaders("F=d; Path=/")),
        )
        val client = YahooClient(mockClient(replies, recorded), store)

        val result = client.getQuotes(listOf("AAPL", "BTC-USD"))

        val error = assertNotNull(result.exceptionOrNull())
        assertTrue(error.message.orEmpty().contains("A3"), "expected A3 in message but was <${error.message}>")
        assertTrue(recorded.none { it.url.encodedPath == "/v7/finance/quote" }, "quote request must not be made")
    }

    @Test
    fun `EU consent flow`() = runTest {
        val recorded = mutableListOf<HttpRequestData>()
        val store = InMemorySessionStore()
        val consentLocation = "https://consent.yahoo.com/v2/collectConsent?sessionId=S&csrfToken=CSRF1&lang=en"
        val replies = listOf(
            MockReply(
                status = HttpStatusCode.SeeOther,
                headers = Headers.build { append(HttpHeaders.Location, consentLocation) },
            ),
            MockReply(
                status = HttpStatusCode.Found,
                headers = Headers.build {
                    append(HttpHeaders.Location, "https://consent.yahoo.com/v2/collectConsent?sessionId=S&agree=agree")
                    append(HttpHeaders.SetCookie, "GUCS=guces-token; Path=/; Domain=.yahoo.com")
                },
            ),
            MockReply(HttpStatusCode.OK, headers = setCookieHeaders("A3=eu-a3; Path=/; Domain=.yahoo.com")),
            MockReply(HttpStatusCode.OK, body = "foo.bar"),
            MockReply(HttpStatusCode.OK, body = fixture("quote-regular.json")),
        )
        // expectSuccess = true must not turn the consent 303 into a response exception
        val client = YahooClient(mockClient(replies, recorded, expectSuccess = true), store)

        val result = client.getQuotes(listOf("AAPL", "BTC-USD"))

        assertEquals(2, result.getOrThrow().size)
        assertEquals(5, recorded.size)

        assertEquals(HttpMethod.Get, recorded[1].method)
        assertEquals(consentLocation, recorded[1].url.toString())

        val consentPost = recorded[2]
        assertEquals(HttpMethod.Post, consentPost.method)
        assertEquals("https://consent.yahoo.com/v2/collectConsent?sessionId=S", consentPost.url.toString())
        assertEquals(
            mapOf(
                "csrfToken" to "CSRF1",
                "sessionId" to "S",
                "namespace" to "yahoo",
                "agree" to "agree",
            ),
            formParams(consentPost),
        )
        assertTrue(cookieHeader(consentPost).contains("GUCS=guces-token"))

        val quoteRequest = recorded[4]
        assertTrue(quoteRequest.url.toString().contains("crumb=foo.bar"))
        assertTrue(cookieHeader(quoteRequest).contains("A3=eu-a3"))
        assertEquals("foo.bar", store.get()?.crumb)
    }

    @Test
    fun `consent redirect to foreign host fails without requesting it`() = runTest {
        val recorded = mutableListOf<HttpRequestData>()
        val store = InMemorySessionStore()
        val replies = listOf(
            MockReply(
                status = HttpStatusCode.SeeOther,
                headers = Headers.build {
                    append(HttpHeaders.Location, "https://example.com/consent?sessionId=S&csrfToken=CSRF1")
                },
            ),
        )
        // even with an injected client that follows redirects, the redirect target
        // must be validated before it is requested
        val client = YahooClient(mockClient(replies, recorded, followRedirects = true), store)

        val result = client.getQuotes(listOf("AAPL"))

        val message = assertNotNull(result.exceptionOrNull()).message.orEmpty()
        assertTrue(message.contains("session refresh"), "expected session error but was <$message>")
        assertTrue(message.contains("consent redirect"), "expected consent location error but was <$message>")
        assertFalse(message.contains("example.com"), "redirect location leaked in <$message>")
        assertEquals(1, recorded.size)
        assertEquals("finance.yahoo.com", recorded[0].url.host)
    }

    @Test
    fun `unauthorized refreshes and retries once with expectSuccess client`() = runTest {
        val recorded = mutableListOf<HttpRequestData>()
        val store = InMemorySessionStore()
        store.put(YahooSession(cookies = listOf("A3" to "stale-a3"), crumb = "stale.crumb"))
        val replies = listOf(
            MockReply(HttpStatusCode.Unauthorized),
            MockReply(HttpStatusCode.OK, headers = setCookieHeaders("A3=fresh-a3; Path=/")),
            MockReply(HttpStatusCode.OK, body = "fresh.crumb"),
            MockReply(HttpStatusCode.OK, body = fixture("quote-regular.json")),
        )
        val client = YahooClient(mockClient(replies, recorded, expectSuccess = true), store)

        val result = client.getQuotes(listOf("AAPL", "BTC-USD"))

        assertEquals(2, result.getOrThrow().size)
        val quoteRequests = recorded.filter { it.url.encodedPath == "/v7/finance/quote" }
        assertEquals(2, quoteRequests.size)
        assertTrue(quoteRequests[1].url.toString().contains("crumb=fresh.crumb"))
    }

    @Test
    fun `transport failure messages do not leak request tokens`() = runTest {
        val store = InMemorySessionStore()
        store.put(YahooSession(cookies = listOf("A3" to "seed-a3"), crumb = "secret.crumb"))
        val http = HttpClient(MockEngine {
            throw IllegalStateException(
                "Client request(GET https://query1.finance.yahoo.com/v7/finance/quote?crumb=secret.crumb) failed",
            )
        })

        val result = YahooClient(http, store).getQuotes(listOf("AAPL"))

        val error = assertNotNull(result.exceptionOrNull())
        var current: Throwable? = error
        while (current != null) {
            val message = current.message.orEmpty()
            assertFalse(message.contains("secret"), "token leaked in <$message>")
            assertFalse(message.contains("crumb"), "token-bearing url leaked in <$message>")
            current = current.cause
        }
        assertTrue(error.message.orEmpty().contains("failed to make request"), "was <${error.message}>")
    }

    @Test
    fun `search returns symbol results with cookies and no crumb`() = runTest {
        val recorded = mutableListOf<HttpRequestData>()
        val store = InMemorySessionStore()
        store.put(YahooSession(cookies = listOf("A3" to "seed-a3"), crumb = "seed.crumb"))
        val replies = listOf(
            MockReply(HttpStatusCode.OK, body = fixture("search-apple.json")),
        )
        val client = YahooClient(mockClient(replies, recorded), store)

        val result = client.search("apple")

        val results = result.getOrThrow()
        assertEquals(2, results.size)
        assertEquals("AAPL", results[0].symbol)
        assertEquals("BTC-USD", results[1].symbol)
        val request = recorded.single()
        assertEquals("/v1/finance/search", request.url.encodedPath)
        assertEquals("apple", request.url.parameters["q"])
        assertEquals("10", request.url.parameters["quotesCount"])
        assertEquals("0", request.url.parameters["newsCount"])
        assertEquals("en-US", request.url.parameters["lang"])
        assertEquals("US", request.url.parameters["region"])
        assertTrue(cookieHeader(request).contains("A3=seed-a3"))
        assertNull(request.url.parameters["crumb"], "search must not send a crumb")
    }

    @Test
    fun `search establishes session when none is stored`() = runTest {
        val recorded = mutableListOf<HttpRequestData>()
        val store = InMemorySessionStore()
        val replies = listOf(
            MockReply(
                HttpStatusCode.OK,
                headers = setCookieHeaders("A3=cookie-a3-value; Path=/; Domain=.yahoo.com", "F=d; Path=/"),
            ),
            MockReply(HttpStatusCode.OK, body = "foo.bar"),
            MockReply(HttpStatusCode.OK, body = fixture("search-apple.json")),
        )
        val client = YahooClient(mockClient(replies, recorded), store)

        val result = client.search("apple")

        assertEquals(2, result.getOrThrow().size)
        assertEquals(3, recorded.size)
        assertTrue(recorded[1].url.toString().contains("/v1/test/getcrumb"))
        val request = recorded[2]
        assertEquals("/v1/finance/search", request.url.encodedPath)
        assertTrue(cookieHeader(request).contains("A3=cookie-a3-value"))
        assertNull(request.url.parameters["crumb"], "search must not send a crumb")
    }

    @Test
    fun `search awaits persisted session through preload`() = runTest {
        val recorded = mutableListOf<HttpRequestData>()
        val store = PreloadingSessionStore(YahooSession(cookies = listOf("A3" to "seed-a3"), crumb = "seed.crumb"))
        val replies = listOf(
            MockReply(HttpStatusCode.OK, body = fixture("search-apple.json")),
        )
        val client = YahooClient(mockClient(replies, recorded), store)

        val result = client.search("apple")

        assertEquals(2, result.getOrThrow().size)
        assertFalse(store.getBeforePreloadCompleted, "get() must not run before the preload completes")
        assertEquals(1, recorded.size)
        assertTrue(cookieHeader(recorded[0]).contains("A3=seed-a3"))
    }

    @Test
    fun `search rate limit failure is sanitized`() = runTest {
        val recorded = mutableListOf<HttpRequestData>()
        val store = InMemorySessionStore()
        store.put(YahooSession(cookies = listOf("A3" to "seed-a3"), crumb = "seed.crumb"))
        val replies = listOf(
            MockReply(HttpStatusCode.TooManyRequests, body = "Too Many Requests"),
        )
        val client = YahooClient(mockClient(replies, recorded), store)

        val result = client.search("apple")

        val message = assertNotNull(result.exceptionOrNull()).message.orEmpty()
        assertTrue(message.contains("429"), "expected status code in message but was <$message>")
        assertFalse(message.contains("Too Many Requests"), "response body leaked in <$message>")
        assertFalse(message.contains("A3=seed-a3"), "cookies leaked in <$message>")
        assertFalse(message.contains("query1.finance.yahoo.com"), "request url leaked in <$message>")
        assertEquals("/v1/finance/search", recorded.single().url.encodedPath)
    }
}
