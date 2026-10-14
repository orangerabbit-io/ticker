package io.orangerabbit.ticker.source.yahoo

// YahooSession holds the transport-level authentication (cookies + crumb) shared
// by every Yahoo request, ported from the CLI's session cache.
data class YahooSession(
    val cookies: List<Pair<String, String>>,
    val crumb: String,
)

interface YahooSessionStore {
    fun get(): YahooSession?
    fun put(session: YahooSession)

    // preload suspends until any deferred initialization (e.g. an asynchronous load of
    // a persisted session) has completed so get() cannot miss it on cold start; stores
    // without deferred state do nothing. It must complete even when nothing is
    // persisted or the load fails.
    suspend fun preload() {}
}

class InMemorySessionStore : YahooSessionStore {
    private var session: YahooSession? = null

    override fun get(): YahooSession? = session

    override fun put(session: YahooSession) {
        this.session = session
    }
}
