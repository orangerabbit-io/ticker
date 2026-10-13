package io.orangerabbit.ticker.poll

import io.orangerabbit.ticker.asset.Asset
import io.orangerabbit.ticker.asset.AssetQuote
import io.orangerabbit.ticker.asset.CurrencyRates
import io.orangerabbit.ticker.asset.PositionSummary
import io.orangerabbit.ticker.asset.getAssets
import io.orangerabbit.ticker.asset.newSorter
import io.orangerabbit.ticker.config.ConfigGroup
import io.orangerabbit.ticker.config.TickerConfig
import io.orangerabbit.ticker.config.withDefaultGroup
import io.orangerabbit.ticker.source.ResolvedGroup
import io.orangerabbit.ticker.source.SymbolsClient
import io.orangerabbit.ticker.source.TickerSymbolMap
import io.orangerabbit.ticker.source.coinbase.CoinbaseQuoteSource
import io.orangerabbit.ticker.source.resolveGroup
import io.orangerabbit.ticker.source.yahoo.YahooQuoteSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.max
import kotlin.time.Clock

data class PollState(
    val group: ConfigGroup? = null,
    val assets: List<Asset> = emptyList(),
    val summary: PositionSummary? = null,
    val unavailable: List<String> = emptyList(),
    val error: String? = null,
    val isLoading: Boolean = false,
    val lastRefreshAt: Long = 0,
)

// QuotePoller keeps a group's quotes fresh in the foreground: it resolves the group's
// symbols, fetches quotes from Yahoo and Coinbase, converts currencies with Yahoo rates,
// aggregates positions and emits sorted assets through PollState. The poll loop ticks at
// max(interval, 30) seconds; a source failure keeps the previous quotes for that source
// visible while surfacing a source-prefixed error string. Quotes are stale-but-visible on
// failure rather than cleared, so the last known data stays on screen with an error banner.
class QuotePoller(
    private val yahoo: YahooQuoteSource,
    private val coinbase: CoinbaseQuoteSource,
    private val symbols: SymbolsClient,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {

    private val _state = MutableStateFlow(PollState())
    val state: StateFlow<PollState> = _state.asStateFlow()

    private val refreshMutex = Mutex()

    private var loopJob: Job? = null
    private var config: TickerConfig? = null
    private var resolved: ResolvedGroup? = null
    private var symbolMap: TickerSymbolMap = emptyMap()
    private var yahooQuotes: List<AssetQuote> = emptyList()
    private var coinbaseQuotes: List<AssetQuote> = emptyList()

    // start cancels any prior loop, resolves the group against the ticker-static symbol
    // map, refreshes immediately, then refreshes on the interval floor of 30 seconds. The
    // loop runs on the caller's dispatcher but is owned by the poller: only stop or a new
    // start cancels it.
    suspend fun start(config: TickerConfig, groupIndex: Int = 0) {
        stop()
        val cfg = config.withDefaultGroup()
        this.config = cfg
        symbolMap = symbols.fetchSymbolMap()
        refreshMutex.withLock { switchGroup(cfg, groupIndex) }
        refresh()

        val job = SupervisorJob()
        loopJob = job
        CoroutineScope(currentCoroutineContext() + job).launch {
            while (true) {
                delay(max(cfg.interval, 30) * 1000L)
                refresh()
            }
        }
    }

    // selectGroup switches to another group and refreshes it immediately, keeping the
    // loop cadence of the running poll.
    suspend fun selectGroup(groupIndex: Int) {
        val cfg = config ?: return
        refreshMutex.withLock { switchGroup(cfg, groupIndex) }
        refresh()
    }

    suspend fun refreshNow() {
        refresh()
    }

    // stop cancels the poll loop; the last emitted state is retained for the UI.
    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    private fun switchGroup(cfg: TickerConfig, groupIndex: Int) {
        val groups = cfg.groups
        resolved = if (groups.isEmpty()) {
            null
        } else {
            resolveGroup(groups[groupIndex.coerceIn(0, groups.lastIndex)], symbolMap)
        }
        yahooQuotes = emptyList()
        coinbaseQuotes = emptyList()
        _state.update {
            it.copy(
                group = resolved?.group,
                assets = emptyList(),
                summary = null,
                unavailable = emptyList(),
                error = null,
            )
        }
    }

    private suspend fun refresh() {
        refreshMutex.withLock {
            val cfg = config ?: return
            val resolved = resolved ?: return
            _state.update { it.copy(isLoading = true) }

            val errors = mutableListOf<String>()

            if (resolved.yahooSymbols.isNotEmpty()) {
                yahoo.getQuotes(resolved.yahooSymbols).fold(
                    onSuccess = { yahooQuotes = it },
                    onFailure = { errors.add(yahooError) },
                )
            }
            if (resolved.coinbaseProducts.isNotEmpty()) {
                coinbase.getQuotes(resolved.coinbaseProducts).fold(
                    onSuccess = { coinbaseQuotes = it },
                    onFailure = { errors.add(coinbaseError) },
                )
            }

            val quotes = yahooQuotes + coinbaseQuotes
            val rates = fetchRates(cfg, quotes)

            val portfolio = getAssets(cfg, resolved.group, quotes, rates)
            val assets = newSorter(cfg.sort)(portfolio.assets)
            val unavailable = missingSymbols(resolved)

            _state.update {
                it.copy(
                    group = resolved.group,
                    assets = assets,
                    summary = portfolio.summary.takeIf { resolved.group.lots.isNotEmpty() },
                    unavailable = unavailable,
                    error = errors.joinToString("; ").ifEmpty { null },
                    isLoading = false,
                    lastRefreshAt = clock(),
                )
            }
        }
    }

    private suspend fun fetchRates(cfg: TickerConfig, quotes: List<AssetQuote>): CurrencyRates {
        val fromCurrencies = quotes.map { it.currency.from }.distinct()
        if (fromCurrencies.isEmpty()) {
            return CurrencyRates(emptyMap())
        }

        return yahoo.getCurrencyRates(fromCurrencies, cfg.currency ?: "USD").getOrElse { CurrencyRates(emptyMap()) }
    }

    // missingSymbols lists expected symbols that got no quote in this refresh: yahoo
    // symbols matched by exact symbol, coinbase products matched by the product id carried
    // in meta.symbolInSourceApi, plus symbols that could not be resolved to a source at all.
    private fun missingSymbols(resolved: ResolvedGroup): List<String> {
        val presentYahoo = yahooQuotes.mapTo(HashSet()) { it.symbol }
        val presentCoinbase = coinbaseQuotes.mapTo(HashSet()) { it.meta.symbolInSourceApi }

        return resolved.yahooSymbols.filter { it !in presentYahoo } +
            resolved.coinbaseProducts.filter { it !in presentCoinbase } +
            resolved.unresolved
    }

    companion object {
        // Poller-owned error strings: source-prefixed and free of exception or transport
        // messages, which can embed request details.
        private const val yahooError = "yahoo: failed to fetch quotes"
        private const val coinbaseError = "coinbase: failed to fetch quotes"
    }
}
