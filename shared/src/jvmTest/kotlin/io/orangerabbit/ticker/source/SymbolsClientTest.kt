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

private class FakeSymbolMapCache(
    var snapshot: CachedSymbolMap? = null,
) : SymbolMapCache {
    val saves = mutableListOf<CachedSymbolMap>()

    override suspend fun load(): CachedSymbolMap? = snapshot

    override suspend fun save(cached: CachedSymbolMap) {
        saves.add(cached)
        snapshot = cached
    }
}

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

    @Test
    fun `persisted snapshot is reused within the 24h ttl across clients`() = runTest {
        var requests = 0
        val cache = FakeSymbolMapCache()
        val first = SymbolsClient(
            HttpClient(MockEngine { requests++; respond("A,A,cb\n") }),
            cache,
            clock = { testScheduler.currentTime },
        )

        assertEquals(setOf("A"), first.fetchSymbolMap().keys)
        assertEquals(1, requests)
        assertEquals("A,A,cb\n", cache.saves.single().csv)

        // a fresh process (new client, new in-memory cache) must serve the persisted
        // snapshot without refetching
        val second = SymbolsClient(
            HttpClient(MockEngine { requests++; respond("B,B,cb\n") }),
            cache,
            clock = { testScheduler.currentTime + 60 * 60 * 1000L },
        )

        assertEquals(setOf("A"), second.fetchSymbolMap().keys)
        assertEquals(1, requests)
    }

    @Test
    fun `persisted snapshot older than the 24h ttl is ignored`() = runTest {
        var requests = 0
        val cache = FakeSymbolMapCache(snapshot = CachedSymbolMap(csv = "A,A,cb\n", savedAt = 0L))
        val client = SymbolsClient(
            HttpClient(MockEngine { requests++; respond("B,B,cb\n") }),
            cache,
            clock = { 25 * 60 * 60 * 1000L },
        )

        assertEquals(setOf("B"), client.fetchSymbolMap().keys)
        assertEquals(1, requests)
    }

    @Test
    fun `cache failures do not break symbol resolution`() = runTest {
        val broken = object : SymbolMapCache {
            override suspend fun load(): CachedSymbolMap? = throw IOException("disk broken")
            override suspend fun save(cached: CachedSymbolMap): Unit = throw IOException("disk broken")
        }
        val client = SymbolsClient(
            HttpClient(MockEngine { respond("A,A,cb\n") }),
            broken,
            clock = { 0L },
        )

        assertEquals(setOf("A"), client.fetchSymbolMap().keys)
    }
}
