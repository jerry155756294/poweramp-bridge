package com.jerry155756294.powerampbridge.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Bundle
import android.util.Base64
import androidx.core.content.ContextCompat
import com.maxmpz.poweramp.player.PowerampAPI
import com.maxmpz.poweramp.player.PowerampAPIHelper
import kotlin.math.ceil
import timber.log.Timber

class PowerampGateway(
  private val context: Context,
  private val stateRepository: BridgeStateRepository
) {
  private val audioManager =
    context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

  private var registered = false
  private var cachedCover: CachedCover? = null

  private val receiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      val actionLabel = PowerampBroadcastDiagnostics.actionLabel(intent.action)
      val extrasSummary = PowerampBroadcastDiagnostics.describeExtras(intent.extras)
      stateRepository.recordPowerampEvent(
        "Poweramp action: $actionLabel extras=$extrasSummary"
      )
      runCatching {
        when (intent.action) {
          PowerampAPI.ACTION_TRACK_CHANGED -> handleTrack(intent, actionLabel)
          PowerampAPI.ACTION_TRACK_CHANGED_EXPLICIT -> handleTrack(intent, actionLabel)
          PowerampAPI.ACTION_STATUS_CHANGED -> handleStatus(intent, actionLabel)
          PowerampAPI.ACTION_STATUS_CHANGED_EXPLICIT -> handleStatus(intent, actionLabel)
          PowerampAPI.ACTION_PLAYING_MODE_CHANGED -> handlePlayingMode(intent, actionLabel)
          PowerampAPI.ACTION_TRACK_POS_SYNC -> handlePosition(intent, actionLabel)
          PowerampAPI.ACTION_AA_CHANGED -> handleAaChanged(actionLabel)
        }
      }.onFailure { error ->
        Timber.e(error, "Failed handling Poweramp broadcast: %s", intent.action)
        stateRepository.setError(
          "Poweramp broadcast failed: ${error.message ?: error.javaClass.simpleName}"
        )
        stateRepository.recordPowerampEvent(
          "Broadcast failure on $actionLabel extras=$extrasSummary ${error.javaClass.simpleName}: ${error.message ?: "no-message"}"
        )
      }
    }
  }

  fun start() {
    if (registered) return
    refreshAvailability()
    val filter = IntentFilter().apply {
      addAction(PowerampAPI.ACTION_TRACK_CHANGED)
      addAction(PowerampAPI.ACTION_TRACK_CHANGED_EXPLICIT)
      addAction(PowerampAPI.ACTION_STATUS_CHANGED)
      addAction(PowerampAPI.ACTION_STATUS_CHANGED_EXPLICIT)
      addAction(PowerampAPI.ACTION_PLAYING_MODE_CHANGED)
      addAction(PowerampAPI.ACTION_TRACK_POS_SYNC)
      addAction(PowerampAPI.ACTION_AA_CHANGED)
    }
    ContextCompat.registerReceiver(
      context,
      receiver,
      filter,
      ContextCompat.RECEIVER_EXPORTED
    )
    registered = true
    updateVolumeSnapshot()
    requestPositionSync()
  }

  fun stop() {
    if (!registered) return
    runCatching { context.unregisterReceiver(receiver) }
    registered = false
  }

  fun refreshAvailability(): Boolean {
    val powerampPackage = PowerampAPIHelper.getPowerampPackageName(context)
    val available = powerampPackage != null && runCatching {
      context.packageManager.getPackageInfo(powerampPackage, 0)
    }.isSuccess
    stateRepository.setPowerampAvailable(available)
    return available
  }

  fun requestPositionSync() {
    sendCommand(PowerampAPI.Commands.POS_SYNC)
  }

  fun refreshVolumeSnapshot() {
    updateVolumeSnapshot()
  }

  fun currentCoverStatus(): Int =
    if (currentCoverBase64() != null) READY_COVER_STATUS else NOT_FOUND_COVER_STATUS

  fun currentCoverPayload(): Map<String, Any?> {
    val cover = currentCoverBase64()
    return if (cover != null) {
      mapOf("status" to SUCCESS_COVER_STATUS, "cover" to cover)
    } else {
      mapOf("status" to NOT_FOUND_COVER_STATUS, "cover" to null)
    }
  }

  fun playPause(): Boolean = sendCommand(PowerampAPI.Commands.TOGGLE_PLAY_PAUSE)
  fun play(): Boolean = sendCommand(PowerampAPI.Commands.PLAY)
  fun pause(): Boolean = sendCommand(PowerampAPI.Commands.PAUSE)
  fun stopPlayback(): Boolean = sendCommand(PowerampAPI.Commands.STOP)
  fun next(): Boolean = sendCommand(PowerampAPI.Commands.NEXT)
  fun previous(): Boolean = sendCommand(PowerampAPI.Commands.PREVIOUS)

  fun seekTo(positionMs: Long): Boolean {
    val clampedMs = positionMs.coerceAtLeast(0L)
    val boundedMs = stateRepository.state.value.playback.track.durationMs
      .takeIf { it > 0L }
      ?.let { clampedMs.coerceAtMost(it) }
      ?: clampedMs
    val positionSeconds = ceil(boundedMs / 1000.0).toLong()
      .coerceIn(0L, Int.MAX_VALUE.toLong())
      .toInt()
    return sendCommand(PowerampAPI.Commands.SEEK) {
      it.putExtra(PowerampAPI.Track.POSITION, positionSeconds)
    }
  }

  fun setShuffle(mode: String): Boolean {
    val normalized = when (mode.lowercase()) {
      "shuffle", "on" -> "shuffle"
      else -> "off"
    }
    val shuffleMode = when (normalized) {
      "shuffle" -> PowerampAPI.ShuffleMode.SHUFFLE_ALL
      else -> PowerampAPI.ShuffleMode.SHUFFLE_NONE
    }
    val success = sendCommand(PowerampAPI.Commands.SHUFFLE) {
      it.putExtra(PowerampAPI.EXTRA_SHUFFLE, shuffleMode)
    }
    if (success) {
      stateRepository.updatePlayback { it.copy(shuffle = normalized) }
    }
    return success
  }

  fun toggleShuffle(): Boolean {
    val current = stateRepository.state.value.playback.shuffle
    return setShuffle(if (current == "shuffle") "off" else "shuffle")
  }

  fun setRepeat(mode: String): Boolean {
    val normalized = when (mode.lowercase()) {
      "all" -> "all"
      "one" -> "one"
      else -> "none"
    }
    val repeatMode = when (normalized) {
      "all" -> PowerampAPI.RepeatMode.REPEAT_ON
      "one" -> PowerampAPI.RepeatMode.REPEAT_SONG
      else -> PowerampAPI.RepeatMode.REPEAT_NONE
    }
    val success = sendCommand(PowerampAPI.Commands.REPEAT) {
      it.putExtra(PowerampAPI.EXTRA_REPEAT, repeatMode)
    }
    if (success) {
      stateRepository.updatePlayback { it.copy(repeat = normalized) }
    }
    return success
  }

  fun toggleRepeat(): Boolean {
    val current = stateRepository.state.value.playback.repeat
    val next = when (current) {
      "none" -> "all"
      "all" -> "one"
      else -> "none"
    }
    return setRepeat(next)
  }

  fun setVolume(volumePercent: Int) {
    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    val clamped = volumePercent.coerceIn(0, 100)
    val streamValue = ((clamped / 100f) * max).toInt().coerceIn(0, max)
    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, streamValue, 0)
    updateVolumeSnapshot()
  }

  private fun updateVolumeSnapshot() {
    val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    val percent = ((current.toFloat() / max) * 100f).toInt().coerceIn(0, 100)
    stateRepository.updatePlayback { it.copy(volume = percent) }
  }

  private fun handleTrack(intent: Intent, actionLabel: String) {
    val track = intent.getBundleExtra(PowerampAPI.EXTRA_TRACK) ?: return
    val positionSec = PowerampBroadcastDiagnostics.readInt(intent.extras, PowerampAPI.Track.POSITION) ?: -1
    val durationMs = PowerampBroadcastDiagnostics.extractDurationMs(track)
    val realId = PowerampBroadcastDiagnostics.readLong(track, PowerampAPI.Track.REAL_ID) ?: 0L
    invalidateCoverCacheIfNeeded(realId)
    stateRepository.updatePlayback { playback ->
      playback.copy(
        track = playback.track.copy(
          realId = realId,
          title = track.getString(PowerampAPI.Track.TITLE).orEmpty(),
          artist = track.getString(PowerampAPI.Track.ARTIST).orEmpty(),
          album = track.getString(PowerampAPI.Track.ALBUM).orEmpty(),
          path = track.getString(PowerampAPI.Track.PATH).orEmpty(),
          durationMs = durationMs,
          year = bundleString(track, "year"),
          albumArtist = bundleString(track, "albumArtist"),
          genre = bundleString(track, "genre"),
          trackNo = bundleString(track, "trackNo"),
          discNo = bundleString(track, "discNo"),
          sampleRate = bundleString(track, PowerampAPI.Track.SAMPLE_RATE),
          channels = bundleString(track, PowerampAPI.Track.CHANNELS),
          bitrate = bundleString(track, PowerampAPI.Track.BITRATE),
          positionMs = if (positionSec >= 0) {
            normalizePositionMs(positionSec.toLong() * 1000L, durationMs)
          } else {
            playback.track.positionMs
          }
        )
      )
    }
    requestPositionSync()
    stateRepository.recordPowerampEvent(
      "Track changed ($actionLabel): ${track.getString(PowerampAPI.Track.TITLE).orEmpty()}"
    )
  }

  private fun handleStatus(intent: Intent, actionLabel: String) {
    val paused = intent.getBooleanExtra(PowerampAPI.EXTRA_PAUSED, false)
    val stateCode = PowerampBroadcastDiagnostics.readInt(intent.extras, PowerampAPI.EXTRA_STATE)
      ?: PowerampAPI.STATE_NO_STATE
    val state = when (stateCode) {
      PowerampAPI.STATE_PLAYING -> "playing"
      PowerampAPI.STATE_PAUSED -> "paused"
      PowerampAPI.STATE_STOPPED -> "stopped"
      else -> if (paused) "paused" else stateRepository.state.value.playback.state
    }
    val positionSec = PowerampBroadcastDiagnostics.readInt(intent.extras, PowerampAPI.Track.POSITION) ?: -1
    stateRepository.updatePlayback { playback ->
      val durationMs = playback.track.durationMs
      playback.copy(
        state = state,
        track = playback.track.copy(
          positionMs = if (positionSec >= 0) {
            normalizePositionMs(positionSec.toLong() * 1000L, durationMs)
          } else {
            playback.track.positionMs
          }
        )
      )
    }
    if (state == "playing") {
      requestPositionSync()
    }
    updateVolumeSnapshot()
    stateRepository.recordPowerampEvent("Status changed ($actionLabel): $state")
  }

  private fun handlePlayingMode(intent: Intent, actionLabel: String) {
    val repeat = when (PowerampBroadcastDiagnostics.readInt(intent.extras, PowerampAPI.EXTRA_REPEAT) ?: -1) {
      PowerampAPI.RepeatMode.REPEAT_ON -> "all"
      PowerampAPI.RepeatMode.REPEAT_SONG -> "one"
      else -> "none"
    }
    val shuffle = when (PowerampBroadcastDiagnostics.readInt(intent.extras, PowerampAPI.EXTRA_SHUFFLE) ?: -1) {
      PowerampAPI.ShuffleMode.SHUFFLE_ALL -> "shuffle"
      else -> "off"
    }
    stateRepository.updatePlayback { it.copy(repeat = repeat, shuffle = shuffle) }
    stateRepository.recordPowerampEvent(
      "Playing mode changed ($actionLabel): repeat=$repeat shuffle=$shuffle"
    )
  }

  private fun handlePosition(intent: Intent, actionLabel: String) {
    val positionSec = PowerampBroadcastDiagnostics.readInt(intent.extras, PowerampAPI.Track.POSITION) ?: -1
    if (positionSec < 0) return
    stateRepository.updatePlayback { playback ->
      val durationMs = playback.track.durationMs
      playback.copy(
        track = playback.track.copy(
          positionMs = normalizePositionMs(positionSec.toLong() * 1000L, durationMs)
        )
      )
    }
    stateRepository.recordPowerampEvent("Position sync ($actionLabel): ${positionSec}s")
  }

  private fun handleAaChanged(actionLabel: String) {
    invalidateCoverCache()
    stateRepository.recordPowerampEvent("AA changed ($actionLabel): cover cache invalidated")
  }

  private fun sendCommand(command: Int, configure: (Intent) -> Unit = {}): Boolean {
    if (!refreshAvailability()) {
      stateRepository.setError("Poweramp not available")
      return false
    }

    val intent = PowerampAPIHelper.newAPIIntent(context)
      .putExtra(PowerampAPI.EXTRA_COMMAND, command)
    configure(intent)
    val result = PowerampAPIHelper.sendPAIntent(context, intent)

    if (!result) {
      stateRepository.setError("Failed to send Poweramp command $command")
    }
    return result
  }

  private fun bundleString(bundle: Bundle, key: String): String =
    if (bundle.containsKey(key)) bundle.get(key)?.toString().orEmpty() else ""

  private fun normalizePositionMs(positionMs: Long, durationMs: Long): Long {
    val sanitized = positionMs.coerceAtLeast(0L)
    return if (durationMs > 0L) sanitized.coerceAtMost(durationMs) else sanitized
  }

  private fun invalidateCoverCacheIfNeeded(realId: Long) {
    if (cachedCover?.realId != realId) {
      cachedCover = null
    }
  }

  private fun invalidateCoverCache() {
    cachedCover = null
  }

  private fun currentCoverBase64(): String? {
    val realId = stateRepository.state.value.playback.track.realId
    if (realId <= 0L) {
      return null
    }

    cachedCover?.takeIf { it.realId == realId }?.let { return it.base64 }

    val base64 = runCatching {
      val uri = PowerampAPI.AA_ROOT_URI.buildUpon()
        .appendEncodedPath("files")
        .appendEncodedPath(realId.toString())
        .build()
      context.contentResolver.openInputStream(uri)?.use { stream ->
        Base64.encodeToString(stream.readBytes(), Base64.NO_WRAP)
      }
    }.getOrNull()

    cachedCover = CachedCover(realId = realId, base64 = base64)
    return base64
  }

  private data class CachedCover(
    val realId: Long,
    val base64: String?
  )

  private companion object {
    const val READY_COVER_STATUS = 1
    const val SUCCESS_COVER_STATUS = 200
    const val NOT_FOUND_COVER_STATUS = 404
  }
}

