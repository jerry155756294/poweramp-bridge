package com.jerry155756294.powerampbridge.bridge

import com.jerry155756294.powerampbridge.protocol.IncomingMessage
import com.jerry155756294.powerampbridge.protocol.JsonMessageCodec
import com.jerry155756294.powerampbridge.protocol.MbrcProtocolAdapter
import com.jerry155756294.powerampbridge.protocol.ProtocolConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackCommandPipelineTest {
  private val controller = FakePowerampController()
  private val repository = BridgeStateRepository()
  private val adapter = MbrcProtocolAdapter(JsonMessageCodec(), repository, controller)
  private val pipeline = PlaybackCommandPipeline(adapter)

  @Test
  fun `playerpause is dispatched immediately after play`() {
    pipeline.handle(IncomingMessage(ProtocolConstants.PlayerPlay, null), nowMs = 1_000L)

    val result = pipeline.handle(IncomingMessage(ProtocolConstants.PlayerPause, null), nowMs = 1_200L)

    assertTrue(result.executed)
    assertEquals("paused", result.optimisticPlaybackState)
    assertTrue(result.protocolEvents.contains("command_pipeline:dispatch_pause"))
    assertEquals(1, controller.pauseCalls)
  }

  @Test
  fun `playpause dispatch remains unaffected`() {
    val result = pipeline.handle(
      IncomingMessage(ProtocolConstants.PlayerPlayPause, null),
      nowMs = 5_000L,
      currentPlaybackState = "playing"
    )

    assertTrue(result.executed)
    assertEquals(PlaybackCommandIntent.PLAY_PAUSE, result.intent)
    assertEquals("paused", result.optimisticPlaybackState)
    assertTrue(result.protocolEvents.contains("command_pipeline:dispatch_playpause"))
    assertEquals(1, controller.playPauseCalls)
  }

  private class FakePowerampController : PowerampController {
    var playPauseCalls = 0
    var playCalls = 0
    var pauseCalls = 0

    override fun currentCoverStatus(): Int = 404
    override fun currentCoverPayload(): Map<String, Any?> = emptyMap()
    override fun readQueueItems(): List<PowerampQueueItem> = emptyList()
    override fun readRadioStations(): List<PowerampRadioStation> = emptyList()
    override fun playPause(): Boolean {
      playPauseCalls += 1
      return true
    }
    override fun play(): Boolean {
      playCalls += 1
      return true
    }
    override fun pause(): Boolean {
      pauseCalls += 1
      return true
    }
    override fun stopPlayback(): Boolean = true
    override fun next(): Boolean = true
    override fun previous(): Boolean = true
    override fun playQueuePosition(position: Int): Boolean = true
    override fun playPath(path: String): Boolean = true
    override fun handleQueueCommand(
      type: String,
      paths: List<String>,
      playPath: String?
    ): QueueCommandResult = QueueCommandResult(200, true, "ok")
    override fun setVolume(volumePercent: Int) = Unit
    override fun refreshVolumeSnapshot() = Unit
    override fun seekTo(positionMs: Long): Boolean = true
    override fun toggleShuffle(): Boolean = true
    override fun setShuffle(mode: String): Boolean = true
    override fun toggleRepeat(): Boolean = true
    override fun setRepeat(mode: String): Boolean = true
  }
}
