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

    // addSymbol adds to the group selected when the user acts: a promoted top-level
    // default group writes back to the top-level watchlist, a named group to its own
    // entry. The target group is captured at action time — before the mutation joins
    // the queue — while its contents are read fresh when the mutation runs.
    fun addSymbol(symbol: String) {
        val trimmed = symbol.trim()
        if (trimmed.isEmpty()) return
        editGroup(targetAt(selectedGroupIndex())) { group ->
            if (trimmed in group.watchlist) group else group.copy(watchlist = group.watchlist + trimmed)
        }
    }

    // removeSymbol removes a watchlist entry of the acted-on group (a lot-owned symbol
    // stays visible through the implicit watchlist until its lots are edited away).
    fun removeSymbol(symbol: String) {
        editGroup(targetAt(selectedGroupIndex())) { group ->
            group.copy(watchlist = group.watchlist.filterNot { it == symbol })
        }
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
        mutate { config ->
            val displayed = config.withDefaultGroup().groups
            // group names stay unique so a name identifies one group across queued
            // actions; a duplicate name is rejected
            if (displayed.any { it.name == trimmed }) {
                config
            } else {
                config.copy(watchlist = emptyList(), lots = emptyList(), groups = displayed + ConfigGroup(name = trimmed))
            }
        }
    }

    fun renameGroup(index: Int, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val target = targetAt(index)
        mutate { config -> renameGroupAt(config, target, trimmed) }
    }

    fun removeGroup(index: Int) {
        val target = targetAt(index)
        mutate { config ->
            if (target.promotedDefault) {
                // removing the promoted default group drops its top-level entries
                if (config.watchlist.isEmpty() && config.lots.isEmpty()) {
                    config
                } else {
                    config.copy(watchlist = emptyList(), lots = emptyList())
                }
            } else {
                val stored = config.groups.indexOfFirst { it.name == target.name }
                if (stored < 0) {
                    config
                } else {
                    config.copy(groups = config.groups.filterIndexed { i, _ -> i != stored })
                }
            }
        }
    }

    // addLot always appends to the acted-on group: a symbol can carry several cost-basis
    // lots.
    fun addLot(lot: Lot) {
        editGroup(targetAt(selectedGroupIndex())) { group -> group.copy(lots = group.lots + lot) }
    }

    // updateLot replaces the lot the user selected: its content is captured at action
    // time and re-found when the queued mutation runs, so a queued change to the same
    // lot list cannot shift the edit onto a different lot.
    fun updateLot(index: Int, lot: Lot) {
        val target = targetAt(selectedGroupIndex())
        val previous = lotAt(target, index) ?: return
        editGroup(target) { group ->
            val at = locateLot(group.lots, index, previous)
            if (at < 0) {
                group
            } else {
                group.copy(lots = group.lots.mapIndexed { i, current -> if (i == at) lot else current })
            }
        }
    }

    // deleteLot removes the lot the user selected, located by its captured content
    // rather than by its (possibly shifted) index.
    fun deleteLot(index: Int) {
        val target = targetAt(selectedGroupIndex())
        val previous = lotAt(target, index) ?: return
        editGroup(target) { group ->
            val at = locateLot(group.lots, index, previous)
            if (at < 0) {
                group
            } else {
                group.copy(lots = group.lots.filterIndexed { i, _ -> i != at })
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

    // GroupTarget identifies a group across queued mutations: the promoted top-level
    // default group structurally (it has no stored name), stored groups by name. Group
    // names are kept unique so a name identifies exactly one group; a target that no
    // longer exists when its mutation runs makes the action a no-op, never a redirect
    // onto whichever group shifted into its place.
    private data class GroupTarget(val name: String, val promotedDefault: Boolean)

    // targetAt captures the identity of the group shown at a displayed index at action
    // time — before the mutation joins the queue — so a later config change (which
    // resets the selection) cannot retarget a queued action.
    private fun targetAt(index: Int): GroupTarget {
        val config = configRepo.config.value ?: return GroupTarget(defaultGroupName, promotedDefault = true)
        val displayed = config.withDefaultGroup().groups
        if (displayed.isEmpty()) {
            // an empty config's first edit creates the default group
            return GroupTarget(defaultGroupName, promotedDefault = true)
        }
        val selected = index.coerceIn(0, displayed.lastIndex)
        return GroupTarget(
            name = displayed[selected].name,
            promotedDefault = (config.watchlist.isNotEmpty() || config.lots.isNotEmpty()) && selected == 0,
        )
    }

    // editGroup applies a group edit under the mutex to the CURRENT contents of the
    // captured target: the promoted top-level default group writes back to the
    // top-level watchlist/lots, a named group to its own stored entry, and a target
    // that is already gone leaves the config unchanged.
    private fun editGroup(target: GroupTarget, edit: (ConfigGroup) -> ConfigGroup) {
        mutate { config ->
            if (target.promotedDefault) {
                if (config.watchlist.isNotEmpty() || config.lots.isNotEmpty() || config.groups.isEmpty()) {
                    withTopLevelDefault(config, edit)
                } else {
                    config
                }
            } else {
                val stored = config.groups.indexOfFirst { it.name == target.name }
                if (stored < 0) {
                    config
                } else {
                    config.copy(groups = config.groups.mapIndexed { i, group -> if (i == stored) edit(group) else group })
                }
            }
        }
    }

    private fun withTopLevelDefault(config: TickerConfig, edit: (ConfigGroup) -> ConfigGroup): TickerConfig {
        val edited = edit(ConfigGroup(name = defaultGroupName, watchlist = config.watchlist, lots = config.lots))
        return config.copy(watchlist = edited.watchlist, lots = edited.lots)
    }

    // lotAt captures the content of the lot shown at an index at action time: content is
    // a lot's only stable identity, so queued changes to the group's lots cannot
    // redirect a later update or delete onto a different lot.
    private fun lotAt(target: GroupTarget, index: Int): Lot? {
        val config = configRepo.config.value ?: return null
        val group = groupAt(config, target) ?: return null
        return group.lots.getOrNull(index)
    }

    private fun groupAt(config: TickerConfig, target: GroupTarget): ConfigGroup? =
        if (target.promotedDefault) {
            ConfigGroup(name = defaultGroupName, watchlist = config.watchlist, lots = config.lots)
        } else {
            config.groups.firstOrNull { it.name == target.name }
        }

    // locateLot finds the captured lot in the current list: its captured position while
    // that still holds the same lot, otherwise the first content match, or -1 when an
    // earlier queued action already removed it — then the action is a no-op.
    private fun locateLot(lots: List<Lot>, index: Int, previous: Lot): Int =
        if (lots.getOrNull(index) == previous) index else lots.indexOfFirst { it == previous }

    // renameGroupAt renames the captured target in the current config. The promoted
    // default group has no stored name, so renaming it stores it under the new name at
    // its displayed position; duplicate names are rejected so names stay unique.
    private fun renameGroupAt(config: TickerConfig, target: GroupTarget, name: String): TickerConfig {
        val taken = config.withDefaultGroup().groups.mapTo(HashSet()) { it.name }
        taken.remove(target.name)
        if (name in taken) return config

        return if (target.promotedDefault) {
            if (config.watchlist.isEmpty() && config.lots.isEmpty() && config.groups.isNotEmpty()) {
                config
            } else {
                config.copy(
                    watchlist = emptyList(),
                    lots = emptyList(),
                    groups = listOf(ConfigGroup(name = name, watchlist = config.watchlist, lots = config.lots)) + config.groups,
                )
            }
        } else {
            val stored = config.groups.indexOfFirst { it.name == target.name }
            if (stored < 0) {
                config
            } else {
                config.copy(groups = config.groups.mapIndexed { i, group -> if (i == stored) group.copy(name = name) else group })
            }
        }
    }

    private fun selectedGroupIndex(): Int = _selectedGroup.value.coerceAtLeast(0)

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

// defaultGroupName matches the name withDefaultGroup gives the promoted top-level
// group; that name is the group's identity across queued mutations.
private const val defaultGroupName = "default"
