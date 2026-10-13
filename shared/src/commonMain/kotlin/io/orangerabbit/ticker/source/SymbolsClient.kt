package io.orangerabbit.ticker.source

import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock

// SymbolsClient fetches the ticker-static symbol map used to resolve ".X" shorthands. The
// parsed map is cached in memory for 24h; fetch failures fall back to an empty map and are
// not cached so a transient network error is retried on the next fetch.
class SymbolsClient(
    private val http: HttpClient,
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

        val map = try {
            val response = http.get(symbolsURL) { expectSuccess = false }
            if (response.status.value != HttpStatusCode.OK.value) {
                return emptyMap()
            }
            parseSymbolsCsv(response.bodyAsText())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return emptyMap()
        }

        cached = map
        cachedAt = now
        return map
    }

    companion object {
        private const val symbolsURL = "https://raw.githubusercontent.com/achannarasappa/ticker-static/master/symbols.csv"
        private const val ttlMillis = 24 * 60 * 60 * 1000L
    }
}
