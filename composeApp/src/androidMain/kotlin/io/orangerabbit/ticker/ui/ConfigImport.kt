package io.orangerabbit.ticker.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ConfigImport exposes the document-picking actions to MainActivity. The pickers are
// Android SAF (ActivityResultContracts.OpenDocument / CreateDocument) and live here in
// androidMain because the Activity result APIs are Android-only; the shared
// GroupTabsScreen stays platform-agnostic and just receives plain () -> Unit callbacks.
class ConfigImport(
    val openImportPicker: () -> Unit,
    val openExportPicker: () -> Unit,
)

// rememberConfigImport wires the SAF pickers. The picked document is read in a
// coroutine on Dispatchers.IO so a slow provider never blocks the main thread, and
// the read is guarded against provider failures (FileNotFoundException,
// SecurityException, IOException): non-cancellation failures are forwarded to onError
// (the UI error state) instead of crashing the activity, while cancellation
// propagates normally.
@Composable
fun rememberConfigImport(
    onImported: (uriString: String, yaml: String) -> Unit,
    onExported: (uriString: String) -> Unit,
    onError: (message: String) -> Unit,
): ConfigImport {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val yaml = try {
                    withContext(Dispatchers.IO) {
                        val stream = context.contentResolver.openInputStream(uri)
                            ?: throw Exception("cannot open $uri for reading")
                        stream.use { it.readBytes().toString(Charsets.UTF_8) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onError(e.message ?: "failed to read the selected document")
                    return@launch
                }
                onImported(uri.toString(), yaml)
            }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        if (uri != null) {
            onExported(uri.toString())
        }
    }
    return ConfigImport(
        // mime */*: .ticker.yaml has no registered MIME type
        openImportPicker = { importLauncher.launch(arrayOf("*/*")) },
        openExportPicker = { exportLauncher.launch("ticker.yaml") },
    )
}
