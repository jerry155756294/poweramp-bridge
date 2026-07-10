package com.jerry155756294.powerampbridge.data

data class BridgeSettings(
  val port: Int = 3000,
  val autoStart: Boolean = false,
  val startOnBoot: Boolean = false,
  val foregroundPersistent: Boolean = true,
  val minimalForegroundNotification: Boolean = false,
  val advancedDiagnosticsEnabled: Boolean = false,
  val powerampDataAccessPermissionRequested: Boolean = false
)
