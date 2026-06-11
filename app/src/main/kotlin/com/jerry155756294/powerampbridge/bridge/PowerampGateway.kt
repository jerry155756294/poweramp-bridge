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
      when (intent.action) {
        PowerampAPI.ACTION_TRACK_CHANGED -> handleTrack(intent)
        PowerampAPI.ACTION_TRACK_CHANGED_EXPLICIT -> handleTrack(intent)
        PowerampAPI.ACTION_STATUS_CHANGED -> handleStatus(intent)
        PowerampAPI.ACTION_STATUS_CHANGED_EXPLICIT -> handleStatus(intent)
        PowerampAPI.ACTION_PLAYING_MODE_CHANGED -> handlePlayingMode(intent)
        PowerampAPI.ACTION_TRACK_POS_SYNC -> handlePosition(intent)
        PowerampAPI.ACTION_AA_CHANGED -> invalidateCoverCache()
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

  fun seekTo(positionSeconds: Int): Boolean =
    sendCommand(PowerampAPI.Commands.SEEK) {
      it.putExtra(PowerampAPI.Track.POSITION, positionSeconds)
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

  private fun handleTrack(intent: Intent) {
    val track = intent.getBundleExtra(PowerampAPI.EXTRA_TRACK) ?: return
    val positionSec = intent.getIntExtra(PowerampAPI.Track.POSITION, -1)
    val realId = track.getLong(PowerampAPI.Track.REAL_ID, 0L)
    invalidateCoverCacheIfNeeded(realId)
    stateRepository.updatePlayback { playback ->
      playback.copy(
        track = playback.track.copy(
          realId = realId,
          title = track.getString(PowerampAPI.Track.TITLE).orEmpty(),
          artist = track.getString(PowerampAPI.Track.ARTIST).orEmpty(),
          album = track.getString(PowerampAPI.Track.ALBUM).orEmpty(),
          path = track.getString(PowerampAPI.Track.PATH).orEmpty(),
          durationMs = track.getInt(PowerampAPI.Track.DURATION, 0).toLong() * 1000L,
          year = bundleString(track, "year"),
          albumArtist = bundleString(track, "albumArtist"),
          genre = bundleString(track, "genre"),
          trackNo = bundleString(track, "trackNo"),
          discNo = bundleString(track, "discNo"),
          sampleRate = bundleString(track, PowerampAPI.Track.SAMPLE_RATE),
          channels = bundleString(track, PowerampAPI.Track.CHANNELS),
          bitrate = bundleString(track, PowerampAPI.Track.BITRATE),
          positionMs = if (positionSec >= 0) positionSec * 1000L else playback.track.positionMs
        )
      )
    }
    requestPositionSync()
    stateRepository.recordPowerampEvent(
      "Track changed: ${track.getString(PowerampAPI.Track.TITLE).orEmpty()}"
    )
  }

  private fun handleStatus(intent: Intent) {
    val paused = intent.getBooleanExtra(PowerampAPI.EXTRA_PAUSED, false)
    val state = when (intent.getIntExtra(PowerampAPI.EXTRA_STATE, PowerampAPI.STATE_NO_STATE)) {
      PowerampAPI.STATE_PLAYING -> "playing"
      PowerampAPI.STATE_PAUSED -> "paused"
      PowerampAPI.STATE_STOPPED -> "stopped"
      else -> if (paused) "paused" else stateRepository.state.value.playback.state
    }
    val positionSec = intent.getIntExtra(PowerampAPI.Track.POSITION, -1)
    stateRepository.updatePlayback { playback ->
      playback.copy(
        state = state,
        track = playback.track.copy(
          positionMs = if (positionSec >= 0) positionSec * 1000L else playback.track.positionMs
        )
      )
    }
    if (state == "playing") {
      requestPositionSync()
    }
    updateVolumeSnapshot()
    stateRepository.recordPowerampEvent("Status changed: $state")
  }

  private fun handlePlayingMode(intent: Intent) {
    val repeat = when (intent.getIntExtra(PowerampAPI.EXTRA_REPEAT, -1)) {
      PowerampAPI.RepeatMode.REPEAT_ON -> "all"
      PowerampAPI.RepeatMode.REPEAT_SONG -> "one"
      else -> "none"
    }
    val shuffle = when (intent.getIntExtra(PowerampAPI.EXTRA_SHUFFLE, -1)) {
      PowerampAPI.ShuffleMode.SHUFFLE_ALL -> "shuffle"
      else -> "off"
    }
    stateRepository.updatePlayback { it.copy(repeat = repeat, shuffle = shuffle) }
    stateRepository.recordPowerampEvent("Playing mode changed: repeat=$repeat shuffle=$shuffle")
  }

  private fun handlePosition(intent: Intent) {
    val positionSec = intent.getIntExtra(PowerampAPI.Track.POSITION, -1)
    if (positionSec < 0) return
    stateRepository.updatePlayback { playback ->
      playback.copy(track = playback.track.copy(positionMs = positionSec * 1000L))
    }
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
