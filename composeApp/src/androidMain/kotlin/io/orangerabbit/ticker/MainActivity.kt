package io.orangerabbit.ticker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.orangerabbit.ticker.ui.GroupTabsScreen
import io.orangerabbit.ticker.ui.HomeViewModel
import io.orangerabbit.ticker.ui.TickerTheme
import io.orangerabbit.ticker.ui.rememberConfigImport
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class MainActivity : ComponentActivity(), KoinComponent {

    private val repository: ConfigRepository by inject()
    private val viewModel: HomeViewModel by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Poller lifecycle ownership on Android: ON_START starts the poller through the
        // HomeViewModel and ON_STOP stops it; TickerApp adds the composition-level
        // start/stop on top. No lifecycle-runtime-compose dependency is needed because
        // ComponentActivity already exposes the Lifecycle.
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.start()
                Lifecycle.Event.ON_STOP -> viewModel.stop()
                else -> Unit
            }
        })
        setContent {
            TickerTheme {
                TickerApp(repository = repository, viewModel = viewModel)
            }
        }
    }
}

// TickerApp wires the SAF pickers, the config repository, and the home view model into
// the group tabs screen. The quote poller runs while this composition is active and
// while the activity is started.
@Composable
private fun TickerApp(repository: ConfigRepository, viewModel: HomeViewModel) {
    val config by repository.config.collectAsState()
    val configError by repository.configError.collectAsState()
    val pollState by viewModel.state.collectAsState()
    val selectedGroup by viewModel.selectedGroup.collectAsState()
    val saveError by viewModel.saveError.collectAsState()
    val scope = rememberCoroutineScope()
    var actionError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(viewModel) {
        viewModel.start()
        onDispose { viewModel.stop() }
    }

    val configImport = rememberConfigImport(
        onImported = { uriString, yaml ->
            scope.launch {
                repository.import(uriString, yaml).fold(
                    onSuccess = { actionError = null },
                    onFailure = { actionError = it.message ?: "import failed" },
                )
            }
        },
        onExported = { uriString ->
            scope.launch {
                repository.export(uriString).fold(
                    onSuccess = { actionError = null },
                    onFailure = { actionError = it.message ?: "export failed" },
                )
            }
        },
        onError = { actionError = it },
    )

    GroupTabsScreen(
        config = config,
        pollState = pollState,
        error = actionError ?: saveError ?: configError,
        selectedGroup = selectedGroup,
        onImportRequested = configImport.openImportPicker,
        onExportRequested = configImport.openExportPicker,
        onGroupSelected = viewModel::selectGroup,
        onRefresh = viewModel::refresh,
        onAddSymbol = viewModel::addSymbol,
        onRemoveSymbol = viewModel::removeSymbol,
        onAddLot = viewModel::addLot,
        onUpdateLot = viewModel::updateLot,
        onDeleteLot = viewModel::deleteLot,
        onUpdateSettings = viewModel::updateSettings,
        onAddGroup = viewModel::addGroup,
        onRenameGroup = viewModel::renameGroup,
        onRemoveGroup = viewModel::removeGroup,
    )
}
