package io.orangerabbit.ticker

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.orangerabbit.ticker.ui.ThemeMode
import io.orangerabbit.ticker.ui.ThemeSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

// Single app-wide Preferences DataStore for presentation settings, deliberately
// separate from the config DataStore: ThemeMode must never round-trip into exported
// YAML (see SettingsUpdate/TickerConfig).
val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

private val themeModeKey = stringPreferencesKey("theme_mode")

// SettingsStore persists the theme mode under the theme_mode key. An unset or
// unrecognized stored value resolves to SYSTEM.
class SettingsStore(private val context: Context) : ThemeSettings {

    // Store-owned scope backing the DataStore-derived StateFlow; the store lives for
    // the process lifetime (registered in Koin).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val mode: StateFlow<ThemeMode> = context.settingsDataStore.data
        .map { prefs -> parseThemeMode(prefs[themeModeKey]) }
        .stateIn(scope, SharingStarted.Eagerly, ThemeMode.SYSTEM)

    override suspend fun set(mode: ThemeMode) {
        context.settingsDataStore.edit { it[themeModeKey] = mode.name }
    }
}

// parseThemeMode resolves the stored name to a ThemeMode; missing or invalid values
// fall back to SYSTEM.
private fun parseThemeMode(stored: String?): ThemeMode =
    ThemeMode.entries.firstOrNull { it.name == stored } ?: ThemeMode.SYSTEM
