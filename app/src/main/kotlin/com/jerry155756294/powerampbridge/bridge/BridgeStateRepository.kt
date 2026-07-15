package com.jerry155756294.powerampbridge.bridge

import com.jerry155756294.powerampbridge.protocol.LogicalClientSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber

class BridgeStateRepository {
  private val _state = MutableStateFlow(BridgeUiState())
  val state: StateFlow<BridgeUiState> = _state.asStateFlow()

  fun markServiceStarted() {
    _state.update {
      it.copy(
        serviceRunning = true,
        serviceStopping = false,
        manualStopActive = false,
        serviceStopSummary = null,
        serviceStopDetail = null
      )
    }
  }

  fun markManualStopRequested() {
    Timber.i("Manual stop requested")
    _state.update {
      it.copy(
        serviceStopping = true,
        manualStopActive = true,
        serviceStopSummary = "已要求手動停止",
        serviceStopDetail = "Bridge 正在停止，直到你再次啟動前都會保持停止。"
      )
    }
  }

  fun markServiceStopping(manualStop: Boolean = _state.value.manualStopActive) {
    Timber.i("Service stopping (manual_stop=%s)", manualStop)
    _state.update {
      it.copy(
        serviceRunning = true,
        serviceStopping = true,
        manualStopActive = manualStop,
        serviceStopSummary = if (manualStop) "已要求手動停止" else "Bridge 正在停止",
        serviceStopDetail = if (manualStop) {
          "Bridge 正在停止，直到你再次啟動前都會保持停止。"
        } else {
          "Bridge 正在關閉。"
        }
      )
    }
  }

  fun markServiceStopped(manualStop: Boolean = _state.value.manualStopActive) {
    Timber.i("Service stopped (manual_stop=%s)", manualStop)
    _state.update {
      it.copy(
        serviceRunning = false,
        serviceStopping = false,
        manualStopActive = manualStop,
        serviceStopSummary = if (manualStop) "已手動停止" else "Bridge 已停止",
        serviceStopDetail = if (manualStop) {
          "自動啟動已暫停，直到你再次啟動 Bridge。"
        } else {
          null
        }
      )
    }
  }

  fun setListenerState(active: Boolean, port: Int) {
    _state.update { it.copy(listenerActive = active, listenPort = port) }
  }

  fun setLocalAddresses(addresses: List<String>) {
    _state.update { it.copy(localAddresses = addresses) }
  }

  fun updateSession(snapshot: LogicalClientSnapshot?) {
    _state.update { it.withSession(snapshot) }
  }

  fun recordProbe(remoteAddress: String) {
    val timestamp = LogEntry.timestampNow()
    _state.update {
      it.copy(lastProbeAt = "$timestamp ($remoteAddress)")
    }
  }

  fun recordRejectedConnection(event: ConnectionEventSnapshot, reason: String) {
    Timber.w("Rejected connection: %s", event.format(reason))
    _state.update {
      it.copy(lastRejectedReason = event.format(reason))
    }
  }

  fun recordDisconnect(event: ConnectionEventSnapshot, reason: String) {
    Timber.i("Disconnect: %s", event.format(reason))
    _state.update {
      it.copy(
        lastDisconnectReason = event.format(reason),
        lastDisconnectCategory = event.disconnectCategory ?: reason,
        lastDisconnectSocketRole = event.role?.name?.lowercase(),
        lastDisconnectHandshakeState = event.handshakeState,
        lastDisconnectLastCommand = event.lastIncomingContext,
        lastDisconnectLastReply = event.lastOutgoingContext
      )
    }
  }

  fun setPowerampAvailable(available: Boolean) {
    _state.update {
      it.copy(
        powerampAvailable = available,
        playback = it.playback.copy(powerampAvailable = available)
      )
    }
  }

  fun setPowerampDataAccess(status: PowerampDataAccessStatus, detail: String? = null) {
    _state.update { current ->
      if (current.powerampDataAccess == status && current.powerampDataAccessDetail == detail) {
        current
      } else {
        current.copy(
        powerampDataAccess = status,
        powerampDataAccessDetail = detail
        )
      }
    }
  }

  fun updatePlayback(transform: (PlaybackSnapshot) -> PlaybackSnapshot) {
    _state.update { current ->
      current.copy(playback = transform(current.playback))
    }
  }

  fun overrideSenderPlaybackState(
    state: String,
    reason: String,
    expiresAtElapsedRealtimeMs: Long
  ) {
    Timber.d(
      "Sender state override: state=%s reason=%s expires_at=%d",
      state,
      reason,
      expiresAtElapsedRealtimeMs
    )
    _state.update { current ->
      current.copy(
        senderPlaybackOverride = SenderPlaybackOverride(
          state = state,
          reason = reason,
          expiresAtElapsedRealtimeMs = expiresAtElapsedRealtimeMs
        )
      )
    }
  }

