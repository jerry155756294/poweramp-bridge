package com.jerry155756294.powerampbridge.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BridgeServiceStartActionTest {
  @Test
  fun runningBridgeSelectsRestartAction() {
    assertEquals(
      "com.jerry155756294.powerampbridge.action.RESTART",
      BridgeService.requestedStartAction(serviceRunning = true)
    )
  }

  @Test
  fun stoppedBridgeSelectsNormalStart() {
    assertNull(BridgeService.requestedStartAction(serviceRunning = false))
  }
}
