package io.orangerabbit.ticker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.orangerabbit.ticker.config.ConfigGroup
import io.orangerabbit.ticker.config.Lot
import io.orangerabbit.ticker.config.TickerConfig
import io.orangerabbit.ticker.config.withDefaultGroup
import io.orangerabbit.ticker.poll.PollState
import io.orangerabbit.ticker.source.SearchResultSource
import io.orangerabbit.ticker.source.SymbolSearchOutcome
import io.orangerabbit.ticker.source.SymbolSearchResult
import kotlinx.coroutines.launch

// SearchState is the add-symbol sheet's search snapshot: the live query the debounced
// search runs against, whether a search is pending or in flight, and the outcome of the
// last completed search for the current query (typing a new query clears it until the
// new outcome lands). The whole state is null while idle — nothing typed or the sheet is
// closed.
data class SearchState(
    val query: String,
    val loading: Boolean,
    val outcome: SymbolSearchOutcome?,
)

// SettingsUpdate carries the settings-sheet edits (interval, sort mode, display flags)
// to the view model as one write; group edits use the dedicated callbacks.
data class SettingsUpdate(
    val interval: Int,
    val sort: String?,
    val showTags: Boolean,
    val showFundamentals: Boolean,
    val showSeparator: Boolean,
    val showSummary: Boolean,
    val showPositions: Boolean,
)

