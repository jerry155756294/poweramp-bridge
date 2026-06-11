package com.jerry155756294.powerampbridge.bridge

import com.jerry155756294.powerampbridge.protocol.LogicalClientSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class BridgeStateRepository {
  private val _state = MutableStateFlow(BridgeUiState())
  val state: StateFlow<BridgeUiState> = _state.asStateFlow()

  fun setServiceRunning(running: Boolean) {
    _state.update { it.copy(serviceRunning = running) }
  }

  fun setListenerState(active: Boolean, port: Int) {
    _state.update { it.copy(listenerActive = active, listenPort = port) }
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

  fun recordRejectedConnection(remoteAddress: String, reason: String) {
    val timestamp = LogEntry.timestampNow()
    _state.update {
      it.copy(lastRejectedReason = "$timestamp ($remoteAddress): $reason")
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

  fun updatePlayback(transform: (PlaybackSnapshot) -> PlaybackSnapshot) {
    _state.update { current ->
      current.copy(playback = transform(current.playback))
    }
  }

  fun recordCommand(message: String) {
    _state.update { it.copy(recentCommands = addEntry(it.recentCommands, message)) }
  }

  fun recordPowerampEvent(message: String) {
    _state.update { it.copy(recentPowerampEvents = addEntry(it.recentPowerampEvents, message)) }
  }

  fun setError(message: String?) {
    _state.update { it.copy(lastError = message) }
  }

  private fun addEntry(existing: List<LogEntry>, message: String): List<LogEntry> =
    (listOf(LogEntry.create(message)) + existing).take(20)
}
