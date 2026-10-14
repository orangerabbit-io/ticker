package io.orangerabbit.ticker.ui

import io.orangerabbit.ticker.ConfigRepository
import io.orangerabbit.ticker.config.ConfigGroup
import io.orangerabbit.ticker.config.Lot
import io.orangerabbit.ticker.config.TickerConfig
import io.orangerabbit.ticker.poll.PollState
import io.orangerabbit.ticker.poll.QuotePoller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// HomeViewModel drives the home screen: it observes the imported config and keeps the
// quote poller running for the selected group while the app is in the foreground, and
// writes add-symbol and lot edits back through the config repository. It lives in
// androidMain because ConfigRepository is an androidMain interface.
class HomeViewModel(
    private val poller: QuotePoller,
    private val configRepo: ConfigRepository,
) {

    val state: StateFlow<PollState> = poller.state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observation: Job? = null
    private var restart: Job? = null
    private var groupIndex: Int = 0
    private var lastConfig: TickerConfig? = null

    // start begins config observation and is idempotent, so the composition-level start
    // and the activity's ON_START can both call it. Each config change cancels the
    // in-flight poller startup and restarts the poller against the new config; a
    // superseded startup surfaces as CancellationException from poller.start and is not
    // an error to display. A null config stops the poller (import empty state).
    fun start() {
        if (observation?.isActive == true) return
        observation = scope.launch {
            configRepo.config.collect { config ->
                if (config != lastConfig) {
                    // a genuinely changed config resets the selection to the default
                    // group at tab index 0 (matching the screen's tab state)
                    lastConfig = config
                    groupIndex = 0
                }
                restart?.cancelAndJoin()
                restart = launch {
                    if (config == null) {
                        poller.stop()
                    } else {
                        try {
                            poller.start(config, groupIndex)
                        } catch (e: CancellationException) {
                            // startup superseded by another config change or stop()
                        }
                    }
                }
            }
        }
    }

    // stop releases the poller while the app is in the background; the last emitted
    // poll state is retained for the next start.
    fun stop() {
        observation?.cancel()
        observation = null
        restart?.cancel()
        restart = null
        poller.stop()
    }

    fun refresh() {
        scope.launch { poller.refreshNow() }
    }

    fun selectGroup(index: Int) {
        groupIndex = index
        scope.launch { poller.selectGroup(index) }
    }

    fun addSymbol(symbol: String) {
        val trimmed = symbol.trim()
        if (trimmed.isEmpty()) return
        editDefaultGroup { group ->
            if (trimmed in group.watchlist) group else group.copy(watchlist = group.watchlist + trimmed)
        }
    }

    fun saveLot(lot: Lot) {
        editDefaultGroup { group ->
            val existing = group.lots.indexOfFirst { it.symbol == lot.symbol }
            if (existing >= 0) {
                group.copy(lots = group.lots.mapIndexed { index, current -> if (index == existing) lot else current })
            } else {
                group.copy(lots = group.lots + lot)
            }
        }
    }

    fun deleteLot(symbol: String) {
        editDefaultGroup { group -> group.copy(lots = group.lots.filterNot { it.symbol == symbol }) }
    }

    // editDefaultGroup applies edits to the group shown at tab index 0: the promoted
    // top-level watchlist/lots ("default" group) when present, otherwise the first
    // stored group, or a new top-level default when the config has no groups at all.
    private fun editDefaultGroup(edit: (ConfigGroup) -> ConfigGroup) {
        val config = configRepo.config.value ?: return
        val promoted = config.watchlist.isNotEmpty() || config.lots.isNotEmpty()
        val updated = if (promoted || config.groups.isEmpty()) {
            val default = edit(ConfigGroup(name = "default", watchlist = config.watchlist, lots = config.lots))
            config.copy(watchlist = default.watchlist, lots = default.lots)
        } else {
            config.copy(groups = config.groups.mapIndexed { index, group -> if (index == 0) edit(group) else group })
        }
        scope.launch { configRepo.save(updated) }
    }
}
