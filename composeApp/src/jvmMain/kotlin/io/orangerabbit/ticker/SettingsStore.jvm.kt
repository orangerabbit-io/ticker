package io.orangerabbit.ticker

import io.orangerabbit.ticker.ui.ThemeMode
import io.orangerabbit.ticker.ui.ThemeSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// In-memory ThemeSettings for the JVM target: the JVM target only compiles the common
// UI code (no desktop app exists), so there is nothing to persist against.
class SettingsStore : ThemeSettings {

    private val _mode = MutableStateFlow(ThemeMode.SYSTEM)

    override val mode: StateFlow<ThemeMode> = _mode.asStateFlow()

    override suspend fun set(mode: ThemeMode) {
        _mode.value = mode
    }
}
