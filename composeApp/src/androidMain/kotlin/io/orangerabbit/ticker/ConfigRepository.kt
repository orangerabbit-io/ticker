package io.orangerabbit.ticker

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.datastore.preferences.core.edit
import io.orangerabbit.ticker.config.TickerConfig
import io.orangerabbit.ticker.config.parseTickerConfig
import io.orangerabbit.ticker.config.parseTickerConfigJson
import io.orangerabbit.ticker.config.toJson
import io.orangerabbit.ticker.config.toYaml
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

// ConfigRepository exposes the imported ticker configuration to the UI plus SAF
// import/export. Import and export failures are reported through the returned Result
// so the caller can show the message; a failed import leaves the previous config
// untouched.
interface ConfigRepository {
    val config: StateFlow<TickerConfig?>

    // configError carries the message for a stored config that cannot be parsed
    // (config emits null in that case).
    val configError: StateFlow<String?>

    // current reads the stored config directly. Unlike the asynchronously updated
    // config StateFlow it is read-after-write consistent with save(), so serialized
    // read-modify-write edits always compose against the current state.
    suspend fun current(): TickerConfig?

    suspend fun import(uriString: String, yaml: String): Result<Unit>
    suspend fun export(uriString: String): Result<Unit>
    suspend fun save(config: TickerConfig)
}

// ConfigRepositoryImpl stores the config in a Preferences DataStore under the
// config_json key as normalized JSON. YAML is only used at the SAF boundaries: import
// parses the picked document's text, export re-serializes the stored config with
// toYaml(). The picked document's persistable URI permission is taken so the URI stays
// usable across process restarts.
class ConfigRepositoryImpl(private val context: Context) : ConfigRepository {

    // Repository-owned scope backing the DataStore-derived StateFlow; the repository
    // lives for the process lifetime (registered in Koin).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _configError = MutableStateFlow<String?>(null)
    override val configError: StateFlow<String?> = _configError.asStateFlow()

    override val config: StateFlow<TickerConfig?> = context.configDataStore.data
        .map { prefs -> parseStored(prefs[configJsonKey]) }
        .onEach { _configError.value = it.error }
        .map { it.config }
        .stateIn(scope, SharingStarted.Eagerly, null)

    override suspend fun current(): TickerConfig? =
        parseStored(context.configDataStore.data.first()[configJsonKey]).config

    override suspend fun import(uriString: String, yaml: String): Result<Unit> {
        val imported = parseTickerConfig(yaml).getOrElse { e ->
            return Result.failure(Exception(e.message ?: "failed to parse ticker configuration", e))
        }
        return try {
            takePersistableUriPermission(uriString, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            save(imported)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception(e.message ?: "failed to save the imported configuration", e))
        }
    }

    override suspend fun export(uriString: String): Result<Unit> {
        return try {
            val stored = context.configDataStore.data.first()[configJsonKey]
                ?: return Result.failure(Exception("no configuration to export"))
            val current = parseTickerConfigJson(stored).getOrElse { e ->
                return Result.failure(Exception(e.message ?: "stored configuration is invalid", e))
            }
            val yaml = current.toYaml()
            withContext(Dispatchers.IO) {
                takePersistableUriPermission(uriString, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                val uri = Uri.parse(uriString)
                val stream = context.contentResolver.openOutputStream(uri)
                    ?: throw Exception("cannot open $uriString for writing")
                stream.use { it.write(yaml.toByteArray()) }
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception(e.message ?: "failed to export the configuration", e))
        }
    }

    override suspend fun save(config: TickerConfig) {
        context.configDataStore.edit { it[configJsonKey] = config.toJson() }
    }

    private fun parseStored(json: String?): StoredConfig =
        if (json == null) {
            StoredConfig(null, null)
        } else {
            parseTickerConfigJson(json).fold(
                onSuccess = { StoredConfig(it, null) },
                onFailure = { StoredConfig(null, it.message ?: "failed to parse stored config") },
            )
        }

    // The document picker's grant is transient; taking the persistable grant keeps the
    // URI usable after a process restart. Failure is not fatal — the transient grant
    // already covers the current call — so it is swallowed here.
    private fun takePersistableUriPermission(uriString: String, flag: Int) {
        try {
            context.contentResolver.takePersistableUriPermission(Uri.parse(uriString), flag)
        } catch (e: SecurityException) {
            // best effort: only later access to the document is affected
        }
    }
}

private class StoredConfig(val config: TickerConfig?, val error: String?)
