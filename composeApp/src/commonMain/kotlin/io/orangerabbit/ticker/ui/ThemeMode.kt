package io.orangerabbit.ticker.ui

import kotlinx.coroutines.flow.StateFlow

// ThemeMode is the user's theme preference: follow the system, or force light/dark.
// It is presentation-only state and deliberately never enters SettingsUpdate or
// TickerConfig so it cannot round-trip into exported YAML.
enum class ThemeMode { SYSTEM, LIGHT, DARK }

// ThemeSettings is the persisted ThemeMode choice and the single source of truth for
// the app's darkTheme flag. Implementations live per platform: a Preferences DataStore
// on Android, in-memory on the JVM compile-gate target.
interface ThemeSettings {
    val mode: StateFlow<ThemeMode>
    suspend fun set(mode: ThemeMode)
}
