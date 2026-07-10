package com.jerry155756294.powerampbridge.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "bridge_settings")

class BridgeSettingsRepository(
  private val context: Context
) {
  val settings: Flow<BridgeSettings> = context.dataStore.data.map { preferences ->
    BridgeSettings(
      port = preferences[Keys.Port]?.takeIf { it in 1..65535 } ?: 3000,
      autoStart = preferences[Keys.AutoStart] ?: false,
      startOnBoot = preferences[Keys.StartOnBoot] ?: false,
      foregroundPersistent = preferences[Keys.ForegroundPersistent] ?: true,
      minimalForegroundNotification = preferences[Keys.MinimalForegroundNotification] ?: false,
      advancedDiagnosticsEnabled = preferences[Keys.AdvancedDiagnosticsEnabled] ?: false,
      powerampDataAccessPermissionRequested =
        preferences[Keys.PowerampDataAccessPermissionRequested] ?: false
    )
  }

  suspend fun updatePort(port: Int) {
    context.dataStore.edit { it[Keys.Port] = port.coerceIn(1, 65535) }
  }

  suspend fun updateAutoStart(enabled: Boolean) {
    context.dataStore.edit { it[Keys.AutoStart] = enabled }
  }

  suspend fun updateStartOnBoot(enabled: Boolean) {
    context.dataStore.edit { it[Keys.StartOnBoot] = enabled }
  }

  suspend fun updateForegroundPersistent(enabled: Boolean) {
    context.dataStore.edit { it[Keys.ForegroundPersistent] = enabled }
  }

  suspend fun updateMinimalForegroundNotification(enabled: Boolean) {
    context.dataStore.edit { it[Keys.MinimalForegroundNotification] = enabled }
  }

  suspend fun updateAdvancedDiagnostics(enabled: Boolean) {
    context.dataStore.edit { it[Keys.AdvancedDiagnosticsEnabled] = enabled }
  }

  suspend fun markPowerampDataAccessPermissionRequested() {
    context.dataStore.edit { it[Keys.PowerampDataAccessPermissionRequested] = true }
  }

  private object Keys {
    val Port: Preferences.Key<Int> = intPreferencesKey("port")
    val AutoStart: Preferences.Key<Boolean> = booleanPreferencesKey("auto_start")
    val StartOnBoot: Preferences.Key<Boolean> = booleanPreferencesKey("start_on_boot")
    val ForegroundPersistent: Preferences.Key<Boolean> =
      booleanPreferencesKey("foreground_persistent")
    val MinimalForegroundNotification: Preferences.Key<Boolean> =
      booleanPreferencesKey("minimal_foreground_notification")
    val AdvancedDiagnosticsEnabled: Preferences.Key<Boolean> =
      booleanPreferencesKey("advanced_diagnostics_enabled")
    val PowerampDataAccessPermissionRequested: Preferences.Key<Boolean> =
      booleanPreferencesKey("poweramp_data_access_permission_requested")
  }
}
