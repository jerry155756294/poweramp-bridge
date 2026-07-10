package com.jerry155756294.powerampbridge.bridge

import com.jerry155756294.powerampbridge.protocol.LogicalClientSnapshot
import com.jerry155756294.powerampbridge.protocol.SocketRole
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TrackSnapshot(
  val realId: Long = 0L,
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

data class SenderPlaybackOverride(
  val state: String,
  val reason: String,
  val expiresAtElapsedRealtimeMs: Long
)

data class LogEntry(
  val timestamp: String,
  val message: String
) {
  companion object {
    fun create(message: String): LogEntry = LogEntry(
      timestamp = timestampNow(),
      message = message
    )

    fun timestampNow(): String =
      SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
  }
}

data class ConnectionEventSnapshot(
  val socketId: String,
  val remoteAddress: String,
  val clientId: String?,
  val role: SocketRole?,
  val handshakeState: String,
  val broadcastInitialized: Boolean,
  val requestSocketCount: Int,
  val disconnectCategory: String? = null,
  val lastIncomingContext: String? = null,
  val lastOutgoingContext: String? = null
)

data class LatencySummary(
  val lastCommand: String? = null,
  val lastDispatchMs: Long? = null,
  val lastObservedMs: Long? = null,
  val lastEffectStatus: String? = null,
  val averageMs: Long? = null,
  val maxMs: Long? = null,
  val sampleCount: Int = 0,
  val totalMs: Long = 0L
)

enum class CoverStateStatus {
  LOADING,
  READY,
  MISSING,
  ERROR
}

data class CoverSnapshot(
  val realId: Long = 0L,
  val status: CoverStateStatus = CoverStateStatus.MISSING,
  val base64: String? = null,
  val elapsedMs: Long? = null,
  val detailReason: String? = null,
  val detailUri: String? = null,
  val detailWidth: Int? = null,
  val detailHeight: Int? = null,
  val detailMime: String? = null,
  val errorMessage: String? = null
) {
  fun statusOnlyCode(): Int =
    if (status == CoverStateStatus.READY && !base64.isNullOrEmpty()) 1 else 404

  fun payload(): Map<String, Any?> =
    if (status == CoverStateStatus.READY && !base64.isNullOrEmpty()) {
      mapOf("status" to 200, "cover" to base64)
    } else {
      mapOf("status" to 404, "cover" to null)
    }

  fun summary(): String = status.name.lowercase()

  fun detail(): String = buildList {
    add("track=${realId.takeIf { it > 0L } ?: "none"}")
    elapsedMs?.let { add("elapsed=${it}ms") }
    detailReason?.takeIf { it.isNotBlank() }?.let { add("reason=$it") }
    if (detailWidth != null || detailHeight != null) {
      add("bounds=${detailWidth ?: "?"}x${detailHeight ?: "?"}")
    }
    detailMime?.takeIf { it.isNotBlank() }?.let { add("mime=$it") }
    detailUri?.takeIf { it.isNotBlank() }?.let { add("uri=$it") }
    errorMessage?.takeIf { it.isNotBlank() }?.let { add("error=$it") }
  }.joinToString(" | ")
}

data class BridgeUiState(
  val serviceRunning: Boolean = false,
  val serviceStopping: Boolean = false,
  val manualStopActive: Boolean = false,
  val serviceStopSummary: String? = null,
  val serviceStopDetail: String? = null,
  val listenerActive: Boolean = false,
  val listenPort: Int = 3000,
  val localAddresses: List<String> = emptyList(),
  val powerampAvailable: Boolean = false,
  val activeClient: String? = null,
  val clientId: String? = null,
  val protocolVersion: Int? = null,
  val broadcastSocketConnected: Boolean = false,
  val broadcastInitialized: Boolean = false,
  val activeRequestSocketCount: Int = 0,
  val requestSocketConnected: Boolean = false,
  val lastProbeAt: String? = null,
  val lastRejectedReason: String? = null,
  val lastDisconnectReason: String? = null,
  val lastDisconnectCategory: String? = null,
  val lastDisconnectSocketRole: String? = null,
  val lastDisconnectHandshakeState: String? = null,
  val lastDisconnectLastCommand: String? = null,
  val lastDisconnectLastReply: String? = null,
  val latencySummary: LatencySummary = LatencySummary(),
  val positionSyncActive: Boolean = false,
  val coverState: CoverSnapshot = CoverSnapshot(),
  val coverSignalRevision: Long = 0L,
  val playback: PlaybackSnapshot = PlaybackSnapshot(),
  val senderPlaybackOverride: SenderPlaybackOverride? = null,
  val recentCommands: List<LogEntry> = emptyList(),
  val recentProtocolEvents: List<LogEntry> = emptyList(),
  val recentPowerampEvents: List<LogEntry> = emptyList(),
  val lastError: String? = null
)

internal fun BridgeUiState.shouldAutoStart(autoStartEnabled: Boolean): Boolean =
  autoStartEnabled && !serviceRunning && !serviceStopping && !manualStopActive

internal fun BridgeUiState.serviceStatusLabel(): String = when {
  serviceStopping -> "Stopping"
  serviceRunning -> "Running"
  manualStopActive -> "Stopped manually"
  else -> "Stopped"
}

internal fun BridgeUiState.withSession(snapshot: LogicalClientSnapshot?): BridgeUiState =
  copy(
    activeClient = snapshot?.remoteAddress,
    clientId = snapshot?.clientId,
    protocolVersion = snapshot?.protocolVersion,
    broadcastSocketConnected = snapshot?.broadcastSocketConnected ?: false,
    broadcastInitialized = snapshot?.broadcastInitialized ?: false,
    activeRequestSocketCount = snapshot?.requestSocketCount ?: 0,
    requestSocketConnected = snapshot?.requestSocketConnected ?: false
  )

internal fun ConnectionEventSnapshot.format(reason: String): String = buildList {
  add("${LogEntry.timestampNow()} ($remoteAddress): $reason")
  add("socket=$socketId")
  role?.let { add("role=${it.name.lowercase()}") }
  clientId?.takeIf { it.isNotBlank() }?.let { add("client_id=$it") }
  add("handshake=$handshakeState")
  add("broadcast_initialized=$broadcastInitialized")
  add("request_count=$requestSocketCount")
  disconnectCategory?.let { add("category=$it") }
  lastIncomingContext?.let { add("last_in=$it") }
  lastOutgoingContext?.let { add("last_out=$it") }
}.joinToString(" | ")
