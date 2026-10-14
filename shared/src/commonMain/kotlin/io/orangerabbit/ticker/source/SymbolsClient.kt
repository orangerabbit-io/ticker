package io.orangerabbit.ticker.source

import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock

// CachedSymbolMap is a persisted ticker-static snapshot: the raw CSV body and the
// fetch timestamp in the same clock domain SymbolsClient uses, so TTL checks work
// across process restarts.
data class CachedSymbolMap(val csv: String, val savedAt: Long)

// SymbolMapCache persists a fetched symbol map snapshot across process restarts
// (DataStore-backed on Android). Load/save are best-effort: SymbolsClient treats a
// broken cache as absent.
interface SymbolMapCache {
    suspend fun load(): CachedSymbolMap?
    suspend fun save(cached: CachedSymbolMap)
}

// SymbolsClient fetches the ticker-static symbol map used to resolve ".X" shorthands. The
// parsed map is cached in memory for 24h and, when a SymbolMapCache is provided, persisted
// on fetch and reused across process restarts while fresh; fetch failures fall back to an
// empty map and are not cached so a transient network error is retried on the next fetch.
class SymbolsClient(
    private val http: HttpClient,
    private val cache: SymbolMapCache? = null,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {

    private var cached: TickerSymbolMap? = null
    private var cachedAt: Long = 0

    suspend fun fetchSymbolMap(): TickerSymbolMap {
        val now = clock()
        val cachedMap = cached
        if (cachedMap != null && now - cachedAt < ttlMillis) {
            return cachedMap
        }

        // a fresh persisted snapshot (possibly from a previous process) is reused
        // as-is; its savedAt anchors the memory TTL to the original fetch time
        val snapshot = loadSnapshot()
        if (snapshot != null && now - snapshot.savedAt < ttlMillis) {
            val map = parseSymbolsCsv(snapshot.csv)
            cached = map
            cachedAt = snapshot.savedAt
            return map
        }

        val body = try {
            val response = http.get(symbolsURL) { expectSuccess = false }
            if (response.status.value != HttpStatusCode.OK.value) {
                return emptyMap()
            }
            response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return emptyMap()
        }

        val map = parseSymbolsCsv(body)
        cached = map
        cachedAt = now
        saveSnapshot(CachedSymbolMap(csv = body, savedAt = now))
        return map
    }

    // persistence is best-effort: a broken cache must not break symbol resolution
    private suspend fun loadSnapshot(): CachedSymbolMap? =
        try {
            cache?.load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    private suspend fun saveSnapshot(snapshot: CachedSymbolMap) {
        try {
            cache?.save(snapshot)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // losing the snapshot only costs a refetch next time
        }
    }

    companion object {
        private const val symbolsURL = "https://raw.githubusercontent.com/achannarasappa/ticker-static/master/symbols.csv"
        private const val ttlMillis = 24 * 60 * 60 * 1000L
    }
}