// GroupTabsScreen is the app's single screen: a tab row over the config's groups (with
// the top-level watchlist/lots promoted to a "default" group via withDefaultGroup) with
// a horizontally paged content area cycling the same groups, the selected group's quote
// list with optional pinned summary bar and positions section, pull-to-refresh, and
// symbols, lot-editor, and settings bottom sheets. The selected group comes from the view
// model (selectedGroup) so tabs, pager, and poller stay in sync across recreation. With
// no config imported it shows the SAF import prompt.
@Composable
fun GroupTabsScreen(
    config: TickerConfig?,
    pollState: PollState,
    error: String?,
    selectedGroup: Int,
    searchState: SearchState?,
    onImportRequested: () -> Unit,
    onExportRequested: () -> Unit,
    onGroupSelected: (Int) -> Unit,
    onRefresh: () -> Unit,
    onAddSymbol: (String) -> Unit,
    onRemoveSymbol: (String) -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onAddLot: (Lot) -> Unit,
    onUpdateLot: (Int, Lot) -> Unit,
    onDeleteLot: (Int) -> Unit,
    onUpdateSettings: (SettingsUpdate) -> Unit,
    onAddGroup: (String) -> Unit,
    onRenameGroup: (Int, String) -> Unit,
    onRemoveGroup: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val groups = config?.withDefaultGroup()?.groups.orEmpty()
    // the selected group is the tab/pager selection and the target of the editing
    // sheets; an empty config edits the (not yet created) default group at index 0
    val selected = if (groups.isEmpty()) 0 else selectedGroup.coerceIn(0, groups.lastIndex)
    var showSymbolsSheet by remember { mutableStateOf(false) }
    var showLotSheet by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }

    // safeDrawingPadding keeps the header clear of status bars and cutouts and the
    // bottom actions clear of the navigation bar under Android 15's enforced
    // edge-to-edge (targetSdk 36)
    Column(modifier = modifier.fillMaxSize().safeDrawingPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Ticker",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onImportRequested) {
                Text("Import")
            }
            if (config != null) {
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onExportRequested) {
                    Text("Export")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { showSettingsSheet = true }) {
                    Text("Settings")
                }
            }
        }
        if (error != null) {
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
        if (config == null) {
            NoConfigImported(
                onImportRequested = onImportRequested,
                modifier = Modifier.weight(1f),
            )
        } else {
            if (groups.isEmpty()) {
                // an empty configuration is editable too: the sheets below create the
                // default group with its first symbol or lot
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "No groups in configuration",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Add a symbol or lot to create the default group.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val pagerState = key(groups.size) {
                    rememberPagerState(initialPage = selected, pageCount = { groups.size })
                }
                val scope = rememberCoroutineScope()

                // keep the pager aligned with the view model's selection (config change
                // resets it to the default group; recreation restores the retained one)
                LaunchedEffect(selected) {
                    if (pagerState.currentPage != selected) {
                        pagerState.scrollToPage(selected)
                    }
                }
                // a pager swipe settles on a page and drives the group selection
                val currentOnGroupSelected by rememberUpdatedState(onGroupSelected)
                LaunchedEffect(pagerState) {
                    snapshotFlow { pagerState.settledPage }.collect { page ->
                        currentOnGroupSelected(page)
                    }
                }

                TabRow(selectedTabIndex = selected) {
                    groups.forEachIndexed { index, group ->
                        Tab(
                            selected = selected == index,
                            onClick = {
                                onGroupSelected(index)
                                scope.launch { pagerState.animateScrollToPage(index) }
                            },
                            text = { Text(text = group.name.ifEmpty { "group ${index + 1}" }) },
                        )
                    }
                }
                if (config.showSummary) {
                    pollState.summary?.let { SummaryBar(summary = it) }
                }
                pollState.error?.let { message ->
                    ErrorBanner(message = message, onRetry = onRefresh)
                }
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f),
                ) {
                    QuoteList(
                        config = config,
                        pollState = pollState,
                        onRefresh = onRefresh,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        // editing actions stay reachable in every state: on a fresh install the first
        // symbol or lot creates and persists the configuration — no import required
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            OutlinedButton(onClick = { showSymbolsSheet = true }) {
                Text("Symbols")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { showLotSheet = true }) {
                Text("Edit lots")
            }
        }
    }

    if (showSymbolsSheet) {
        // adding from search keeps the sheet open so several symbols can be added in
        // one sitting; closing (dismiss) clears the debounced search back to idle
        SearchSheet(
            existing = explicitWatchlist(config, selected),
            state = searchState,
            onQueryChanged = onSearchQueryChanged,
            onAdd = onAddSymbol,
            onRemove = onRemoveSymbol,
            onAddExact = onAddSymbol,
            onDismiss = {
                showSymbolsSheet = false
                onSearchQueryChanged("")
            },
        )
    }
    if (showLotSheet) {
        LotEditorSheet(
            lots = groups.getOrNull(selected)?.lots.orEmpty(),
            onAdd = {
                showLotSheet = false
                onAddLot(it)
            },
            onUpdate = { index, lot ->
                showLotSheet = false
                onUpdateLot(index, lot)
            },
            onDelete = { index ->
                showLotSheet = false
                onDeleteLot(index)
            },
            onDismiss = { showLotSheet = false },
        )
    }
    if (showSettingsSheet && config != null) {
        SettingsSheet(
            config = config,
            groups = groups,
            onSave = {
                showSettingsSheet = false
                onUpdateSettings(it)
            },
            onAddGroup = onAddGroup,
            onRenameGroup = onRenameGroup,
            onRemoveGroup = onRemoveGroup,
            onDismiss = { showSettingsSheet = false },
        )
    }
}

// QuoteList shows the poller's assets in order (the poller applies config.sort), with
// optional row separators, the positions section, and a subtle footer for unavailable
// symbols. Pull-to-refresh triggers an immediate poller refresh.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuoteList(
    config: TickerConfig,
    pollState: PollState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val positioned = pollState.assets.filter { it.position.quantity != 0.0 }
    PullToRefreshBox(
        isRefreshing = pollState.isLoading,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxWidth(),
    ) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(pollState.assets) { asset ->
                QuoteRow(
                    asset = asset,
                    showTags = config.showTags,
                    showFundamentals = config.showFundamentals,
                )
                if (config.showSeparator) {
                    HorizontalDivider()
                }
            }
            if (config.showPositions && positioned.isNotEmpty()) {
                item {
                    PositionsSection(assets = positioned)
                }
            }
            if (pollState.unavailable.isNotEmpty()) {
                item {
                    Text(
                        text = "Unavailable: " + pollState.unavailable.joinToString(", "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            if (pollState.assets.isEmpty() && pollState.unavailable.isEmpty() && !pollState.isLoading) {
                item {
                    Text(
                        text = "No quotes yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    )
                }
            }
        }
    }
}

// ErrorBanner surfaces a poller (source-prefixed) failure over the stale-but-visible
// quotes with a Retry action for an immediate refresh.
@Composable
private fun ErrorBanner(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry) {
            Text("Retry")
        }
    }
}

