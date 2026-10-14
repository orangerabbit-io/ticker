package io.orangerabbit.ticker.ui

import io.orangerabbit.ticker.ConfigRepository
import io.orangerabbit.ticker.config.ConfigGroup
import io.orangerabbit.ticker.config.Lot
import io.orangerabbit.ticker.config.TickerConfig
import io.orangerabbit.ticker.config.withDefaultGroup
import io.orangerabbit.ticker.poll.PollState
import io.orangerabbit.ticker.poll.QuotePoller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// HomeViewModel drives the home screen: it observes the imported config and keeps the
// quote poller running for the selected group while the app is in the foreground, and
// writes configuration edits (symbols, lots, groups, settings) back through the config
// repository. It owns the selected group index as the single source of truth shared by
// the group tabs, the group pager, and the poller. It lives in androidMain because
// ConfigRepository is an androidMain interface.
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

    // saveError surfaces non-cancellation config write failures (e.g. serialization
    // rejecting a value) through the UI instead of letting them escape the root scope
    // and crash the app.
    private val _saveError = MutableStateFlow<String?>(null)
    val saveError: StateFlow<String?> = _saveError.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observation: Job? = null
    private var restart: Job? = null
    private var foreground: CompletableJob? = null
    private var lastConfig: TickerConfig? = null

    // start begins config observation and is idempotent, so the composition-level start
    // and the activity's ON_START can both call it. Each config change cancels the
    // in-flight poller startup and restarts the poller against the new config; a
    // superseded startup surfaces as CancellationException from poller.start and is not
    // an error to display. A null config stops the poller (import empty state).
    fun start() {
        if (observation?.isActive == true) return
        foreground = SupervisorJob()
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

    // stop releases the poller and cancels every foreground-owned action job (manual
    // refresh, group selection — including work still queued behind another refresh)
    // while the app is in the background; the last emitted poll state is retained for
    // the next start. No manual quote work outlives ON_STOP.
    fun stop() {
        observation?.cancel()
        observation = null
        restart?.cancel()
        restart = null
        foreground?.cancel()
        foreground = null
        poller.stop()
    }

    fun refresh() {
        launchForeground { poller.refreshNow() }
    }

    fun selectGroup(index: Int) {
        if (_selectedGroup.value == index) return
        _selectedGroup.value = index
        launchForeground { poller.selectGroup(index) }
    }

    // launchForeground runs manual quote work as a child of the foreground-owned job so
    // stop() can cancel it (and anything it queued) at ON_STOP. With no foreground job
    // there is no lifecycle ownership, so the action is not started at all.
    private fun launchForeground(block: suspend CoroutineScope.() -> Unit) {
        val owner = foreground ?: return
        scope.launch(owner) { block() }
    }

    // addSymbol adds to the SELECTED group: a promoted top-level default group writes
    // back to the top-level watchlist, a named group to its own entry.
    fun addSymbol(symbol: String) {
        val trimmed = symbol.trim()
        if (trimmed.isEmpty()) return
        editSelectedGroup { group ->
            if (trimmed in group.watchlist) group else group.copy(watchlist = group.watchlist + trimmed)
        }
    }

    // removeSymbol removes a watchlist entry of the selected group (a lot-owned symbol
    // stays visible through the implicit watchlist until its lots are edited away).
    fun removeSymbol(symbol: String) {
        editSelectedGroup { group -> group.copy(watchlist = group.watchlist.filterNot { it == symbol }) }
    }

    // updateSettings applies the settings sheet's interval, sort mode, and display
    // flags as a single write.
    fun updateSettings(settings: SettingsUpdate) {
        if (settings.interval < 0) return
        mutate { config ->
            config.copy(
                interval = settings.interval,
                sort = settings.sort,
                showTags = settings.showTags,
                showFundamentals = settings.showFundamentals,
                showSeparator = settings.showSeparator,
                showSummary = settings.showSummary,
                showPositions = settings.showPositions,
            )
        }
    }

    fun addGroup(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        editGroups { groups -> groups + ConfigGroup(name = trimmed) }
    }

    fun renameGroup(index: Int, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        editGroups { groups ->
            if (index in groups.indices) {
                groups.mapIndexed { i, group -> if (i == index) group.copy(name = trimmed) else group }
            } else {
                groups
            }
        }
    }

    fun removeGroup(index: Int) {
        editGroups { groups -> groups.filterIndexed { i, _ -> i != index } }
    }

    // addLot always appends to the selected group: a symbol can carry several cost-basis
    // lots.
    fun addLot(lot: Lot) {
        editSelectedGroup { group -> group.copy(lots = group.lots + lot) }
    }

    // updateLot replaces only the lot at the given index in the selected group.
    fun updateLot(index: Int, lot: Lot) {
        editSelectedGroup { group ->
            if (index in group.lots.indices) {
                group.copy(lots = group.lots.mapIndexed { i, current -> if (i == index) lot else current })
            } else {
                group
            }
        }
    }

    // deleteLot removes only the lot at the given index in the selected group.
    fun deleteLot(index: Int) {
        editSelectedGroup { group ->
            if (index in group.lots.indices) {
                group.copy(lots = group.lots.filterIndexed { i, _ -> i != index })
            } else {
                group
            }
        }
    }

    // mutationMutex serializes read-modify-write config edits so rapid consecutive
    // actions compose: every mutation reads the CURRENT stored config inside the
    // critical section (read-after-write consistent with save) instead of a snapshot
    // captured at action time, which let a later save restore what an earlier one
    // removed.
    private val mutationMutex = Mutex()

    private fun mutate(edit: (TickerConfig) -> TickerConfig) {
        scope.launch {
            mutationMutex.withLock {
                val config = configRepo.current() ?: return@withLock
                val updated = edit(config)
                // no-op edits do not save, avoiding a needless poller restart
                if (updated != config) {
                    write(updated)
                }
            }
        }
    }

    // editSelectedGroup applies edits to the group shown at the selected tab: the
    // promoted top-level default group (written back to the top-level watchlist/lots)
    // when it is selected, the matching stored group otherwise, or a new top-level
    // default when the config has no groups at all. Lot and symbol indices align with
    // the sheets' view because the promotion preserves order.
    private fun editSelectedGroup(edit: (ConfigGroup) -> ConfigGroup) {
        mutate { config ->
            val promoted = config.watchlist.isNotEmpty() || config.lots.isNotEmpty()
            when {
                config.groups.isEmpty() -> withTopLevelDefault(config, edit)
                promoted && selectedGroupIndex() == 0 -> withTopLevelDefault(config, edit)
                else -> {
                    val stored = if (promoted) selectedGroupIndex() - 1 else selectedGroupIndex()
                    if (stored in config.groups.indices) {
                        config.copy(groups = config.groups.mapIndexed { i, group -> if (i == stored) edit(group) else group })
                    } else {
                        config
                    }
                }
            }
        }
    }

    private fun withTopLevelDefault(config: TickerConfig, edit: (ConfigGroup) -> ConfigGroup): TickerConfig {
        val edited = edit(ConfigGroup(name = "default", watchlist = config.watchlist, lots = config.lots))
        return config.copy(watchlist = edited.watchlist, lots = edited.lots)
    }

    private fun selectedGroupIndex(): Int = _selectedGroup.value.coerceAtLeast(0)

    // editGroups applies group management to the displayed group list (the promoted
    // default group first, per withDefaultGroup) and normalizes the result into the
    // stored groups form so a renamed or removed promoted default group round-trips
    // through YAML export; displayed indices match the tab row one-for-one.
    private fun editGroups(edit: (List<ConfigGroup>) -> List<ConfigGroup>) {
        mutate { config ->
            val displayed = config.withDefaultGroup().groups
            val updated = edit(displayed)
            if (updated == displayed) {
                config
            } else {
                config.copy(watchlist = emptyList(), lots = emptyList(), groups = updated)
            }
        }
    }

    // write persists a config edit; a failed write is reported through saveError and
    // never thrown at the root scope.
    private suspend fun write(config: TickerConfig) {
        try {
            configRepo.save(config)
            _saveError.value = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _saveError.value = e.message ?: "failed to save the configuration"
        }
    }
}
