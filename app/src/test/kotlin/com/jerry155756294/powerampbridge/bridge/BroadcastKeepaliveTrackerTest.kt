package com.jerry155756294.powerampbridge.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BroadcastKeepaliveTrackerTest {
  private var nowMs = 0L
  private val tracker = BroadcastKeepaliveTracker(
    idleThresholdMs = 25_000L,
    clock = { nowMs }
  )

  @Test
  fun `keepalive is not due before any broadcast activity`() {
    nowMs = 30_000L

    assertFalse(tracker.shouldSendKeepalive())
  }

  @Test
  fun `keepalive becomes due after idle threshold elapses`() {
    tracker.recordActivity(1_000L)

    nowMs = 25_999L
    assertFalse(tracker.shouldSendKeepalive())

    nowMs = 26_000L
    assertTrue(tracker.shouldSendKeepalive())
  }

  @Test
  fun `recording activity resets keepalive deadline`() {
    tracker.recordActivity(1_000L)
    nowMs = 30_000L
    assertTrue(tracker.shouldSendKeepalive())

    tracker.recordActivity(30_000L)
    nowMs = 54_999L
    assertFalse(tracker.shouldSendKeepalive())
  }

  @Test
  fun `clear removes pending keepalive state`() {
    tracker.recordActivity(1_000L)
    nowMs = 30_000L
    assertTrue(tracker.shouldSendKeepalive())

    tracker.clear()

    assertFalse(tracker.shouldSendKeepalive())
  }
}
