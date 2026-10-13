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
}

class InMemorySessionStore : YahooSessionStore {
    private var session: YahooSession? = null

    override fun get(): YahooSession? = session

    override fun put(session: YahooSession) {
        this.session = session
    }
}
