package com.jerry155756294.powerampbridge.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import androidx.core.content.ContextCompat
import com.maxmpz.poweramp.player.PowerampAPI
import com.maxmpz.poweramp.player.PowerampAPIHelper
import com.maxmpz.poweramp.player.TableDefs
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.ceil

class PowerampGateway(
  private val context: Context,
  private val stateRepository: BridgeStateRepository
) : PowerampController {
  private val audioManager =
    context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

  private var registered = false
  private var coverLoadJob: Job? = null
  private var coverLoadGeneration: Long = 0L
  private val negativeCoverCache = ConcurrentHashMap<Long, NegativeCoverCacheEntry>()
  private val coverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val receiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      val actionLabel = PowerampBroadcastDiagnostics.actionLabel(intent.action)
      val extrasSummary = PowerampBroadcastDiagnostics.describeExtras(intent.extras)
      stateRepository.recordPowerampEvent(
        "Poweramp action: $actionLabel extras=$extrasSummary"
      )
      runCatching {
        when (intent.action) {
          ACTION_VOLUME_CHANGED -> handleVolumeChanged(intent)
          PowerampAPI.ACTION_TRACK_CHANGED -> handleTrack(intent, actionLabel)
          PowerampAPI.ACTION_TRACK_CHANGED_EXPLICIT -> handleTrack(intent, actionLabel)
          PowerampAPI.ACTION_STATUS_CHANGED -> handleStatus(intent, actionLabel)
          PowerampAPI.ACTION_STATUS_CHANGED_EXPLICIT -> handleStatus(intent, actionLabel)
          PowerampAPI.ACTION_PLAYING_MODE_CHANGED -> handlePlayingMode(intent, actionLabel)
          PowerampAPI.ACTION_TRACK_POS_SYNC -> handlePosition(intent, actionLabel)
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
      addAction(ACTION_VOLUME_CHANGED)
      addAction(PowerampAPI.ACTION_TRACK_CHANGED)
      addAction(PowerampAPI.ACTION_TRACK_CHANGED_EXPLICIT)
      addAction(PowerampAPI.ACTION_STATUS_CHANGED)
      addAction(PowerampAPI.ACTION_STATUS_CHANGED_EXPLICIT)
      addAction(PowerampAPI.ACTION_PLAYING_MODE_CHANGED)
      addAction(PowerampAPI.ACTION_TRACK_POS_SYNC)
    }
    ContextCompat.registerReceiver(
      context,
      receiver,
      filter,
      ContextCompat.RECEIVER_EXPORTED
    )
    registered = true
    updateVolumeSnapshot()
  }

  fun stop() {
    if (!registered) return
    runCatching { context.unregisterReceiver(receiver) }
    registered = false
    coverLoadJob?.cancel()
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

  override fun refreshVolumeSnapshot() {
    updateVolumeSnapshot()
  }

  override fun currentCoverStatus(): Int {
    val realId = stateRepository.state.value.playback.track.realId
    if (realId <= 0L) {
      transitionCoverState(
        CoverSnapshot(status = CoverStateStatus.MISSING),
        "Cover state: missing track=none reason=no_active_track"
      )
      return stateRepository.state.value.coverState.statusOnlyCode()
    }
    ensureCoverLoading(realId)
    return stateRepository.state.value.coverState.statusOnlyCode()
  }

  override fun currentCoverPayload(): Map<String, Any?> {
    val realId = stateRepository.state.value.playback.track.realId
    if (realId <= 0L) {
      transitionCoverState(
        CoverSnapshot(status = CoverStateStatus.MISSING),
        "Cover state: missing track=none reason=no_active_track"
      )
      stateRepository.recordPowerampEvent("Cover reply: missing track=none")
      return stateRepository.state.value.coverState.payload()
    }

    ensureCoverLoading(realId)
    val coverState = stateRepository.state.value.coverState
    stateRepository.recordPowerampEvent(
      "Cover reply: ${coverState.summary()} track=${realId}"
    )
    return coverState.payload()
  }

  override fun currentLyricsPayload(): Map<String, Any> {
    val track = stateRepository.state.value.playback.track
    val lyrics = readCurrentLyrics(track.realId, track.path)
    val status = if (lyrics.isBlank()) 404 else 200
    stateRepository.recordPowerampEvent(
      "Lyrics reply: status=$status source=${if (lyrics.isBlank()) "none" else "available"} track=${track.realId}"
    )
    return mapOf("status" to status, "lyrics" to lyrics)
  }

  override fun readQueueItems(): List<PowerampQueueItem> {
    if (!refreshAvailability()) {
      return emptyList()
    }

    val uri = PowerampAPI.ROOT_URI.buildUpon()
      .appendEncodedPath("queue")
      .build()
    return runCatching {
      context.contentResolver.query(
        uri,
        arrayOf(
          "${TableDefs.Queue._ID} AS queue_id",
          "${TableDefs.Queue.FOLDER_FILE_ID} AS file_id",
          "${TableDefs.Files.TITLE_TAG} AS title_tag",
          "${TableDefs.Files.ARTIST_TAG} AS artist_tag",
          "${TableDefs.Files.ALBUM_TAG} AS album_tag",
          "${TableDefs.Files.URL} AS item_url",
          "${TableDefs.Files.FULL_PATH} AS item_path"
        ),
        null,
        null,
        null
      )?.use { cursor ->
        buildList {
          while (cursor.moveToNext()) {
            add(
              PowerampQueueItem(
                queueId = cursor.longOrNull("queue_id"),
                fileId = cursor.longOrNull("file_id"),
                title = cursor.stringOrBlank("title_tag"),
                artist = cursor.stringOrBlank("artist_tag"),
                album = cursor.stringOrBlank("album_tag"),
                path = cursor.firstNonBlank("item_url", "item_path")
              )
            )
          }
        }
      } ?: emptyList()
    }.onFailure { error ->
      Timber.w(error, "Failed querying Poweramp queue")
      stateRepository.recordPowerampEvent(
        "Queue query failed: ${error.javaClass.simpleName}:${error.message ?: "no-message"}"
      )
    }.getOrDefault(emptyList())
  }

  override fun readRadioStations(): List<PowerampRadioStation> {
    if (!refreshAvailability()) {
      return emptyList()
    }

    val uri = PowerampAPI.ROOT_URI.buildUpon()
      .appendEncodedPath("streams")
      .build()
    return runCatching {
      context.contentResolver.query(
        uri,
        arrayOf(
          "${TableDefs.Files._ID} AS stream_id",
          "${TableDefs.Files.NAME_WITHOUT_NUMBER} AS station_name",
          "${TableDefs.Files.TITLE_TAG} AS title_tag",
          "${TableDefs.Files.ARTIST_TAG} AS artist_tag",
          "${TableDefs.Files.ALBUM_TAG} AS album_tag",
          "${TableDefs.Files.URL} AS stream_url",
          "${TableDefs.Files.FULL_PATH} AS stream_path"
        ),
        null,
        null,
        null
      )?.use { cursor ->
        buildList {
          while (cursor.moveToNext()) {
            val streamId = cursor.longOrNull("stream_id") ?: continue
            add(
              PowerampRadioStation(
                streamId = streamId,
                name = cursor.firstNonBlank("station_name", "title_tag", "stream_url", "stream_path"),
                url = cursor.firstNonBlank("stream_url", "stream_path"),
                artist = cursor.stringOrBlank("artist_tag"),
                album = cursor.stringOrBlank("album_tag")
              )
            )
          }
        }
      }?.asSequence()
        // Empty URLs cannot be played by MBRC, so don't let them replace its cached list.
        ?.filter { it.url.isNotBlank() }
        ?.distinctBy { it.url.trim().lowercase() }
        ?.sortedBy { it.name.lowercase() }
        ?.toList()
        ?: emptyList()
    }.onFailure { error ->
      Timber.w(error, "Failed querying Poweramp streams")
      stateRepository.recordPowerampEvent(
        "Radio query failed: ${error.javaClass.simpleName}:${error.message ?: "no-message"}"
      )
    }.getOrDefault(emptyList())
  }

  override fun playPause(): Boolean = sendCommand(PowerampAPI.Commands.TOGGLE_PLAY_PAUSE)
  override fun play(): Boolean = sendCommand(PowerampAPI.Commands.PLAY)
  override fun pause(): Boolean = sendCommand(PowerampAPI.Commands.PAUSE)
  override fun stopPlayback(): Boolean = sendCommand(PowerampAPI.Commands.STOP)
  override fun next(): Boolean = sendCommand(PowerampAPI.Commands.NEXT)
  override fun previous(): Boolean = sendCommand(PowerampAPI.Commands.PREVIOUS)

  override fun playQueuePosition(position: Int): Boolean {
    if (position <= 0) {
      return false
    }

    val item = readQueueItems().getOrNull(position - 1) ?: return false
    val uri = when {
      item.queueId != null && item.queueId > 0L -> PowerampAPI.ROOT_URI.buildUpon()
        .appendEncodedPath("queue")
        .appendEncodedPath(item.queueId.toString())
        .build()

      item.fileId != null && item.fileId > 0L -> PowerampAPI.ROOT_URI.buildUpon()
        .appendEncodedPath("files")
        .appendEncodedPath(item.fileId.toString())
        .build()

      else -> parsePlayableUri(item.path)
    } ?: return false

    stateRepository.recordPowerampEvent("Queue play dispatch: position=$position uri=$uri")
    return openToPlay(uri)
  }

  override fun playPath(path: String): Boolean {
    val trimmed = path.trim()
    if (trimmed.isEmpty()) {
      return false
    }

    val matchedStream = readRadioStations().firstOrNull { it.url.equals(trimmed, ignoreCase = true) }
    val uri = if (matchedStream != null) {
      PowerampAPI.ROOT_URI.buildUpon()
        .appendEncodedPath("streams")
        .appendEncodedPath(matchedStream.streamId.toString())
        .build()
    } else {
      parsePlayableUri(trimmed)
    } ?: return false

    stateRepository.recordPowerampEvent(
      "Path play dispatch: source=${if (matchedStream != null) "stream_id" else "direct_uri"} target=$uri"
    )
    return openToPlay(uri)
  }

  override fun handleQueueCommand(
    type: String,
    paths: List<String>,
    playPath: String?
  ): QueueCommandResult {
    val normalizedType = type.trim().lowercase()
    if (paths.isEmpty()) {
      return QueueCommandResult(400, accepted = false, detail = "queue command rejected: empty data")
    }

    return when (normalizedType) {
      "now", "default" -> {
        val target = playPath?.takeIf { requested ->
          paths.any { it.equals(requested, ignoreCase = true) }
        } ?: paths.first()
        val success = this.playPath(target)
        QueueCommandResult(
          code = if (success) 200 else 500,
          accepted = success,
          detail = "queue command type=$normalizedType play=$target count=${paths.size}"
        )
      }

      else -> QueueCommandResult(
        code = 501,
        accepted = false,
        detail = "queue command unsupported: type=$normalizedType count=${paths.size}"
      )
    }
  }

  override fun seekTo(positionMs: Long): Boolean {
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

  override fun setLfmRating(action: String): Boolean {
    val currentRating = stateRepository.state.value.playback.track.rating
    val targetRating = when (action.lowercase()) {
      "love" -> if (currentRating == PowerampAPI.Track.RATING_LIKE) {
        PowerampAPI.Track.RATING_NOT_SET
      } else {
        PowerampAPI.Track.RATING_LIKE
      }
      "ban" -> if (currentRating == PowerampAPI.Track.RATING_UNLIKE) {
        PowerampAPI.Track.RATING_NOT_SET
      } else {
        PowerampAPI.Track.RATING_UNLIKE
      }
      // MBRC only emits toggle when clearing its current Love or Ban state.
      "toggle" -> PowerampAPI.Track.RATING_NOT_SET
      else -> return false
    }
    val success = sendCommand(PowerampAPI.Commands.SET_RATING) {
      it.putExtra(PowerampAPI.EXTRA_RATING, targetRating)
    }
    if (success) {
      stateRepository.updatePlayback { playback ->
        playback.copy(track = playback.track.copy(rating = targetRating))
      }
      stateRepository.recordPowerampEvent(
        "Poweramp rating mapped: action=$action rating=$targetRating"
      )
    }
    return success
  }

  override fun setShuffle(mode: String): Boolean {
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

  override fun toggleShuffle(): Boolean {
    val current = stateRepository.state.value.playback.shuffle
    return setShuffle(if (current == "shuffle") "off" else "shuffle")
  }

  override fun setRepeat(mode: String): Boolean {
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

  override fun toggleRepeat(): Boolean {
    val current = stateRepository.state.value.playback.repeat
    val next = when (current) {
      "none" -> "all"
      "all" -> "one"
      else -> "none"
    }
    return setRepeat(next)
  }

  override fun setVolume(volumePercent: Int) {
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

  private fun handleVolumeChanged(intent: Intent) {
    val streamType = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1)
    if (streamType != AudioManager.STREAM_MUSIC) {
      return
    }
    updateVolumeSnapshot()
    stateRepository.recordPowerampEvent(
      "System music volume changed: ${stateRepository.state.value.playback.volume}%"
    )
  }

  /**
   * Poweramp does not expose one public "read the rendered lyrics" command. Its provider can
   * expose lyrics columns on current files in newer builds, while sidecar LRC and embedded lyrics
   * remain local to the track. Try the provider first, then the same two sources Poweramp uses.
   */
  private fun readCurrentLyrics(realId: Long, path: String): String {
    readProviderLyrics(realId).takeIf { it.isNotBlank() }?.let { return it }
    readSidecarLrc(path).takeIf { it.isNotBlank() }?.let { return it }
    return readEmbeddedLyrics(path)
  }

  private fun readProviderLyrics(realId: Long): String {
    if (realId <= 0L || !refreshAvailability()) return ""
    val uri = PowerampAPI.ROOT_URI.buildUpon()
      .appendEncodedPath("files")
      .appendEncodedPath(realId.toString())
      .build()
    return runCatching {
      context.contentResolver.query(
        uri,
        arrayOf("lyrics_synced", "lyrics"),
        null,
        null,
        null
      )?.use { cursor ->
        if (cursor.moveToFirst()) {
          cursor.firstNonBlank("lyrics_synced", "lyrics")
        } else {
          ""
        }
      }.orEmpty()
    }.onFailure { error ->
      // These optional columns are absent on older Poweramp builds. Sidecar/embedded fallbacks
      // below still make lyrics available without making a normal request look like an error.
      Timber.d(error, "Poweramp provider lyrics unavailable")
    }.getOrDefault("")
  }

  private fun readSidecarLrc(path: String): String {
    val source = localFileForPath(path) ?: return ""
    val baseName = source.name.substringBeforeLast('.', source.name)
    val parent = source.parentFile ?: return ""
    val lrc = File(parent, "$baseName.lrc")
    return runCatching {
      if (lrc.isFile) lrc.readText(Charsets.UTF_8).trim() else ""
    }.onFailure { error -> Timber.d(error, "Unable to read sidecar LRC") }
      .getOrDefault("")
  }

  private fun readEmbeddedLyrics(path: String): String {
    val source = localFileForPath(path) ?: return ""
    return runCatching {
      val retriever = MediaMetadataRetriever()
      try {
        retriever.setDataSource(source.absolutePath)
        // METADATA_KEY_LYRIC is not available on every Android API stub, but its platform key
        // is stable and MediaMetadataRetriever simply returns null when the container has none.
        retriever.extractMetadata(METADATA_KEY_LYRIC).orEmpty().trim()
      } finally {
        retriever.release()
      }
    }.onFailure { error -> Timber.d(error, "Unable to read embedded lyrics") }
      .getOrDefault("")
  }

  private fun localFileForPath(path: String): File? {
    val uri = Uri.parse(path)
    val file = when {
      path.startsWith("/") -> File(path)
      uri.scheme.equals("file", ignoreCase = true) -> uri.path?.let(::File)
      else -> null
    }
    return file?.takeIf(File::isFile)
  }

  private fun handleTrack(intent: Intent, actionLabel: String) {
    val track = intent.getBundleExtra(PowerampAPI.EXTRA_TRACK) ?: return
    val positionSec = PowerampBroadcastDiagnostics.readInt(intent.extras, PowerampAPI.Track.POSITION) ?: -1
    val durationMs = PowerampBroadcastDiagnostics.extractDurationMs(track)
    val realId = PowerampBroadcastDiagnostics.readLong(track, PowerampAPI.Track.REAL_ID) ?: 0L
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
          rating = PowerampBroadcastDiagnostics.readInt(track, PowerampAPI.Track.RATING)
            ?: playback.track.rating,
          positionMs = if (positionSec >= 0) {
            normalizePositionMs(positionSec.toLong() * 1000L, durationMs)
          } else {
            playback.track.positionMs
          }
        )
      )
    }
    if (stateRepository.state.value.coverState.realId != realId) {
      resetCoverStateForTrack(realId)
    }
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
    updateVolumeSnapshot()
    stateRepository.recordPowerampEvent(
      "poweramp_status_changed:$state source=$actionLabel state_code=$stateCode paused=$paused"
    )
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

  private fun sendCommand(command: Int, configure: (Intent) -> Unit = {}): Boolean {
    if (!refreshAvailability()) {
      stateRepository.setError("Poweramp not available")
      return false
    }

    val intent = PowerampAPIHelper.newAPIIntent(context)
      .putExtra(PowerampAPI.EXTRA_COMMAND, command)
    configure(intent)
    val result = PowerampAPIHelper.sendPAIntent(context, intent)

    if (result) {
      stateRepository.recordPowerampEvent("api_command_sent:${commandLabel(command)}")
    } else {
      stateRepository.setError("Failed to send Poweramp command $command")
    }
    return result
  }

  private fun commandLabel(command: Int): String = when (command) {
    PowerampAPI.Commands.TOGGLE_PLAY_PAUSE -> "playpause"
    PowerampAPI.Commands.PLAY -> "play"
    PowerampAPI.Commands.PAUSE -> "pause"
    PowerampAPI.Commands.STOP -> "stop"
    PowerampAPI.Commands.NEXT -> "next"
    PowerampAPI.Commands.PREVIOUS -> "previous"
    PowerampAPI.Commands.SET_RATING -> "set_rating"
    else -> command.toString()
  }

  private fun openToPlay(uri: Uri): Boolean =
    sendCommand(PowerampAPI.Commands.OPEN_TO_PLAY) { intent ->
      intent.data = uri
    }

  private fun bundleString(bundle: Bundle, key: String): String =
    if (bundle.containsKey(key)) bundle.get(key)?.toString().orEmpty() else ""

  private fun parsePlayableUri(path: String): Uri? = when {
    path.startsWith("http://", ignoreCase = true) ||
      path.startsWith("https://", ignoreCase = true) ||
      path.startsWith("content://", ignoreCase = true) ||
      path.startsWith("file://", ignoreCase = true) -> Uri.parse(path)

    path.startsWith("/") -> Uri.fromFile(File(path))
    else -> Uri.parse(path)
  }

  private fun normalizePositionMs(positionMs: Long, durationMs: Long): Long {
    val sanitized = positionMs.coerceAtLeast(0L)
    return if (durationMs > 0L) sanitized.coerceAtMost(durationMs) else sanitized
  }

  private fun invalidateCoverCacheIfNeeded(realId: Long) {
    if (stateRepository.state.value.coverState.realId != realId) {
      coverLoadJob?.cancel()
      coverLoadGeneration += 1L
    }
    val activeTrackId = stateRepository.state.value.coverState.realId
    if (activeTrackId > 0L && activeTrackId != realId) {
      negativeCoverCache.remove(activeTrackId)
    }
  }

  private fun invalidateCoverCache() {
    coverLoadJob?.cancel()
    coverLoadGeneration += 1L
    negativeCoverCache.clear()
    transitionCoverState(CoverSnapshot(status = CoverStateStatus.MISSING))
  }

  private fun ensureCoverLoading(realId: Long) {
    if (realId <= 0L) return
    val current = stateRepository.state.value.coverState
    if (current.realId == realId &&
      current.status == CoverStateStatus.READY &&
      !current.base64.isNullOrEmpty()
    ) {
      return
    }
    if (current.realId == realId &&
      current.status == CoverStateStatus.MISSING
    ) {
      negativeCoverCache[realId]?.takeIf { it.expiresAtMs > SystemClock.elapsedRealtime() }?.let {
        return
      }
    }
    if (current.realId == realId &&
      current.status == CoverStateStatus.ERROR
    ) {
      return
    }
    if (current.realId == realId &&
      current.status == CoverStateStatus.LOADING &&
      coverLoadJob?.isActive == true
    ) {
      return
    }

    coverLoadJob?.cancel()
    coverLoadGeneration += 1L
    val generation = coverLoadGeneration
    transitionCoverState(
      CoverSnapshot(realId = realId, status = CoverStateStatus.LOADING),
      "Cover state: loading track=$realId"
    )
    coverLoadJob = coverScope.launch {
      val startedAt = SystemClock.elapsedRealtime()
      val result = loadCoverResult(realId)
      val elapsedMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
      if (generation != coverLoadGeneration ||
        stateRepository.state.value.playback.track.realId != realId
      ) {
        return@launch
      }

      val nextState = when (result) {
        is CoverLoadResult.Ready -> CoverSnapshot(
          realId = realId,
          status = CoverStateStatus.READY,
          base64 = result.base64,
          elapsedMs = elapsedMs
        )
        is CoverLoadResult.Missing -> CoverSnapshot(
          realId = realId,
          status = CoverStateStatus.MISSING,
          elapsedMs = elapsedMs,
          detailReason = result.reason.name.lowercase(),
          detailUri = result.uri.toString(),
          detailWidth = result.width,
          detailHeight = result.height,
          detailMime = result.mime
        )
        is CoverLoadResult.Error -> CoverSnapshot(
          realId = realId,
          status = CoverStateStatus.ERROR,
          elapsedMs = elapsedMs,
          detailUri = result.uri.toString(),
          errorMessage = result.message
        )
      }

      val event = when (result) {
        is CoverLoadResult.Ready -> "Cover state: ready track=$realId elapsed=${elapsedMs}ms"
        is CoverLoadResult.Missing ->
          "Cover state: missing track=$realId reason=${result.reason.name.lowercase()} uri=${result.uri} elapsed=${elapsedMs}ms bounds=${result.width ?: "?"}x${result.height ?: "?"} mime=${result.mime ?: "unknown"}"
        is CoverLoadResult.Error ->
          "Cover state: error track=$realId elapsed=${elapsedMs}ms error=${result.message}"
      }
      transitionCoverState(nextState, event)
    }
  }

  private fun loadCoverResult(realId: Long): CoverLoadResult {
    val now = SystemClock.elapsedRealtime()
    negativeCoverCache[realId]?.let { cached ->
      if (cached.expiresAtMs > now) {
        stateRepository.recordPowerampEvent(
          "Cover state: missing_cached track=$realId reason=${cached.reason.name.lowercase()} uri=${cached.uri}"
        )
        return CoverLoadResult.Missing(
          reason = cached.reason,
          uri = cached.uri,
          width = cached.width,
          height = cached.height,
          mime = cached.mime
        )
      }
      negativeCoverCache.remove(realId)
    }

    val uri = PowerampAPI.AA_ROOT_URI.buildUpon()
      .appendEncodedPath("files")
      .appendEncodedPath(realId.toString())
      .build()
    val result = loadScaledCoverBase64Detailed(uri)
    when (result) {
      is CoverLoadResult.Ready -> negativeCoverCache.remove(realId)
      is CoverLoadResult.Missing -> {
        negativeCoverCache[realId] = NegativeCoverCacheEntry(
          reason = result.reason,
          uri = result.uri,
          expiresAtMs = now + NEGATIVE_COVER_CACHE_MS,
          width = result.width,
          height = result.height,
          mime = result.mime
        )
      }
      is CoverLoadResult.Error -> negativeCoverCache.remove(realId)
    }
    return result
  }

  private fun resetCoverStateForTrack(realId: Long) {
    invalidateCoverCacheIfNeeded(realId)
    if (realId <= 0L) {
      transitionCoverState(
        CoverSnapshot(status = CoverStateStatus.MISSING),
        "Cover state: missing track=none reason=no_active_track"
      )
      return
    }

    transitionCoverState(
      CoverSnapshot(realId = realId, status = CoverStateStatus.LOADING),
      "Cover state: loading track=$realId trigger=track_changed"
    )
    ensureCoverLoading(realId)
  }

  private fun transitionCoverState(next: CoverSnapshot, event: String? = null) {
    val current = stateRepository.state.value.coverState
    if (current == next) {
      return
    }
    event?.let(stateRepository::recordPowerampEvent)
    stateRepository.updateCoverState(next)
  }

  private fun loadScaledCoverBase64Detailed(uri: Uri): CoverLoadResult {
    val startedAt = SystemClock.elapsedRealtime()
    return runCatching {
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      val fdForBounds = context.contentResolver.openFileDescriptor(uri, "r")
        ?: return CoverLoadResult.Missing(
          reason = CoverMissingReason.FD_NULL,
          uri = uri
        )

      fdForBounds.use { descriptor ->
        BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor, null, bounds)
      }

      if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        return CoverLoadResult.Missing(
          reason = CoverMissingReason.BOUNDS_INVALID,
          uri = uri,
          width = bounds.outWidth,
          height = bounds.outHeight,
          mime = bounds.outMimeType
        )
      }

      val sampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, MAX_COVER_DIMENSION)
      val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
      val fdForBitmap = context.contentResolver.openFileDescriptor(uri, "r")
        ?: return CoverLoadResult.Missing(
          reason = CoverMissingReason.FD_NULL,
          uri = uri,
          width = bounds.outWidth,
          height = bounds.outHeight,
          mime = bounds.outMimeType
        )

      val bitmap = fdForBitmap.use { descriptor ->
        BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor, null, decodeOptions)
      } ?: return CoverLoadResult.Missing(
        reason = CoverMissingReason.BITMAP_DECODE_NULL,
        uri = uri,
        width = bounds.outWidth,
        height = bounds.outHeight,
        mime = bounds.outMimeType
      )

      val base64 = bitmap.useScaledBase64()
      if (base64.isBlank()) {
        CoverLoadResult.Missing(
          reason = CoverMissingReason.ENCODE_EMPTY,
          uri = uri,
          width = bounds.outWidth,
          height = bounds.outHeight,
          mime = bounds.outMimeType
        )
      } else {
        CoverLoadResult.Ready(base64)
      }
    }.getOrElse { error ->
      Timber.w(error, "Failed to load cover for uri %s", uri)
      CoverLoadResult.Error(
        uri = uri,
        message = "${error.javaClass.simpleName}:${error.message ?: "no-message"}"
      )
    }.withElapsed((SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L))
  }

  private fun Bitmap.useScaledBase64(): String {
    val scaled = scaleBitmapIfNeeded(this, MAX_COVER_DIMENSION)
    return try {
      ByteArrayOutputStream().use { output ->
        scaled.compress(Bitmap.CompressFormat.JPEG, COVER_JPEG_QUALITY, output)
        Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
      }
    } finally {
      if (scaled !== this) {
        scaled.recycle()
      }
      recycle()
    }
  }

  private fun scaleBitmapIfNeeded(bitmap: Bitmap, maxDimension: Int): Bitmap {
    val width = bitmap.width
    val height = bitmap.height
    if (width <= maxDimension && height <= maxDimension) {
      return bitmap
    }

    val scale = minOf(maxDimension.toFloat() / width, maxDimension.toFloat() / height)
    val targetWidth = (width * scale).toInt().coerceAtLeast(1)
    val targetHeight = (height * scale).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
  }

  private fun calculateSampleSize(width: Int, height: Int, maxDimension: Int): Int {
    var sampleSize = 1
    var nextWidth = width
    var nextHeight = height
    while (nextWidth > maxDimension || nextHeight > maxDimension) {
      sampleSize *= 2
      nextWidth /= 2
      nextHeight /= 2
    }
    return sampleSize.coerceAtLeast(1)
  }

  private enum class CoverMissingReason {
    FD_NULL,
    BOUNDS_INVALID,
    BITMAP_DECODE_NULL,
    ENCODE_EMPTY
  }

  private data class NegativeCoverCacheEntry(
    val reason: CoverMissingReason,
    val uri: Uri,
    val expiresAtMs: Long,
    val width: Int? = null,
    val height: Int? = null,
    val mime: String? = null
  )

  private sealed interface CoverLoadResult {
    data class Ready(
      val base64: String,
      val elapsedMs: Long = 0L
    ) : CoverLoadResult

    data class Missing(
      val reason: CoverMissingReason,
      val uri: Uri,
      val elapsedMs: Long = 0L,
      val width: Int? = null,
      val height: Int? = null,
      val mime: String? = null
    ) : CoverLoadResult

    data class Error(
      val uri: Uri,
      val message: String,
      val elapsedMs: Long = 0L
    ) : CoverLoadResult
  }

  private fun CoverLoadResult.withElapsed(elapsedMs: Long): CoverLoadResult =
    when (this) {
      is CoverLoadResult.Ready -> copy(elapsedMs = elapsedMs)
      is CoverLoadResult.Missing -> copy(elapsedMs = elapsedMs)
      is CoverLoadResult.Error -> copy(elapsedMs = elapsedMs)
    }

  private companion object {
    // These framework broadcasts/extras are @hide, but are stable Android platform contracts.
    const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
    const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
    // android.media.MediaMetadataRetriever.METADATA_KEY_LYRIC
    const val METADATA_KEY_LYRIC = 1000
    const val NEGATIVE_COVER_CACHE_MS = 30_000L
    const val MAX_COVER_DIMENSION = 1024
    const val COVER_JPEG_QUALITY = 95
  }
}

private fun Cursor.stringOrBlank(columnName: String): String =
  stringOrNull(columnName).orEmpty()

private fun Cursor.stringOrNull(columnName: String): String? {
  val index = getColumnIndex(columnName)
  if (index < 0 || isNull(index)) {
    return null
  }
  return getString(index)
}

private fun Cursor.longOrNull(columnName: String): Long? {
  val index = getColumnIndex(columnName)
  if (index < 0 || isNull(index)) {
    return null
  }
  return getLong(index)
}

private fun Cursor.firstNonBlank(vararg columnNames: String): String {
  columnNames.forEach { columnName ->
    stringOrNull(columnName)
      ?.trim()
      ?.takeIf { it.isNotEmpty() }
      ?.let { return it }
  }
  return ""
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
