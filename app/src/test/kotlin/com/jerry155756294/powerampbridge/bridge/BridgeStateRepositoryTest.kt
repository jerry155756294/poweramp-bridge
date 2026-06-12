package com.jerry155756294.powerampbridge.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BridgeStateRepositoryTest {
  private val repository = BridgeStateRepository()

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
    assertEquals(45L, latency.averageMs)
    assertEquals(45L, latency.maxMs)
    assertEquals(1, latency.sampleCount)
  }

  @Test
  fun `latency summary prefers observed samples for aggregates`() {
    repository.recordLatencySample(
      command = "playerplay",
      dispatchMs = 30L,
      observedMs = 120L
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
}