@Composable
private fun NoConfigImported(
    onImportRequested: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = "No config yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Add a symbol below to start fresh, or import an existing .ticker.yaml.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onImportRequested) {
            Text("Import")
        }
    }
}

// explicitWatchlist lists a group's explicit watchlist entries — the ones symbol
// removal can act on. The promoted default group's entries live in the top-level
// watchlist (index 0 while promotion applies), named groups carry their own, and an
// empty config's future default group reads the top level. Lot-owned symbols stay
// managed in the lot editor.
private fun explicitWatchlist(config: TickerConfig?, index: Int): List<String> {
    if (config == null) return emptyList()
    val promoted = config.watchlist.isNotEmpty() || config.lots.isNotEmpty()
    return when {
        config.groups.isEmpty() -> config.watchlist
        promoted && index == 0 -> config.watchlist
        else -> config.groups.getOrNull(if (promoted) index - 1 else index)?.watchlist.orEmpty()
    }
}

// SearchSheet finds symbols to add to the acted-on group (Yahoo and the Coinbase
// catalog, merged and ranked by the shared SymbolSearch) and removes mistaken entries
// from its watchlist; the poller restarts when the saved config emits. A result row
// proposes its symbol and adds it through the same addSymbol path as manual entry
// (which normalizes and dedupes) — the sheet stays open across adds so several can be
// done at once, and each added row shows "added" until the query changes. A degraded
// search surfaces an inline note and never blocks manual entry: the "no matches" row
// and the permanent "Add exact symbol…" row add the typed query as-is.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchSheet(
    existing: List<String>,
    state: SearchState?,
    onQueryChanged: (String) -> Unit,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onAddExact: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val query = state?.query.orEmpty()
    // row-level add feedback for the current query only; the next query clears it
    var added by remember(query) { mutableStateOf(emptySet<String>()) }
    val outcome = state?.outcome
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(text = "Symbols", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChanged,
                label = { Text("Symbol") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state?.loading == true) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Searching…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (outcome != null && outcome.results.isEmpty()) {
                TextButton(
                    onClick = { onAddExact(query) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("no matches — add $query as an exact symbol")
                }
            }
            outcome?.results?.forEach { result ->
                SearchResultRow(
                    result = result,
                    added = result.symbol in added,
                    onAdd = {
                        added = added + result.symbol
                        onAdd(result.symbol)
                    },
                )
            }
            if (outcome?.yahooFailed == true) {
                Text(
                    text = "Yahoo search unavailable — results may be incomplete",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (outcome?.catalogFailed == true) {
                Text(
                    text = "symbol match only — Coinbase catalog unavailable",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (existing.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(text = "Watchlist", style = MaterialTheme.typography.labelMedium)
                existing.forEach { entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = entry,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onRemove(entry) }) {
                            Text("Remove")
                        }
                    }
                }
            }
            // the permanent last row: exact-symbol entry never depends on search results
            TextButton(
                onClick = { onAddExact(query) },
                enabled = query.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Add exact symbol…")
            }
        }
    }
}

