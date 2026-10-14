package io.orangerabbit.ticker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
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
import io.orangerabbit.ticker.ui.ThemeMode
import io.orangerabbit.ticker.ui.ThemeSettings
import io.orangerabbit.ticker.ui.TickerTheme
import io.orangerabbit.ticker.ui.rememberConfigImport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class MainActivity : ComponentActivity(), KoinComponent {

    private val repository: ConfigRepository by inject()
    private val viewModel: HomeViewModel by inject()
    private val themeSettings: ThemeSettings by inject()

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
            // The persisted ThemeMode resolves to TickerTheme's darkTheme flag here so
            // every color in the tree follows the setting (never system-dark detection
            // inside call sites).
            val mode by themeSettings.mode.collectAsState()
            TickerTheme(
                darkTheme = when (mode) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                },
            ) {
                // Root Surface: paints colorScheme.background over the activity window
                // (the platform theme's windowBackground is dark-first and does not
                // follow ThemeMode on its own).
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    TickerApp(
                        repository = repository,
                        viewModel = viewModel,
                        themeMode = mode,
                        onThemeModeChanged = themeSettings::set,
                    )
                }
            }
        }
    }
}

// TickerApp wires the SAF pickers, the config repository, and the home view model into
// the group tabs screen. The quote poller runs while this composition is active and
// while the activity is started. The current ThemeMode and its persist callback pass
// through to the settings sheet (theme is presentation state, never part of the
// Save-button batch); the persist runs here so a failed write surfaces through the
// action-error channel like every other action instead of escaping the coroutine.
@Composable
private fun TickerApp(
    repository: ConfigRepository,
    viewModel: HomeViewModel,
    themeMode: ThemeMode,
    onThemeModeChanged: suspend (ThemeMode) -> Unit,
) {
    val config by repository.config.collectAsState()
    val configError by repository.configError.collectAsState()
    val pollState by viewModel.state.collectAsState()
    val selectedGroup by viewModel.selectedGroup.collectAsState()
    val searchState by viewModel.searchState.collectAsState()
    val saveError by viewModel.saveError.collectAsState()
    val scope = rememberCoroutineScope()
    var actionError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(viewModel) {
        viewModel.start()
        onDispose { viewModel.stop() }
    }

    val configImport = rememberConfigImport(
        onImported = { uriString, yaml ->
            viewModel.importConfig(uriString, yaml) { result ->
                result.fold(
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
        searchState = searchState,
        themeMode = themeMode,
        onThemeModeChanged = { newMode ->
            scope.launch {
                try {
                    onThemeModeChanged(newMode)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    actionError = "failed to save theme"
                }
            }
        },
        onImportRequested = configImport.openImportPicker,
        onExportRequested = configImport.openExportPicker,
        onGroupSelected = viewModel::selectGroup,
        onRefresh = viewModel::refresh,
        onAddSymbol = viewModel::addSymbol,
        onRemoveSymbol = viewModel::removeSymbol,
        onSearchQueryChanged = viewModel::onSearchQueryChanged,
        onAddLot = viewModel::addLot,
        onUpdateLot = viewModel::updateLot,
        onDeleteLot = viewModel::deleteLot,
        onUpdateSettings = viewModel::updateSettings,
        onAddGroup = viewModel::addGroup,
        onRenameGroup = viewModel::renameGroup,
        onRemoveGroup = viewModel::removeGroup,
    )
}
