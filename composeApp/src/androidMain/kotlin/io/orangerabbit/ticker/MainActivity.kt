package io.orangerabbit.ticker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.orangerabbit.ticker.ui.GroupTabsScreen
import io.orangerabbit.ticker.ui.TickerTheme
import io.orangerabbit.ticker.ui.rememberConfigImport
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class MainActivity : ComponentActivity(), KoinComponent {

    private val repository: ConfigRepository by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TickerTheme {
                TickerApp(repository = repository)
            }
        }
    }
}

// TickerApp wires the SAF pickers and the config repository into the group tabs
// screen. The quote poller is started by HomeViewModel in Task 10; this screen only
// displays the configuration.
@Composable
private fun TickerApp(repository: ConfigRepository) {
    val config by repository.config.collectAsState()
    val configError by repository.configError.collectAsState()
    val scope = rememberCoroutineScope()
    var actionError by remember { mutableStateOf<String?>(null) }

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
    )

    GroupTabsScreen(
        config = config,
        error = actionError ?: configError,
        onImportRequested = configImport.openImportPicker,
        onExportRequested = configImport.openExportPicker,
    )
}