// SearchResultRow shows one proposed symbol — proposed symbol in mono, matched name
// muted, source tag — with an Add action that stays on the row as "added" feedback for
// the current query.
@Composable
private fun SearchResultRow(
    result: SymbolSearchResult,
    added: Boolean,
    onAdd: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = result.symbol,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
            if (result.name.isNotEmpty()) {
                Text(
                    text = result.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = result.source.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        if (added) {
            Text(
                text = "added",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            TextButton(onClick = onAdd) {
                Text("Add")
            }
        }
    }
}

// label is the source tag shown on a result row.
private val SearchResultSource.label: String
    get() = when (this) {
        SearchResultSource.YAHOO -> "Yahoo"
        SearchResultSource.COINBASE -> "Coinbase"
    }

// SettingsSheet is the single in-app configuration surface the spec's config lifecycle
// requires: refresh interval, sort mode, display flags, and group management
// (add/rename/remove). Settings apply on Save as one write; group edits apply through
// their own buttons. Symbol and lot edits live in the Symbols and Edit lots sheets.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    config: TickerConfig,
    groups: List<ConfigGroup>,
    onSave: (SettingsUpdate) -> Unit,
    onAddGroup: (String) -> Unit,
    onRenameGroup: (Int, String) -> Unit,
    onRemoveGroup: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var interval by remember { mutableStateOf(config.interval.toString()) }
    var sort by remember { mutableStateOf(config.sort) }
    var showTags by remember { mutableStateOf(config.showTags) }
    var showFundamentals by remember { mutableStateOf(config.showFundamentals) }
    var showSeparator by remember { mutableStateOf(config.showSeparator) }
    var showSummary by remember { mutableStateOf(config.showSummary) }
    var showPositions by remember { mutableStateOf(config.showPositions) }
    var newGroupName by remember { mutableStateOf("") }

    val intervalValue = interval.trim().toIntOrNull()
    val intervalValid = intervalValue != null && intervalValue >= 0
    // group names stay unique (a name identifies a group across queued actions), so
    // add/rename onto a taken name is disabled here and rejected in the view model
    val takenNames = groups.mapTo(HashSet()) { it.name }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(text = "Settings", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = interval,
                onValueChange = { interval = it },
                label = { Text("Refresh interval (seconds)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            if (!intervalValid) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Interval must be a whole number of zero or more",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(text = "Sort", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SortOption(label = "Change", selected = sort == null) { sort = null }
                SortOption(label = "Alpha", selected = sort == "alpha") { sort = "alpha" }
                SortOption(label = "Value", selected = sort == "value") { sort = "value" }
                SortOption(label = "User", selected = sort == "user") { sort = "user" }
            }
            Spacer(Modifier.height(8.dp))
            FlagRow(label = "Show tags", checked = showTags) { showTags = it }
            FlagRow(label = "Show fundamentals", checked = showFundamentals) { showFundamentals = it }
            FlagRow(label = "Show separator", checked = showSeparator) { showSeparator = it }
            FlagRow(label = "Show summary", checked = showSummary) { showSummary = it }
            FlagRow(label = "Show positions", checked = showPositions) { showPositions = it }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text(text = "Groups", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            groups.forEachIndexed { index, group ->
                GroupRow(
                    group = group,
                    isNameTaken = { it in takenNames },
                    onRename = { onRenameGroup(index, it) },
                    onRemove = { onRemoveGroup(index) },
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newGroupName,
                    onValueChange = { newGroupName = it },
                    label = { Text("New group name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        onAddGroup(newGroupName.trim())
                        newGroupName = ""
                    },
                    enabled = newGroupName.trim().isNotEmpty() && newGroupName.trim() !in takenNames,
                ) {
                    Text("Add")
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    onSave(
                        SettingsUpdate(
                            interval = intervalValue ?: config.interval,
                            sort = sort,
                            showTags = showTags,
                            showFundamentals = showFundamentals,
                            showSeparator = showSeparator,
                            showSummary = showSummary,
                            showPositions = showPositions,
                        ),
                    )
                },
                enabled = intervalValid,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save")
            }
        }
    }
}

@Composable
private fun SortOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onSelect,
        label = { Text(label) },
    )
}

@Composable
private fun FlagRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Checkbox(checked = checked, onCheckedChange = onChange)
    }
}

