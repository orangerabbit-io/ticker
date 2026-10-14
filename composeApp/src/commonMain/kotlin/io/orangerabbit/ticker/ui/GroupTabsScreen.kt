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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.orangerabbit.ticker.config.ConfigGroup
import io.orangerabbit.ticker.config.TickerConfig
import io.orangerabbit.ticker.config.withDefaultGroup

// GroupTabsScreen is the app's single screen: a tab row over the config's groups (with
// the top-level watchlist/lots promoted to a "default" group via withDefaultGroup) and
// a "no config imported" empty state prompting a SAF import. The selected group's
// configuration is displayed as a placeholder until Task 10 attaches the quote list.
@Composable
fun GroupTabsScreen(
    config: TickerConfig?,
    error: String?,
    onImportRequested: () -> Unit,
    onExportRequested: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val groups = config?.withDefaultGroup()?.groups.orEmpty()
    var selectedIndex by remember(config) { mutableStateOf(0) }

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
                            onClick = { selectedIndex = index },
                            text = { Text(text = group.name.ifEmpty { "group ${index + 1}" }) },
                        )
                    }
                }
                GroupConfig(group = groups[selected], modifier = Modifier.weight(1f))
            }
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

@Composable
private fun GroupConfig(
    group: ConfigGroup,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(text = group.name, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        for (symbol in group.watchlist) {
            Text(text = symbol, style = MaterialTheme.typography.bodyLarge)
        }
        for (lot in group.lots) {
            Text(
                text = "${lot.symbol}  ${lot.quantity} @ ${lot.unitCost}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
