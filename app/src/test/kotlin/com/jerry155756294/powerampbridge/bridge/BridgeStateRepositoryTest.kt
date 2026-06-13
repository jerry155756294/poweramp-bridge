package com.jerry155756294.powerampbridge.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeStateRepositoryTest {
  private val repository = BridgeStateRepository()

  @Test
  fun `manual stop blocks auto start until service starts again`() {
    repository.markServiceStarted()
    repository.markManualStopRequested()
    repository.markServiceStopped()

    val stopped = repository.state.value
    assertFalse(stopped.shouldAutoStart(autoStartEnabled = true))
    assertTrue(stopped.manualStopActive)
    assertEquals("Stopped manually", stopped.serviceStopSummary)

    repository.markServiceStarted()

    val restarted = repository.state.value
    assertFalse(restarted.shouldAutoStart(autoStartEnabled = true))
    assertFalse(restarted.manualStopActive)
    assertNull(restarted.serviceStopSummary)
  }

  @Test
  fun `service stopping state is visible before final stop`() {
    repository.markServiceStarted()
    repository.markManualStopRequested()

    val state = repository.state.value
    assertTrue(state.serviceRunning)
    assertTrue(state.serviceStopping)
    assertTrue(state.manualStopActive)
    assertEquals("Manual stop requested", state.serviceStopSummary)
  }

  @Test
  fun `latency summary tracks dispatch only sample`() {
    repository.recordLatencySample(
      command = "playerplay",
      dispatchMs = 45L,
      observedMs = null
    )

    val latency = repository.state.value.latencySummary
    assertEquals("playerplay", latency.lastCommand)
    assertEquals(45L, latency.lastDispatchMs)
    assertNull(latency.lastObservedMs)
    assertEquals("dispatch_only", latency.lastEffectStatus)
    assertEquals(45L, latency.averageMs)
    assertEquals(45L, latency.maxMs)
    assertEquals(1, latency.sampleCount)
  }

  @Test
  fun `latency summary prefers observed samples for aggregates`() {
    repository.recordLatencySample(
      command = "playerplay",
      dispatchMs = 30L,
      observedMs = 120L,
      effectStatus = "confirmed:playback_state"
    )
    repository.recordLatencySample(
      command = "playerpause",
      dispatchMs = 40L,
      observedMs = null
    )

    val latency = repository.state.value.latencySummary
    assertEquals("playerpause", latency.lastCommand)
    assertEquals(40L, latency.lastDispatchMs)
    assertNull(latency.lastObservedMs)
    assertEquals("dispatch_only", latency.lastEffectStatus)
    assertEquals(80L, latency.averageMs)
    assertEquals(120L, latency.maxMs)
    assertEquals(2, latency.sampleCount)
  }

  @Test
  fun `poweramp tpos sync events are collapsed`() {
    repository.recordPowerampEvent("Poweramp action: TPOS_SYNC extras=[api:Integer,pos:Integer]")
    repository.recordPowerampEvent("Position sync (TPOS_SYNC): 12s")
    repository.recordPowerampEvent("Poweramp action: TPOS_SYNC extras=[api:Integer,pos:Integer]")
    repository.recordPowerampEvent("Position sync (TPOS_SYNC): 14s")

    val events = repository.state.value.recentPowerampEvents
    assertEquals(1, events.size)
    assertEquals("TPOS_SYNC x 2, last pos=14s", events.first().message)
  }

  @Test
  fun `cover signal revision increments`() {
    repository.signalCoverChanged("test")
    repository.signalCoverChanged("test")

    assertEquals(2L, repository.state.value.coverSignalRevision)
  }

  @Test
  fun `cover state transitions update summary and sender signal`() {
    repository.updateCoverState(
      CoverSnapshot(
        realId = 42L,
        status = CoverStateStatus.LOADING
      )
    )
    repository.updateCoverState(
      CoverSnapshot(
        realId = 42L,
        status = CoverStateStatus.READY,
        base64 = "abc",
        elapsedMs = 33L
      )
    )

    val state = repository.state.value
    assertEquals(CoverStateStatus.READY, state.coverState.status)
    assertEquals("ready", state.coverState.summary())
    assertEquals("track=42 | elapsed=33ms", state.coverState.detail())
    assertEquals(2L, state.coverSignalRevision)
  }

  @Test
  fun `same cover state does not emit duplicate sender signal`() {
    val coverState = CoverSnapshot(
      realId = 7L,
      status = CoverStateStatus.MISSING,
      elapsedMs = 15L
    )

    repository.updateCoverState(coverState)
    repository.updateCoverState(coverState)

    assertEquals(1L, repository.state.value.coverSignalRevision)
  }
}
