package com.jerry155756294.powerampbridge.bridge

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TrackSnapshot(
  val title: String = "",
  val artist: String = "",
  val album: String = "",
  val albumArtist: String = "",
  val genre: String = "",
  val path: String = "",
  val year: String = "",
  val durationMs: Long = 0L,
  val positionMs: Long = 0L,
  val trackNo: String = "",
  val discNo: String = "",
  val sampleRate: String = "",
  val channels: String = "",
  val bitrate: String = ""
)

data class PlaybackSnapshot(
  val state: String = "stopped",
  val repeat: String = "none",
  val shuffle: String = "off",
  val volume: Int = 0,
  val mute: Boolean = false,
  val powerampAvailable: Boolean = false,
  val track: TrackSnapshot = TrackSnapshot()
)

data class LogEntry(
  val timestamp: String,
  val message: String
) {
  companion object {
    fun create(message: String): LogEntry = LogEntry(
      timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()),
      message = message
    )
  }
}

data class BridgeUiState(
  val serviceRunning: Boolean = false,
  val listenerActive: Boolean = false,
  val listenPort: Int = 3000,
  val powerampAvailable: Boolean = false,
  val activeClient: String? = null,
  val handshakeComplete: Boolean = false,
  val playback: PlaybackSnapshot = PlaybackSnapshot(),
  val recentCommands: List<LogEntry> = emptyList(),
  val recentPowerampEvents: List<LogEntry> = emptyList(),
  val lastError: String? = null
)
