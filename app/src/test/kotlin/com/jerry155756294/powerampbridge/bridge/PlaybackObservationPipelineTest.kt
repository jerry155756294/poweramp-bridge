package com.jerry155756294.powerampbridge.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackObservationPipelineTest {
  private val pipeline = PlaybackObservationPipeline()

  @Test
  fun `recovery fires once after play then suppressed pause then observed pause`() {
    pipeline.onCommandResult(
      CommandPipelineResult(
        replies = emptyList(),
        intent = PlaybackCommandIntent.PLAY_PAUSE,
        executed = true
      ),
      nowMs = 1_000L
    )
    pipeline.onCommandResult(
      CommandPipelineResult(
        replies = emptyList(),
        intent = PlaybackCommandIntent.PAUSE,
        executed = false,
        suppressedSenderPause = true
      ),
      nowMs = 1_200L
    )

    val first = pipeline.onPlaybackObserved(
      previous = playingState(),
      current = pausedState(),
      nowMs = 1_300L
    )
    val second = pipeline.onPlaybackObserved(
      previous = playingState(),
      current = pausedState(),
      nowMs = 1_350L
    )

    assertEquals(ObservationAction.REPLAY_PLAY, first.action)
    assertTrue(first.protocolEvents.contains("recovery_policy:replay_play_once"))
    assertNull(second.action)
  }

  @Test
  fun `recovery does not fire when sender explicitly paused`() {
    pipeline.onCommandResult(
      CommandPipelineResult(
        replies = emptyList(),
        intent = PlaybackCommandIntent.PLAY,
        executed = true
      ),
      nowMs = 2_000L
    )
    pipeline.onCommandResult(
      CommandPipelineResult(
        replies = emptyList(),
        intent = PlaybackCommandIntent.PAUSE,
        executed = true
      ),
      nowMs = 2_100L
    )
    pipeline.onCommandResult(
      CommandPipelineResult(
        replies = emptyList(),
        intent = PlaybackCommandIntent.PAUSE,
        executed = false,
        suppressedSenderPause = true
      ),
      nowMs = 2_150L
    )

    val result = pipeline.onPlaybackObserved(
      previous = playingState(),
      current = pausedState(),
      nowMs = 2_200L
    )

    assertNull(result.action)
  }

  @Test
  fun `recovery expires after the short window`() {
    pipeline.onCommandResult(
      CommandPipelineResult(
        replies = emptyList(),
        intent = PlaybackCommandIntent.PLAY,
        executed = true
      ),
      nowMs = 3_000L
    )
    pipeline.onCommandResult(
      CommandPipelineResult(
        replies = emptyList(),
        intent = PlaybackCommandIntent.PAUSE,
        executed = false,
        suppressedSenderPause = true
      ),
      nowMs = 3_100L
    )

    val result = pipeline.onPlaybackObserved(
      previous = playingState(),
      current = pausedState(),
      nowMs = 4_000L
    )

    assertNull(result.action)
  }

  private fun playingState(): BridgeUiState =
    BridgeUiState(playback = PlaybackSnapshot(state = "playing"))

  private fun pausedState(): BridgeUiState =
    BridgeUiState(playback = PlaybackSnapshot(state = "paused"))
}
