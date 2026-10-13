package io.orangerabbit.ticker.source

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.orangerabbit.ticker.asset.QuoteSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SymbolsClientTest {

    @Test
    fun `fetch parses ticker-static csv into a symbol map`() = runTest {
        val client = SymbolsClient(
            HttpClient(MockEngine { respond("BTC.X,BTC-USD,cb\nFOO,FOO,xx\n") }),
            clock = { testScheduler.currentTime },
        )

        val map = client.fetchSymbolMap()

        assertEquals(
            mapOf(
                "BTC.X" to SymbolSourceMap("BTC.X", "BTC-USD", QuoteSource.COINBASE),
                "FOO" to SymbolSourceMap("FOO", "FOO", QuoteSource.UNKNOWN),
            ),
            map,
        )
    }

    @Test
    fun `fetch caches the symbol map within the 24h ttl`() = runTest {
        var requests = 0
        val client = SymbolsClient(
            HttpClient(MockEngine { requests++; respond("A,A,cb\n") }),
            clock = { testScheduler.currentTime },
        )

        client.fetchSymbolMap()
        advanceTimeBy(60 * 60 * 1000L)
        runCurrent()
        client.fetchSymbolMap()

        assertEquals(1, requests)
    }

    @Test
    fun `fetch refetches after the 24h ttl expires`() = runTest {
        var requests = 0
        val client = SymbolsClient(
            HttpClient(MockEngine { requests++; respond("A,A,cb\n") }),
            clock = { testScheduler.currentTime },
        )

        client.fetchSymbolMap()
        advanceTimeBy(25 * 60 * 60 * 1000L)
        runCurrent()
        client.fetchSymbolMap()

        assertEquals(2, requests)
    }

    @Test
    fun `http failure falls back to an empty map without caching`() = runTest {
        var requests = 0
        val client = SymbolsClient(
            HttpClient(MockEngine { requests++; respond("", HttpStatusCode.InternalServerError) }),
            clock = { testScheduler.currentTime },
        )

        assertEquals(emptyMap<String, SymbolSourceMap>(), client.fetchSymbolMap())
        assertEquals(emptyMap<String, SymbolSourceMap>(), client.fetchSymbolMap())

        assertEquals(2, requests)
    }

    @Test
    fun `transport failure falls back to an empty map`() = runTest {
        val client = SymbolsClient(
            HttpClient(MockEngine { throw IOException("network down") }),
            clock = { testScheduler.currentTime },
        )

        assertEquals(emptyMap<String, SymbolSourceMap>(), client.fetchSymbolMap())
    }
}
