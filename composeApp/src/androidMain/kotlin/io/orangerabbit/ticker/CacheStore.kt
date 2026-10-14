package io.orangerabbit.ticker

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.orangerabbit.ticker.source.CachedSymbolMap
import io.orangerabbit.ticker.source.SymbolMapCache
import io.orangerabbit.ticker.source.yahoo.YahooSession
import io.orangerabbit.ticker.source.yahoo.YahooSessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

// Single app-wide Preferences DataStore for cached network state: the Yahoo handshake
// session and the ticker-static symbol map. Both survive process death and entries
// older than 24h are ignored, mirroring the CLI's startup cache.
val Context.cacheDataStore: DataStore<Preferences> by preferencesDataStore(name = "cache")

private val symbolsCsvKey = stringPreferencesKey("symbols_csv")
private val symbolsSavedAtKey = longPreferencesKey("symbols_saved_at")
private val yahooSessionKey = stringPreferencesKey("yahoo_session_json")
private val yahooSessionSavedAtKey = longPreferencesKey("yahoo_session_saved_at")

private const val cacheTtlMillis = 24 * 60 * 60 * 1000L

// DataStoreSymbolMapCache persists the fetched ticker-static CSV with its fetch
// timestamp; TTL enforcement lives in SymbolsClient, which compares the timestamp
// against its own clock and ignores stale snapshots.
class DataStoreSymbolMapCache(private val context: Context) : SymbolMapCache {

    override suspend fun load(): CachedSymbolMap? {
        val prefs = context.cacheDataStore.data.first()
        val csv = prefs[symbolsCsvKey] ?: return null
        return CachedSymbolMap(csv = csv, savedAt = prefs[symbolsSavedAtKey] ?: 0L)
    }

    override suspend fun save(cached: CachedSymbolMap) {
        context.cacheDataStore.edit {
            it[symbolsCsvKey] = cached.csv
            it[symbolsSavedAtKey] = cached.savedAt
        }
    }
}

// DataStoreSessionStore persists the Yahoo handshake session (cookies + crumb) across
// process restarts. YahooSessionStore's get/put are synchronous by contract, so the
// store keeps an in-memory mirror: the persisted value is loaded asynchronously at
// construction, and put() updates the mirror and writes through to the DataStore.
// get() ignores entries older than 24h so a stale session is re-handshaken instead of
// reused. Persistence is best-effort: a broken cache only costs an extra handshake.
class DataStoreSessionStore(
    context: Context,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : YahooSessionStore {

    private val dataStore = context.cacheDataStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var session: YahooSession? = null
    private var savedAt = 0L
    private var written = false

    init {
        scope.launch {
            val prefs = try {
                dataStore.data.first()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch
            }
            synchronized(lock) {
                // a session written before the preload finished wins over the stored one
                if (!written) {
                    session = decodeSession(prefs[yahooSessionKey])
                    savedAt = prefs[yahooSessionSavedAtKey] ?: 0L
                }
            }
        }
    }

    override fun get(): YahooSession? {
        synchronized(lock) {
            val current = session ?: return null
            if (clock() - savedAt >= cacheTtlMillis) {
                session = null
                return null
            }
            return current
        }
    }

    override fun put(session: YahooSession) {
        val at = clock()
        synchronized(lock) {
            this.session = session
            savedAt = at
            written = true
        }
        scope.launch {
            try {
                dataStore.edit {
                    it[yahooSessionKey] = encodeSession(session)
                    it[yahooSessionSavedAtKey] = at
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // best-effort persistence: the in-memory session still serves this run
            }
        }
    }
}

private fun encodeSession(session: YahooSession): String =
    JSONObject()
        .put("crumb", session.crumb)
        .put("cookies", JSONArray(session.cookies.map { JSONArray(listOf(it.first, it.second)) }))
        .toString()

private fun decodeSession(encoded: String?): YahooSession? {
    if (encoded == null) return null
    return try {
        val json = JSONObject(encoded)
        val cookiesArray = json.getJSONArray("cookies")
        val cookies = (0 until cookiesArray.length()).map { index ->
            val pair = cookiesArray.getJSONArray(index)
            pair.getString(0) to pair.getString(1)
        }
        YahooSession(cookies = cookies, crumb = json.getString("crumb"))
    } catch (e: Exception) {
        null
    }
}
