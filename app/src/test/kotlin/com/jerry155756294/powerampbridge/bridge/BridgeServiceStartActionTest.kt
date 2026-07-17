package com.jerry155756294.powerampbridge.bridge

import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BridgeServiceStartActionTest {
  @Test
  fun `running bridge dispatches restart action`() {
    val context = RecordingContext()

    BridgeService.startOrRestart(context, serviceRunning = true)

    assertEquals(
      "com.jerry155756294.powerampbridge.action.RESTART",
      context.lastStartedIntent?.action
    )
  }

  @Test
  fun `stopped bridge dispatches normal start`() {
    val context = RecordingContext()

    BridgeService.startOrRestart(context, serviceRunning = false)

    assertNull(context.lastStartedIntent?.action)
  }

  private class RecordingContext : ContextWrapper(null) {
    var lastStartedIntent: Intent? = null

    override fun startForegroundService(service: Intent?): ComponentName {
      lastStartedIntent = service
      return ComponentName("test", BridgeService::class.java.name)
    }

    override fun startService(service: Intent?): ComponentName {
      lastStartedIntent = service
      return ComponentName("test", BridgeService::class.java.name)
    }
  }
}
