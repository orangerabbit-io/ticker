package io.orangerabbit.ticker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.orangerabbit.ticker.config.Lot
import io.orangerabbit.ticker.config.TickerConfig
import io.orangerabbit.ticker.config.withDefaultGroup
import io.orangerabbit.ticker.poll.PollState

// GroupTabsScreen is the app's single screen: a tab row over the config's groups (with
// the top-level watchlist/lots promoted to a "default" group via withDefaultGroup), the
// selected group's quote list with optional pinned summary bar and positions section,
// pull-to-refresh, and add-symbol and lot-editor bottom sheets. With no config imported
// it shows the SAF import prompt.
@Composable
fun GroupTabsScreen(
    config: TickerConfig?,
    pollState: PollState,
    error: String?,
    onImportRequested: () -> Unit,
    onExportRequested: () -> Unit,
    onGroupSelected: (Int) -> Unit,
    onRefresh: () -> Unit,
    onAddSymbol: (String) -> Unit,
    onSaveLot: (Lot) -> Unit,
    onDeleteLot: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val groups = config?.withDefaultGroup()?.groups.orEmpty()
    var selectedIndex by remember(config) { mutableStateOf(0) }
    var showAddSheet by remember { mutableStateOf(false) }
    var showLotSheet by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
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
        when {
            config == null -> NoConfigImported(
                onImportRequested = onImportRequested,
                modifier = Modifier.weight(1f),
            )
            groups.isEmpty() -> Text(
                text = "No groups in configuration",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
            else -> {
                val selected = selectedIndex.coerceIn(0, groups.lastIndex)
                TabRow(selectedTabIndex = selected) {
                    groups.forEachIndexed { index, group ->
                        Tab(
                            selected = selected == index,
                            onClick = {
                                selectedIndex = index
                                onGroupSelected(index)
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
                QuoteList(
                    config = config,
                    pollState = pollState,
                    onRefresh = onRefresh,
                    modifier = Modifier.weight(1f),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    OutlinedButton(onClick = { showAddSheet = true }) {
                        Text("Add symbol")
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { showLotSheet = true }) {
                        Text("Edit lots")
                    }
                }
            }
        }
    }

    if (showAddSheet) {
        AddSymbolSheet(
            onAdd = {
                showAddSheet = false
                onAddSymbol(it)
            },
            onDismiss = { showAddSheet = false },
        )
    }
    if (showLotSheet) {
        LotEditorSheet(
            lots = config?.withDefaultGroup()?.groups?.firstOrNull()?.lots.orEmpty(),
            onSave = {
                showLotSheet = false
                onSaveLot(it)
            },
            onDelete = {
                showLotSheet = false
                onDeleteLot(it)
            },
            onDismiss = { showLotSheet = false },
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
        Text(text = "No config imported", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        Text(text = "Import a .ticker.yaml file to get started.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onImportRequested) {
            Text("Import")
        }
    }
}

// AddSymbolSheet appends a typed symbol to the default group's watchlist through the
// config repository; the poller restarts when the saved config emits.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSymbolSheet(
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var symbol by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(text = "Add symbol", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = symbol,
                onValueChange = { symbol = it },
                label = { Text("Symbol") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { onAdd(symbol) },
                enabled = symbol.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Add")
            }
        }
    }
}

// LotEditorSheet adds, updates, and deletes lots of the default group: tapping an
// existing lot loads it into the fields, Save adds or updates by symbol, and Delete
// removes the lot with the typed symbol.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LotEditorSheet(
    lots: List<Lot>,
    onSave: (Lot) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var symbol by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf("") }
    var unitCost by remember { mutableStateOf("") }
    var fixedCost by remember { mutableStateOf("") }

    val quantityValue = quantity.trim().toDoubleOrNull()
    val unitCostValue = unitCost.trim().toDoubleOrNull()
    val fixedCostValue = fixedCost.trim().toDoubleOrNull()
    val hasInvalidField = listOf(quantity, unitCost, fixedCost).any {
        it.isNotBlank() && it.trim().toDoubleOrNull() == null
    }
    val trimmedSymbol = symbol.trim()
    val canSave = trimmedSymbol.isNotBlank() && !hasInvalidField
    val canDelete = lots.any { it.symbol == trimmedSymbol }

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
                lots.forEach { lot ->
                    TextButton(
                        onClick = {
                            symbol = lot.symbol
                            quantity = formatAmount(lot.quantity, false)
                            unitCost = formatMoney(lot.unitCost)
                            fixedCost = formatMoney(lot.fixedCost)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = lot.symbol + " · " + formatAmount(lot.quantity, false) +
                                " @ " + formatMoney(lot.unitCost),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
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
                    text = "Quantity, unit cost, and fixed cost must be numbers",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row {
                Button(
                    onClick = {
                        onSave(
                            Lot(
                                symbol = trimmedSymbol,
                                unitCost = unitCostValue ?: 0.0,
                                quantity = quantityValue ?: 0.0,
                                fixedCost = fixedCostValue ?: 0.0,
                            ),
                        )
                    },
                    enabled = canSave,
                ) {
                    Text("Save")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { onDelete(trimmedSymbol) },
                    enabled = canDelete,
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
