package com.jerry155756294.powerampbridge.bridge

import com.jerry155756294.powerampbridge.protocol.IncomingMessage
import com.jerry155756294.powerampbridge.protocol.ProtocolConstants

internal class PlaybackCommandGuard(
  private val pauseSuppressionWindowMs: Long = DEFAULT_PAUSE_SUPPRESSION_WINDOW_MS
) {
  private var lastPlayCommandAtMs: Long? = null
  private var lastPlayingObservedAtMs: Long? = null

  fun recordIncomingCommand(message: IncomingMessage, observedAtMs: Long) {
    when (message.context) {
      ProtocolConstants.PlayerPlay,
      ProtocolConstants.PlayerPlayPause -> lastPlayCommandAtMs = observedAtMs
      else -> Unit
    }
  }

  fun recordPlaybackState(state: String, observedAtMs: Long) {
    when (state) {
      "playing" -> lastPlayingObservedAtMs = observedAtMs
      else -> Unit
    }
  }

  fun shouldSuppress(message: IncomingMessage, observedAtMs: Long): Boolean {
    if (message.context != ProtocolConstants.PlayerPause) {
      return false
    }

    val nearRecentPlayCommand = lastPlayCommandAtMs
      ?.let { observedAtMs - it in 0..pauseSuppressionWindowMs }
      ?: false
    val nearRecentPlayingState = lastPlayingObservedAtMs
      ?.let { observedAtMs - it in 0..pauseSuppressionWindowMs }
      ?: false

    return nearRecentPlayCommand || nearRecentPlayingState
  }

  private companion object {
    const val DEFAULT_PAUSE_SUPPRESSION_WINDOW_MS = 750L
  }
}
