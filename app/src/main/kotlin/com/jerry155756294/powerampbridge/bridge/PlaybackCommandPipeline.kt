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
  val optimisticPlaybackState: String? = null,
  val suppressedSenderPause: Boolean = false,
  val protocolEvents: List<String> = emptyList(),
  val powerampEvents: List<String> = emptyList()
)

internal class PlaybackCommandPipeline(
  private val adapter: MbrcProtocolAdapter
) {
  fun handle(
    message: IncomingMessage,
    nowMs: Long,
    currentPlaybackState: String? = null
  ): CommandPipelineResult {
    val intent = intentFor(message)
    val replies = adapter.handleCommand(message)
    val executed = commandExecuted(intent, replies)
    val optimisticPlaybackState = if (executed) {
      optimisticStateFor(intent, currentPlaybackState)
    } else {
      null
    }
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
      executed = executed,
      optimisticPlaybackState = optimisticPlaybackState,
      protocolEvents = protocolEvents
    )
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

  private fun commandExecuted(intent: PlaybackCommandIntent, replies: List<String>): Boolean =
    when (intent) {
      PlaybackCommandIntent.PLAY,
      PlaybackCommandIntent.PLAY_PAUSE,
      PlaybackCommandIntent.PAUSE,
      PlaybackCommandIntent.STOP -> replies.isEmpty()
      else -> true
    }

  private fun optimisticStateFor(
    intent: PlaybackCommandIntent,
    currentPlaybackState: String?
  ): String? = when (intent) {
    PlaybackCommandIntent.PLAY -> "playing"
    PlaybackCommandIntent.PAUSE,
    PlaybackCommandIntent.STOP -> "paused"
    PlaybackCommandIntent.PLAY_PAUSE -> if (currentPlaybackState == "playing") "paused" else "playing"
    else -> null
  }
}
