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
import kotlinx.coroutines.CancellationException
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

    // start cancels any prior loop and any prior startup, resolves the group against the
    // ticker-static symbol map, refreshes immediately, then refreshes on the interval floor
    // of 30 seconds. Lifecycle ownership is registered before the first suspension: stop()
    // or a later start() cancels the registered job — including in-flight startup work —
    // and a superseded startup cannot install a loop because its scope is already cancelled.
    // Cancellation propagates both ways: startup cancelled by stop()/a later start() surfaces
    // as a cancelled start() result, and a cancelled caller cleans up the startup work.
    suspend fun start(config: TickerConfig, groupIndex: Int = 0) {
        stop()
        val cfg = config.withDefaultGroup()
        this.config = cfg

        val job = SupervisorJob()
        loopJob = job
        val scope = CoroutineScope(currentCoroutineContext() + job)

        val startup = scope.launch {
            symbolMap = symbols.fetchSymbolMap()
            refreshMutex.withLock { switchGroup(cfg, groupIndex) }
            refresh()
        }

        try {
            startup.join()
        } catch (e: CancellationException) {
            // this caller was cancelled while starting up: clean up the in-flight startup
            // work so it cannot publish state afterward
            job.cancel()
            throw e
        }

        scope.launch {
            while (true) {
                delay(max(cfg.interval, 30) * 1000L)
                refresh()
            }
        }

        if (!job.isActive) {
            // stop() or a superseding start() cancelled this startup as it completed:
            // report the cancelled result to the caller instead of a successful start
            throw CancellationException("quote poller startup was cancelled")
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
        val next = if (groups.isEmpty()) {
            null
        } else {
            resolveGroup(groups[groupIndex.coerceIn(0, groups.lastIndex)], symbolMap)
        }
        // restarting the same identities (config reload, resume from background, group
        // re-select) keeps the cached quotes and the emitted portfolio visible — stale
        // but honest — while they are only cleared when the reconciled identities
        // actually change
        val previous = resolved
        resolved = next
        if (next != null && sameIdentities(previous, next)) {
            _state.update { it.copy(group = next.group) }
            return
        }
        yahooQuotes = emptyList()
        coinbaseQuotes = emptyList()
        _state.update {
            it.copy(
                group = next?.group,
                assets = emptyList(),
                summary = null,
                unavailable = emptyList(),
                error = null,
            )
        }
    }

    // sameIdentities compares the quote-relevant identity of two resolutions: the
    // deduplicated source-native fetch sets plus every configured ticker identity
    // mapped to them, so a renamed/added/removed symbol still clears the caches.
    private fun sameIdentities(current: ResolvedGroup?, next: ResolvedGroup): Boolean =
        current != null &&
            current.yahooSymbols == next.yahooSymbols &&
            current.coinbaseProducts == next.coinbaseProducts &&
            current.unresolved == next.unresolved &&
            current.tickerByYahooSymbol == next.tickerByYahooSymbol &&
            current.tickerByCoinbaseProduct == next.tickerByCoinbaseProduct

    private suspend fun refresh() {
        refreshMutex.withLock {
            val cfg = config ?: return
            val resolved = resolved ?: return
            _state.update { it.copy(isLoading = true) }
            try {
                val errors = mutableListOf<String>()

                if (resolved.yahooSymbols.isNotEmpty()) {
                    yahoo.getQuotes(resolved.yahooSymbols).fold(
                        onSuccess = { yahooQuotes = it.withTickerIdentities(resolved.tickerByYahooSymbol) { quote -> quote.symbol } },
                        onFailure = { errors.add(yahooError) },
                    )
                }
                if (resolved.coinbaseProducts.isNotEmpty()) {
                    coinbase.getQuotes(resolved.coinbaseProducts).fold(
                        onSuccess = {
                            coinbaseQuotes =
                                it.withTickerIdentities(resolved.tickerByCoinbaseProduct) { quote -> quote.meta.symbolInSourceApi }
                        },
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
                        lastRefreshAt = clock(),
                    )
                }
            } finally {
                // resets loading even when the refresh is cancelled mid-request, keeping
                // the previous assets and propagating the cancellation to the caller
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    // withTickerIdentities restores the configured ticker symbols on each returned quote
    // via its source-qualified identity map so aggregation lookups (lots, order index)
    // match the user's config, keeping meta.symbolInSourceApi as the source API identity
    // (e.g. the Coinbase product id). A product that resolves to several configured
    // identities is reconciled to each of them — one quote copy per identity — so no
    // configured lot or watchlist entry is dropped. A quote can only ever be re-keyed by
    // its own source's map.
    private fun List<AssetQuote>.withTickerIdentities(
        tickerBySource: Map<String, List<String>>,
        sourceKey: (AssetQuote) -> String,
    ): List<AssetQuote> = flatMap { quote ->
        val tickers = tickerBySource[sourceKey(quote)].orEmpty()
        if (tickers.isEmpty()) {
            listOf(quote)
        } else {
            tickers.map { ticker -> if (ticker == quote.symbol) quote else quote.copy(symbol = ticker) }
        }
    }

    private suspend fun fetchRates(cfg: TickerConfig, quotes: List<AssetQuote>): CurrencyRates {
        val fromCurrencies = quotes.map { it.currency.from }.distinct()
        if (fromCurrencies.isEmpty()) {
            return CurrencyRates(emptyMap())
        }

        return yahoo.getCurrencyRates(fromCurrencies, cfg.currency ?: "USD").getOrElse { CurrencyRates(emptyMap()) }
    }

    // missingSymbols lists expected identities that got no quote in this refresh: every
    // configured ticker identity of an unanswered yahoo symbol or coinbase product (an
    // identity counts as present only when its source-native symbol returned a quote),
    // plus symbols that could not be resolved to a source at all.
    private fun missingSymbols(resolved: ResolvedGroup): List<String> {
        val presentYahoo = yahooQuotes.mapTo(HashSet()) { it.symbol }
        val presentCoinbase = coinbaseQuotes.mapTo(HashSet()) { it.meta.symbolInSourceApi }

        return resolved.tickerByYahooSymbol.filterKeys { it !in presentYahoo }.values.flatten() +
            resolved.tickerByCoinbaseProduct.filterKeys { it !in presentCoinbase }.values.flatten() +
            resolved.unresolved
    }

    companion object {
        // Poller-owned error strings: source-prefixed and free of exception or transport
        // messages, which can embed request details.
        private const val yahooError = "yahoo: failed to fetch quotes"
        private const val coinbaseError = "coinbase: failed to fetch quotes"
    }
}
