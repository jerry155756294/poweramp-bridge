package com.jerry155756294.powerampbridge.bridge

import com.jerry155756294.powerampbridge.protocol.IncomingMessage
import com.jerry155756294.powerampbridge.protocol.MbrcProtocolAdapter
import com.jerry155756294.powerampbridge.protocol.ProtocolConstants

internal enum class PlaybackCommandIntent {
  PLAY,
  PLAY_PAUSE,
  PAUSE,
  STOP,
  NEXT,
  PREVIOUS,
  SEEK,
  VOLUME,
  SHUFFLE,
  REPEAT,
  OTHER
}

internal data class CommandPipelineResult(
  val replies: List<String>,
  val intent: PlaybackCommandIntent,
  val executed: Boolean,
  val suppressedSenderPause: Boolean = false,
  val protocolEvents: List<String> = emptyList(),
  val powerampEvents: List<String> = emptyList()
)

internal class PlaybackCommandPipeline(
  private val adapter: MbrcProtocolAdapter,
  private val guard: PlaybackCommandGuard = PlaybackCommandGuard()
) {
  fun handle(message: IncomingMessage, nowMs: Long): CommandPipelineResult {
    val intent = intentFor(message)
    if (guard.shouldSuppress(message, nowMs)) {
      return CommandPipelineResult(
        replies = emptyList(),
        intent = intent,
        executed = false,
        suppressedSenderPause = true,
        protocolEvents = listOf(
          "command_pipeline:suppress_pause_echo",
          "suppressed_echo_pause:${message.context}"
        ),
        powerampEvents = listOf("Suppressed echo pause within playback guard window")
      )
    }

    guard.recordIncomingCommand(message, nowMs)
    val replies = adapter.handleCommand(message)
    val protocolEvents = when (intent) {
      PlaybackCommandIntent.PLAY -> listOf("command_pipeline:dispatch_play")
      PlaybackCommandIntent.PLAY_PAUSE -> listOf("command_pipeline:dispatch_playpause")
      PlaybackCommandIntent.PAUSE -> listOf("command_pipeline:dispatch_pause")
      PlaybackCommandIntent.STOP -> listOf("command_pipeline:dispatch_stop")
      PlaybackCommandIntent.NEXT -> listOf("command_pipeline:dispatch_next")
      PlaybackCommandIntent.PREVIOUS -> listOf("command_pipeline:dispatch_previous")
      PlaybackCommandIntent.SEEK -> listOf("command_pipeline:dispatch_seek")
      PlaybackCommandIntent.VOLUME -> listOf("command_pipeline:dispatch_volume")
      PlaybackCommandIntent.SHUFFLE -> listOf("command_pipeline:dispatch_shuffle")
      PlaybackCommandIntent.REPEAT -> listOf("command_pipeline:dispatch_repeat")
      PlaybackCommandIntent.OTHER -> emptyList()
    }

    return CommandPipelineResult(
      replies = replies,
      intent = intent,
      executed = true,
      protocolEvents = protocolEvents
    )
  }

  fun onPlaybackStateObserved(state: String, nowMs: Long) {
    guard.recordPlaybackState(state, nowMs)
  }

  private fun intentFor(message: IncomingMessage): PlaybackCommandIntent = when (message.context) {
    ProtocolConstants.PlayerPlay -> PlaybackCommandIntent.PLAY
    ProtocolConstants.PlayerPlayPause -> PlaybackCommandIntent.PLAY_PAUSE
    ProtocolConstants.PlayerPause -> PlaybackCommandIntent.PAUSE
    ProtocolConstants.PlayerStop -> PlaybackCommandIntent.STOP
    ProtocolConstants.PlayerNext -> PlaybackCommandIntent.NEXT
    ProtocolConstants.PlayerPrevious -> PlaybackCommandIntent.PREVIOUS
    ProtocolConstants.NowPlayingPosition -> PlaybackCommandIntent.SEEK
    ProtocolConstants.PlayerVolume -> PlaybackCommandIntent.VOLUME
    ProtocolConstants.PlayerShuffle -> PlaybackCommandIntent.SHUFFLE
    ProtocolConstants.PlayerRepeat -> PlaybackCommandIntent.REPEAT
    else -> PlaybackCommandIntent.OTHER
  }
}
