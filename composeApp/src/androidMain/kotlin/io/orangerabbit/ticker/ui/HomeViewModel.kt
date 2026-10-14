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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// HomeViewModel drives the home screen: it observes the imported config and keeps the
// quote poller running for the selected group while the app is in the foreground, and
// writes add-symbol and lot edits back through the config repository. It owns the
// selected group index as the single source of truth shared by the group tabs, the
// group pager, and the poller. It lives in androidMain because ConfigRepository is an
// androidMain interface.
class HomeViewModel(
    private val poller: QuotePoller,
    private val configRepo: ConfigRepository,
) {

    val state: StateFlow<PollState> = poller.state

    // selectedGroup is the tab/pager selection and the poller's group; it survives
    // activity recreation (the view model is a singleton) and resets to the default
    // group at index 0 when the config changes.
    private val _selectedGroup = MutableStateFlow(0)
    val selectedGroup: StateFlow<Int> = _selectedGroup.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observation: Job? = null
    private var restart: Job? = null
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
                    _selectedGroup.value = 0
                }
                restart?.cancelAndJoin()
                restart = launch {
                    if (config == null) {
                        poller.stop()
                    } else {
                        try {
                            poller.start(config, _selectedGroup.value)
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
        if (_selectedGroup.value == index) return
        _selectedGroup.value = index
        scope.launch { poller.selectGroup(index) }
    }

    fun addSymbol(symbol: String) {
        val trimmed = symbol.trim()
        if (trimmed.isEmpty()) return
        editDefaultGroup { group ->
            if (trimmed in group.watchlist) group else group.copy(watchlist = group.watchlist + trimmed)
        }
    }

    // addLot always appends: a symbol can carry several cost-basis lots.
    fun addLot(lot: Lot) {
        editDefaultGroup { group -> group.copy(lots = group.lots + lot) }
    }

    // updateLot replaces only the lot at the given index in the default group.
    fun updateLot(index: Int, lot: Lot) {
        editDefaultGroup { group ->
            if (index in group.lots.indices) {
                group.copy(lots = group.lots.mapIndexed { i, current -> if (i == index) lot else current })
            } else {
                group
            }
        }
    }

    // deleteLot removes only the lot at the given index in the default group.
    fun deleteLot(index: Int) {
        editDefaultGroup { group ->
            if (index in group.lots.indices) {
                group.copy(lots = group.lots.filterIndexed { i, _ -> i != index })
            } else {
                group
            }
        }
    }

    // editDefaultGroup applies edits to the group shown at tab index 0: the promoted
    // top-level watchlist/lots ("default" group) when present, otherwise the first
    // stored group, or a new top-level default when the config has no groups at all.
    // Lot indices align with the sheet's view because the promotion preserves order.
    // No-op edits do not save, avoiding a needless poller restart.
    private fun editDefaultGroup(edit: (ConfigGroup) -> ConfigGroup) {
        val config = configRepo.config.value ?: return
        val promoted = config.watchlist.isNotEmpty() || config.lots.isNotEmpty()
        val updated = if (promoted || config.groups.isEmpty()) {
            val default = edit(ConfigGroup(name = "default", watchlist = config.watchlist, lots = config.lots))
            config.copy(watchlist = default.watchlist, lots = default.lots)
        } else {
            config.copy(groups = config.groups.mapIndexed { index, group -> if (index == 0) edit(group) else group })
        }
        if (updated == config) return
        scope.launch { configRepo.save(updated) }
    }
}
