package com.jerry155756294.powerampbridge.data

data class BridgeSettings(
  val port: Int = 3000,
  val autoStart: Boolean = false,
  val startOnBoot: Boolean = false,
  val foregroundPersistent: Boolean = true
)
