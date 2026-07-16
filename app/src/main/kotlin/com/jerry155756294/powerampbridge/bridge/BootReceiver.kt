package com.jerry155756294.powerampbridge.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jerry155756294.powerampbridge.BridgeApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

    val pendingResult = goAsync()
    CoroutineScope(Dispatchers.Default).launch {
      try {
        val app = context.applicationContext as BridgeApplication
        val settings = app.appContainer.settingsRepository.settings.first()
        // App-launch and boot-launch are independent preferences. Boot should not depend on
        // whether the user also wants the activity-open behavior.
        if (settings.startOnBoot) {
          BridgeService.start(context)
        }
      } finally {
        pendingResult.finish()
      }
    }
  }
}
