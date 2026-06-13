package com.jerry155756294.powerampbridge.bridge

internal enum class ObservationAction {
  REPLAY_PLAY
}

internal data class ObservationResult(
  val action: ObservationAction? = null,
  val protocolEvents: List<String> = emptyList(),
  val powerampEvents: List<String> = emptyList()
)

internal class PlaybackObservationPipeline(
  private val recoveryPolicy: UnexpectedPauseRecoveryPolicy = UnexpectedPauseRecoveryPolicy()
) {
  fun onCommandResult(result: CommandPipelineResult, nowMs: Long) {
    recoveryPolicy.recordCommandResult(result, nowMs)
  }

  fun onPlaybackObserved(
    previous: BridgeUiState?,
    current: BridgeUiState,
    nowMs: Long
  ): ObservationResult {
    recoveryPolicy.recordPlaybackState(current.playback.state, nowMs)
    if (
      previous?.playback?.state == "playing" &&
      current.playback.state == "paused" &&
      recoveryPolicy.shouldRecover(nowMs)
    ) {
      return ObservationResult(
        action = ObservationAction.REPLAY_PLAY,
        protocolEvents = listOf(
          "observation_pipeline:unexpected_pause_detected",
          "unexpected_pause_recovery:play",
          "recovery_policy:replay_play_once"
        ),
        powerampEvents = listOf("Recovering from unexpected pause shortly after play")
      )
    }
    return ObservationResult()
  }
}

internal class UnexpectedPauseRecoveryPolicy(
  private val recoveryWindowMs: Long = 800L
) {
  private var lastBridgePlayCommandAtMs: Long? = null
  private var lastSenderPauseAtMs: Long? = null
  private var lastSuppressedPauseAtMs: Long? = null
  private var recoveryIssued = false

  fun recordCommandResult(result: CommandPipelineResult, nowMs: Long) {
    when {
      result.suppressedSenderPause -> {
        lastSuppressedPauseAtMs = nowMs
      }
      result.executed && result.intent == PlaybackCommandIntent.PAUSE -> {
        lastSenderPauseAtMs = nowMs
      }
      result.executed &&
        (result.intent == PlaybackCommandIntent.PLAY || result.intent == PlaybackCommandIntent.PLAY_PAUSE) -> {
        lastBridgePlayCommandAtMs = nowMs
        recoveryIssued = false
      }
    }
  }

  fun recordPlaybackState(state: String, @Suppress("UNUSED_PARAMETER") nowMs: Long) {
    when (state) {
      "playing" -> recoveryIssued = false
      "stopped" -> {
        recoveryIssued = false
        lastBridgePlayCommandAtMs = null
        lastSenderPauseAtMs = null
        lastSuppressedPauseAtMs = null
      }
      else -> Unit
    }
  }

  fun shouldRecover(nowMs: Long): Boolean {
    if (recoveryIssued) {
      return false
    }

    val recentPlay = lastBridgePlayCommandAtMs
      ?.let { nowMs - it in 0..recoveryWindowMs }
      ?: false
    val recentSuppressedPause = lastSuppressedPauseAtMs
      ?.let { nowMs - it in 0..recoveryWindowMs }
      ?: false
    val senderPausedRecently = lastSenderPauseAtMs
      ?.let { nowMs - it in 0..recoveryWindowMs }
      ?: false

    val shouldRecover = recentPlay && recentSuppressedPause && !senderPausedRecently
    if (shouldRecover) {
      recoveryIssued = true
    }
    return shouldRecover
  }
}
