package io.orangerabbit.ticker.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

// ConfigImport exposes the document-picking actions to MainActivity. The pickers are
// Android SAF (ActivityResultContracts.OpenDocument / CreateDocument) and live here in
// androidMain because the Activity result APIs are Android-only; the shared
// GroupTabsScreen stays platform-agnostic and just receives plain () -> Unit callbacks.
class ConfigImport(
    val openImportPicker: () -> Unit,
    val openExportPicker: () -> Unit,
)

@Composable
fun rememberConfigImport(
    onImported: (uriString: String, yaml: String) -> Unit,
    onExported: (uriString: String) -> Unit,
): ConfigImport {
    val context = LocalContext.current
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val yaml = context.contentResolver.openInputStream(uri)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                .orEmpty()
            onImported(uri.toString(), yaml)
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
