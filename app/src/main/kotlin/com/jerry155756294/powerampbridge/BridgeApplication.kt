package com.jerry155756294.powerampbridge

import android.app.Application
import com.jerry155756294.powerampbridge.bridge.BridgeStateRepository
import com.jerry155756294.powerampbridge.bridge.NetworkAddressMonitor
import com.jerry155756294.powerampbridge.data.BridgeSettingsRepository
import timber.log.Timber

class BridgeApplication : Application() {
  lateinit var appContainer: AppContainer
    private set
  private lateinit var networkAddressMonitor: NetworkAddressMonitor

  override fun onCreate() {
    super.onCreate()
    Timber.plant(Timber.DebugTree())
    appContainer = AppContainer(
      settingsRepository = BridgeSettingsRepository(this),
      stateRepository = BridgeStateRepository()
    )
    networkAddressMonitor = NetworkAddressMonitor(this, appContainer.stateRepository)
    networkAddressMonitor.start()
  }
}

data class AppContainer(
  val settingsRepository: BridgeSettingsRepository,
  val stateRepository: BridgeStateRepository
)