internal object PowerampBroadcastDiagnostics {
  fun extractDurationMs(track: Bundle): Long {
    val durationMs = readLong(track, PowerampAPI.Track.DURATION_MS)
    if (durationMs != null && durationMs >= 0L) {
      return durationMs
    }

    val durationSeconds = readLong(track, PowerampAPI.Track.DURATION)
    return durationSeconds
      ?.takeIf { it >= 0L }
      ?.times(1000L)
      ?.coerceAtLeast(0L)
      ?: 0L
  }

  fun readLong(bundle: Bundle?, key: String): Long? {
    if (bundle == null || !bundle.containsKey(key)) {
      return null
    }
    return coerceLong(bundle.get(key))
  }

  fun readInt(bundle: Bundle?, key: String): Int? =
    readLong(bundle, key)
      ?.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }
      ?.toInt()

  fun actionLabel(action: String?): String = when (action) {
    PowerampAPI.ACTION_TRACK_CHANGED -> "TRACK_CHANGED"
    PowerampAPI.ACTION_TRACK_CHANGED_EXPLICIT -> "TRACK_CHANGED_EXPLICIT"
    PowerampAPI.ACTION_STATUS_CHANGED -> "STATUS_CHANGED"
    PowerampAPI.ACTION_STATUS_CHANGED_EXPLICIT -> "STATUS_CHANGED_EXPLICIT"
    PowerampAPI.ACTION_PLAYING_MODE_CHANGED -> "PLAYING_MODE_CHANGED"
    PowerampAPI.ACTION_TRACK_POS_SYNC -> "TPOS_SYNC"
    PowerampAPI.ACTION_AA_CHANGED -> "AA_CHANGED"
    null -> "UNKNOWN"
    else -> action.substringAfterLast('.')
  }

  fun describeExtras(bundle: Bundle?): String {
    if (bundle == null || bundle.isEmpty) {
      return "none"
    }

    val keys = bundle.keySet().toList().sorted()
    val preview = keys.take(6).joinToString(",") { key ->
      "$key:${describeValue(bundle.get(key))}"
    }
    val suffix = if (keys.size > 6) ",+${keys.size - 6} more" else ""
    return "[$preview$suffix]"
  }

  private fun describeValue(value: Any?): String = when (value) {
    null -> "null"
    is Bundle -> "Bundle${describeBundleKeys(value)}"
    else -> value.javaClass.simpleName
  }

  private fun describeBundleKeys(bundle: Bundle): String {
    if (bundle.isEmpty) {
      return "[]"
    }

    val keys = bundle.keySet().toList().sorted()
    val preview = keys.take(6).joinToString(",") { key ->
      "$key:${bundle.get(key)?.javaClass?.simpleName ?: "null"}"
    }
    val suffix = if (keys.size > 6) ",+${keys.size - 6} more" else ""
    return "[$preview$suffix]"
  }

  private fun coerceLong(value: Any?): Long? = when (value) {
    null -> null
    is Long -> value
    is Int -> value.toLong()
    is Short -> value.toLong()
    is Byte -> value.toLong()
    is String -> value.trim().toLongOrNull()
    is Number -> value.toLong()
    else -> null
  }
}