// GroupRow renames or removes one displayed group; the rename applies on the button so
// typing does not restart the poller on every keystroke. Renaming to a taken name is
// disabled to keep group names unique.
@Composable
private fun GroupRow(
    group: ConfigGroup,
    isNameTaken: (String) -> Boolean,
    onRename: (String) -> Unit,
    onRemove: () -> Unit,
) {
    var name by remember(group.name) { mutableStateOf(group.name) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        TextButton(
            onClick = { onRename(name.trim()) },
            enabled = name.trim().isNotEmpty() && name.trim() != group.name && !isNameTaken(name.trim()),
        ) {
            Text("Rename")
        }
        TextButton(onClick = onRemove) {
            Text("Remove")
        }
    }
}

// LotEditorSheet adds, updates, and deletes lots of the selected group. A lot is
// identified by its index: tapping a lot selects and loads it (numeric fields use
// lossless ungrouped values so no holding data is rounded away), Save updates the
// selected lot or appends a new one, and Delete removes only the selected lot.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LotEditorSheet(
    lots: List<Lot>,
    onAdd: (Lot) -> Unit,
    onUpdate: (Int, Lot) -> Unit,
    onDelete: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    var symbol by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf("") }
    var unitCost by remember { mutableStateOf("") }
    var fixedCost by remember { mutableStateOf("") }

    val quantityValue = parseFinite(quantity)
    val unitCostValue = parseFinite(unitCost)
    val fixedCostValue = parseFinite(fixedCost)
    // NaN, Infinity, and overflowed literals (1e309) are rejected alongside
    // non-numeric input: the JSON serializer cannot represent them
    val hasInvalidField = listOf(quantity, unitCost, fixedCost).any {
        it.isNotBlank() && parseFinite(it) == null
    }
    val trimmedSymbol = symbol.trim()
    val canSave = trimmedSymbol.isNotBlank() && !hasInvalidField
    val selected = selectedIndex?.takeIf { it in lots.indices }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(text = "Edit lots", style = MaterialTheme.typography.titleMedium)
            if (lots.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                lots.forEachIndexed { index, lot ->
                    TextButton(
                        onClick = {
                            selectedIndex = index
                            symbol = lot.symbol
                            quantity = lot.quantity.toString()
                            unitCost = lot.unitCost.toString()
                            fixedCost = lot.fixedCost.toString()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = (if (index == selected) "▸ " else "") + lot.symbol +
                                " · " + formatAmount(lot.quantity, false) + " @ " + formatMoney(lot.unitCost),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (index == selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (index == selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
            TextButton(
                onClick = {
                    selectedIndex = null
                    symbol = ""
                    quantity = ""
                    unitCost = ""
                    fixedCost = ""
                },
            ) {
                Text("New lot")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = symbol,
                onValueChange = { symbol = it },
                label = { Text("Symbol") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = quantity,
                onValueChange = { quantity = it },
                label = { Text("Quantity") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = unitCost,
                onValueChange = { unitCost = it },
                label = { Text("Unit cost") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = fixedCost,
                onValueChange = { fixedCost = it },
                label = { Text("Fixed cost") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            if (hasInvalidField) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Quantity, unit cost, and fixed cost must be finite numbers",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row {
                Button(
                    onClick = {
                        val lot = Lot(
                            symbol = trimmedSymbol,
                            unitCost = unitCostValue ?: 0.0,
                            quantity = quantityValue ?: 0.0,
                            fixedCost = fixedCostValue ?: 0.0,
                        )
                        val index = selected
                        if (index != null) onUpdate(index, lot) else onAdd(lot)
                    },
                    enabled = canSave,
                ) {
                    Text(if (selected != null) "Update" else "Add")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { selected?.let { onDelete(it) } },
                    enabled = selected != null,
                ) {
                    Text("Delete")
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
        }
    }
}

// parseFinite accepts only finite numeric input: NaN, Infinity, and overflowed
// literals like 1e309 all parse as non-finite doubles and must not reach the
// JSON serializer, which cannot represent them.
private fun parseFinite(text: String): Double? = text.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
