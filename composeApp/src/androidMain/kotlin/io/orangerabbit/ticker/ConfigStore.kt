package io.orangerabbit.ticker

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

// Single app-wide Preferences DataStore. The config is stored as normalized JSON
// (see io.orangerabbit.ticker.config.parseTickerConfigJson / toJson), not YAML: YAML
// only appears at the SAF boundaries.
val Context.configDataStore: DataStore<Preferences> by preferencesDataStore(name = "config")

val configJsonKey: Preferences.Key<String> = stringPreferencesKey("config_json")
