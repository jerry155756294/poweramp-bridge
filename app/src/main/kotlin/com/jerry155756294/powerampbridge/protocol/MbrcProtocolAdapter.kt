package com.jerry155756294.powerampbridge.protocol

import com.jerry155756294.powerampbridge.BuildConfig
import com.jerry155756294.powerampbridge.bridge.BridgeStateRepository
import com.jerry155756294.powerampbridge.bridge.BridgeUiState
import com.jerry155756294.powerampbridge.bridge.PowerampGateway
import com.jerry155756294.powerampbridge.data.BridgeSettings
import org.json.JSONObject

class MbrcProtocolAdapter(
  private val codec: JsonMessageCodec,
  private val stateRepository: BridgeStateRepository,
  private val powerampGateway: PowerampGateway
) {
  private var initialized = false

  fun onClientConnected() {
    initialized = false
    stateRepository.setHandshakeComplete(false)
  }

  fun onClientDisconnected() {
    initialized = false
    stateRepository.setHandshakeComplete(false)
  }

  fun canBroadcastState(): Boolean = initialized

  fun handleMessage(message: IncomingMessage, settings: BridgeSettings): List<String> {
    stateRepository.recordCommand("${message.context}: ${message.data ?: ""}".trim())
    return when (message.context) {
      ProtocolConstants.Player ->
        listOf(codec.encode(ProtocolConstants.Player, "Android"))

      ProtocolConstants.Protocol -> {
        stateRepository.setHandshakeComplete(true)
        listOf(codec.encode(ProtocolConstants.Protocol, ProtocolConstants.ProtocolVersion.toString()))
      }

      ProtocolConstants.Init -> {
        initialized = true
        snapshotMessages(stateRepository.state.value)
      }

      ProtocolConstants.VerifyConnection ->
        listOf(codec.encode(ProtocolConstants.VerifyConnection, ""))

      ProtocolConstants.Ping ->
        listOf(codec.encode(ProtocolConstants.Pong, message.data ?: ""))

      ProtocolConstants.PlayerPlayPause ->
        commandResult(powerampGateway.playPause(), ProtocolConstants.PlayerPlayPause)

      ProtocolConstants.PlayerPlay ->
        commandResult(powerampGateway.play(), ProtocolConstants.PlayerPlay)

      ProtocolConstants.PlayerPause ->
        commandResult(powerampGateway.pause(), ProtocolConstants.PlayerPause)

      ProtocolConstants.PlayerStop ->
        commandResult(powerampGateway.stopPlayback(), ProtocolConstants.PlayerStop)

      ProtocolConstants.PlayerNext ->
        commandResult(powerampGateway.next(), ProtocolConstants.PlayerNext)

      ProtocolConstants.PlayerPrevious ->
        commandResult(powerampGateway.previous(), ProtocolConstants.PlayerPrevious)

      ProtocolConstants.PlayerVolume -> {
        val volume = asInt(message.data)
        if (volume != null) {
          powerampGateway.setVolume(volume)
          listOf(codec.encode(ProtocolConstants.PlayerVolume, volume))
        } else {
          commandUnavailable(ProtocolConstants.PlayerVolume)
        }
      }

      ProtocolConstants.NowPlayingPosition -> {
        val seconds = asInt(message.data)
        if (seconds != null) {
          commandResult(powerampGateway.seekTo(seconds), ProtocolConstants.NowPlayingPosition)
        } else {
          listOf(
            codec.encode(
              ProtocolConstants.NowPlayingPosition,
              positionPayload(stateRepository.state.value)
            )
          )
        }
      }

      ProtocolConstants.PlayerShuffle -> {
        val payload = dataAsString(message.data)
        val success = if (payload.isEmpty() || payload == ProtocolConstants.Toggle) {
          powerampGateway.toggleShuffle()
        } else {
          powerampGateway.setShuffle(payload)
        }
        commandResult(success, ProtocolConstants.PlayerShuffle)
      }

      ProtocolConstants.PlayerRepeat -> {
        val payload = dataAsString(message.data)
        val success = if (payload.isEmpty() || payload == ProtocolConstants.Toggle) {
          powerampGateway.toggleRepeat()
        } else {
          powerampGateway.setRepeat(payload)
        }
        commandResult(success, ProtocolConstants.PlayerRepeat)
      }

      ProtocolConstants.PlayerStatus ->
        listOf(codec.encode(ProtocolConstants.PlayerStatus, statusPayload(stateRepository.state.value)))

      ProtocolConstants.PlayerState ->
        listOf(codec.encode(ProtocolConstants.PlayerState, stateRepository.state.value.playback.state))

      ProtocolConstants.NowPlayingTrack ->
        listOf(codec.encode(ProtocolConstants.NowPlayingTrack, trackPayload(stateRepository.state.value)))

      ProtocolConstants.NowPlayingDetails ->
        listOf(codec.encode(ProtocolConstants.NowPlayingDetails, detailsPayload(stateRepository.state.value)))

      ProtocolConstants.PluginVersion ->
        listOf(codec.encode(ProtocolConstants.PluginVersion, pluginVersion()))

      ProtocolConstants.NowPlayingList ->
        listOf(codec.encode(ProtocolConstants.NowPlayingList, nowPlayingListPayload(stateRepository.state.value)))

      ProtocolConstants.NowPlayingQueue ->
        listOf(codec.encode(ProtocolConstants.NowPlayingQueue, mapOf("code" to 404)))

      ProtocolConstants.NowPlayingCover ->
        listOf(codec.encode(ProtocolConstants.NowPlayingCover, mapOf("status" to 404, "cover" to null)))

      ProtocolConstants.NowPlayingLyrics ->
        listOf(codec.encode(ProtocolConstants.NowPlayingLyrics, mapOf("status" to 404, "lyrics" to "")))

      ProtocolConstants.PlaylistList,
      ProtocolConstants.RadioStations,
      ProtocolConstants.BrowseGenres,
      ProtocolConstants.BrowseArtists,
      ProtocolConstants.BrowseAlbums,
      ProtocolConstants.BrowseTracks ->
        listOf(codec.encode(message.context, emptyPage()))

      ProtocolConstants.LibraryCover ->
        listOf(codec.encode(ProtocolConstants.LibraryCover, mapOf("status" to 404, "cover" to null)))

      ProtocolConstants.PlayerOutput,
      ProtocolConstants.PlayerOutputSwitch ->
        listOf(codec.encode(message.context, mapOf("devices" to emptyList<String>(), "active" to "")))

      ProtocolConstants.PlaylistPlay,
      ProtocolConstants.LibraryPlayAll,
      ProtocolConstants.PlayerMute ->
        commandUnavailable(message.context)

      else ->
        listOf(codec.encode(ProtocolConstants.UnknownCommand, message.context))
    }
  }

  fun snapshotMessages(state: BridgeUiState): List<String> = listOf(
    codec.encode(ProtocolConstants.PluginVersion, pluginVersion()),
    codec.encode(ProtocolConstants.PlayerStatus, statusPayload(state)),
    codec.encode(ProtocolConstants.PlayerState, state.playback.state),
    codec.encode(ProtocolConstants.PlayerRepeat, state.playback.repeat),
    codec.encode(ProtocolConstants.PlayerShuffle, state.playback.shuffle),
    codec.encode(ProtocolConstants.PlayerVolume, state.playback.volume),
    codec.encode(ProtocolConstants.NowPlayingTrack, trackPayload(state)),
    codec.encode(ProtocolConstants.NowPlayingPosition, positionPayload(state)),
    codec.encode(ProtocolConstants.NowPlayingDetails, detailsPayload(state))
  )

  private fun pluginVersion(): String = "poweramp-bridge ${BuildConfig.VERSION_NAME}"

  private fun statusPayload(state: BridgeUiState): Map<String, Any> = mapOf(
    ProtocolConstants.PlayerMute to state.playback.mute,
    ProtocolConstants.PlayerState to state.playback.state,
    ProtocolConstants.PlayerRepeat to state.playback.repeat,
    ProtocolConstants.PlayerShuffle to state.playback.shuffle,
    "scrobbler" to false,
    ProtocolConstants.PlayerVolume to state.playback.volume
  )

  private fun trackPayload(state: BridgeUiState): Map<String, String> = mapOf(
    "artist" to state.playback.track.artist,
    "album" to state.playback.track.album,
    "title" to state.playback.track.title,
    "year" to state.playback.track.year,
    "path" to state.playback.track.path
  )

  private fun positionPayload(state: BridgeUiState): Map<String, Long> = mapOf(
    "current" to state.playback.track.positionMs,
    "total" to state.playback.track.durationMs
  )

  private fun nowPlayingListPayload(state: BridgeUiState): Map<String, Any> {
    val track = state.playback.track
    val items = if (track.title.isBlank() && track.path.isBlank()) {
      emptyList()
    } else {
      listOf(
        mapOf(
          "title" to track.title,
          "artist" to track.artist,
          "path" to track.path,
          "position" to 1
        )
      )
    }
    return mapOf(
      "total" to items.size,
      "offset" to 0,
      "limit" to items.size.coerceAtLeast(1),
      "data" to items
    )
  }

  private fun detailsPayload(state: BridgeUiState): Map<String, String> {
    val track = state.playback.track
    return mapOf(
      "albumArtist" to track.albumArtist,
      "genre" to track.genre,
      "trackNo" to track.trackNo,
      "trackCount" to "",
      "discNo" to track.discNo,
      "discCount" to "",
      "grouping" to "",
      "publisher" to "",
      "ratingAlbum" to "",
      "composer" to "",
      "comment" to "",
      "encoder" to "",
      "kind" to "",
      "format" to extension(track.path),
      "size" to "",
      "channels" to track.channels,
      "sampleRate" to track.sampleRate,
      "bitrate" to track.bitrate,
      "dateModified" to "",
      "dateAdded" to "",
      "lastPlayed" to "",
      "playCount" to "",
      "skipCount" to "",
      "duration" to (track.durationMs / 1000L).toString()
    )
  }

  private fun emptyPage(): Map<String, Any> = mapOf(
    "total" to 0,
    "offset" to 0,
    "limit" to 0,
    "data" to emptyList<Map<String, Any>>()
  )

  private fun extension(path: String): String =
    path.substringAfterLast('.', "").uppercase()

  private fun commandUnavailable(context: String): List<String> =
    listOf(codec.encode(ProtocolConstants.CommandUnavailable, context))

  private fun commandResult(success: Boolean, context: String): List<String> =
    if (success) emptyList() else commandUnavailable(context)

  private fun asInt(data: Any?): Int? = when (data) {
    is Number -> data.toInt()
    is String -> data.toIntOrNull()
    is Map<*, *> -> {
      val current = data["current"] ?: data["value"] ?: data["position"]
      when (current) {
        is Number -> current.toInt()
        is String -> current.toIntOrNull()
        else -> null
      }
    }
    else -> null
  }

  private fun dataAsString(data: Any?): String = when (data) {
    null -> ""
    is String -> data.lowercase()
    is Boolean -> if (data) "on" else "off"
    is Map<*, *> -> JSONObject(data).optString("value").lowercase()
    else -> data.toString().lowercase()
  }
}
