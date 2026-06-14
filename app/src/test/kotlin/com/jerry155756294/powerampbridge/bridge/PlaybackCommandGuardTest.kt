package com.jerry155756294.powerampbridge.bridge

import com.jerry155756294.powerampbridge.protocol.IncomingMessage
import com.jerry155756294.powerampbridge.protocol.ProtocolConstants
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackCommandGuardTest {
  private val guard = PlaybackCommandGuard(pauseSuppressionWindowMs = 750L)

  @Test
  fun `suppresses pause shortly after play command`() {
    guard.recordIncomingCommand(
      IncomingMessage(ProtocolConstants.PlayerPlay, null),
      observedAtMs = 1_000L
    )

    assertTrue(
      guard.shouldSuppress(
        IncomingMessage(ProtocolConstants.PlayerPause, null),
        observedAtMs = 1_200L
      )
    )
  }

  @Test
  fun `suppresses pause shortly after playing state is observed`() {
    guard.recordPlaybackState("playing", observedAtMs = 5_000L)

    assertTrue(
      guard.shouldSuppress(
        IncomingMessage(ProtocolConstants.PlayerPause, null),
        observedAtMs = 5_400L
      )
    )
  }

  @Test
  fun `does not suppress pause after guard window expires`() {
    guard.recordIncomingCommand(
      IncomingMessage(ProtocolConstants.PlayerPlayPause, null),
      observedAtMs = 10_000L
    )

    assertFalse(
      guard.shouldSuppress(
        IncomingMessage(ProtocolConstants.PlayerPause, null),
        observedAtMs = 10_900L
      )
    )
  }

  @Test
  fun `does not suppress unrelated commands`() {
    guard.recordIncomingCommand(
      IncomingMessage(ProtocolConstants.PlayerPlay, null),
      observedAtMs = 20_000L
    )

    assertFalse(
      guard.shouldSuppress(
        IncomingMessage(ProtocolConstants.PlayerNext, null),
        observedAtMs = 20_100L
      )
    )
  }
}