  fun clearSenderPlaybackOverride(reason: String? = null) {
    _state.update { current ->
      val active = current.senderPlaybackOverride ?: return@update current
      if (reason != null && active.reason != reason) {
        return@update current
      }
      current.copy(senderPlaybackOverride = null)
    }
  }

  fun tickPlaybackPosition(stepMs: Long) {
    _state.update { current ->
      if (current.playback.state != "playing") {
        return@update current
      }

      val track = current.playback.track
      val nextPosition = if (track.durationMs > 0L) {
        (track.positionMs + stepMs).coerceAtMost(track.durationMs)
      } else {
        track.positionMs + stepMs
      }

      current.copy(
        playback = current.playback.copy(
          track = track.copy(positionMs = nextPosition)
        )
      )
    }
  }

  fun setPositionSyncActive(active: Boolean) {
    _state.update { it.copy(positionSyncActive = active) }
  }

  fun recordCommand(message: String) {
    Timber.d("Command: %s", message)
    _state.update { it.copy(recentCommands = addEntry(it.recentCommands, message)) }
  }

  fun recordProtocolEvent(message: String) {
    Timber.d("Protocol: %s", message)
    _state.update { it.copy(recentProtocolEvents = addEntry(it.recentProtocolEvents, message)) }
  }

  fun recordPowerampEvent(message: String) {
    if (!message.startsWith("Poweramp action: TPOS_SYNC") &&
      !message.startsWith("Position sync (TPOS_SYNC):")
    ) {
      Timber.d("Poweramp: %s", message)
    }
    _state.update { current ->
      current.copy(recentPowerampEvents = addPowerampEntry(current.recentPowerampEvents, message))
    }
  }

  fun recordLatencySample(
    command: String,
    dispatchMs: Long,
    observedMs: Long?,
    effectStatus: String = if (observedMs == null) "dispatch_only" else "confirmed"
  ) {
    val effectiveMs = observedMs ?: dispatchMs
    Timber.d(
      "Latency: command=%s dispatch_ms=%d observed_ms=%s status=%s",
      command,
      dispatchMs,
      observedMs?.toString() ?: "unconfirmed",
      effectStatus
    )
    _state.update { current ->
      val previous = current.latencySummary
      val sampleCount = previous.sampleCount + 1
      val totalMs = previous.totalMs + effectiveMs
      current.copy(
        latencySummary = previous.copy(
          lastCommand = command,
          lastDispatchMs = dispatchMs,
          lastObservedMs = observedMs,
          lastEffectStatus = effectStatus,
          averageMs = totalMs / sampleCount,
          maxMs = maxOf(previous.maxMs ?: 0L, effectiveMs),
          sampleCount = sampleCount,
          totalMs = totalMs
        )
      )
    }
  }

  fun updateCoverState(coverState: CoverSnapshot) {
    _state.update { current ->
      if (current.coverState == coverState) {
        current
      } else {
        current.copy(
          coverState = coverState,
          coverSignalRevision = current.coverSignalRevision + 1L
        )
      }
    }
  }

  fun signalCoverChanged(reason: String) {
    Timber.d("Cover signal: %s", reason)
    _state.update { current ->
      current.copy(coverSignalRevision = current.coverSignalRevision + 1L)
    }
  }

  fun signalQueueChanged(reason: String) {
    Timber.d("Queue signal: %s", reason)
    _state.update { current ->
      current.copy(queueSignalRevision = current.queueSignalRevision + 1L)
    }
  }

  fun setError(message: String?) {
    if (message != null) {
      Timber.e("Bridge error: %s", message)
    }
    _state.update { it.copy(lastError = message) }
  }

  private fun addEntry(existing: List<LogEntry>, message: String): List<LogEntry> =
    (listOf(LogEntry.create(message)) + existing).take(20)

  private fun addPowerampEntry(existing: List<LogEntry>, message: String): List<LogEntry> {
    if (message.startsWith("Poweramp action: TPOS_SYNC")) {
      return existing
    }

    if (message.startsWith("Position sync (TPOS_SYNC):")) {
      val latest = existing.firstOrNull()
      val lastPos = message.substringAfterLast(':').trim()
      if (latest != null && latest.message.startsWith("TPOS_SYNC x ")) {
        val currentCount = latest.message
          .substringAfter("TPOS_SYNC x ")
          .substringBefore(',')
          .toIntOrNull()
          ?: 1
        val replacement = latest.copy(message = "TPOS_SYNC x ${currentCount + 1}, last pos=$lastPos")
        return listOf(replacement) + existing.drop(1)
      }

      return addEntry(existing, "TPOS_SYNC x 1, last pos=$lastPos")
    }

    return addEntry(existing, message)
  }
}
