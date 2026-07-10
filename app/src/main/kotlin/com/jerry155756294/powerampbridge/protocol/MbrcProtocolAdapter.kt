package com.jerry155756294.powerampbridge.protocol

import com.jerry155756294.powerampbridge.BuildConfig
import com.jerry155756294.powerampbridge.bridge.BridgeStateRepository
import com.jerry155756294.powerampbridge.bridge.BridgeUiState
import com.jerry155756294.powerampbridge.bridge.PowerampController
import org.json.JSONObject

class MbrcProtocolAdapter(
  private val codec: JsonMessageCodec,
  private val stateRepository: BridgeStateRepository,
  private val powerampGateway: PowerampController
) {
  data class PageRequest(
    val offset: Int = 0,
    val limit: Int = DEFAULT_PAGE_LIMIT
  )

  fun handleCommand(message: IncomingMessage): List<String> {
    stateRepository.recordCommand("${message.context}: ${message.data ?: ""}".trim())
    return when (message.context) {
      ProtocolConstants.PluginVersion ->
        listOf(codec.encode(ProtocolConstants.PluginVersion, pluginVersion()))

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
          powerampGateway.refreshVolumeSnapshot()
          listOf(codec.encode(ProtocolConstants.PlayerVolume, volume))
        } else {
          listOf(codec.encode(ProtocolConstants.PlayerVolume, stateRepository.state.value.playback.volume))
        }
      }

      ProtocolConstants.NowPlayingPosition -> {
        val positionMs = asLong(message.data)
        if (positionMs != null) {
          commandResult(powerampGateway.seekTo(positionMs), ProtocolConstants.NowPlayingPosition)
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

      ProtocolConstants.NowPlayingList ->
        listOf(
          codec.encode(
            ProtocolConstants.NowPlayingList,
            pagedQueuePayload(pageRequest(message.data))
          )
        )

      ProtocolConstants.NowPlayingQueue ->
        queueResponse(message)

      ProtocolConstants.NowPlayingCover ->
        listOf(codec.encode(ProtocolConstants.NowPlayingCover, powerampGateway.currentCoverPayload()))

      ProtocolConstants.NowPlayingLyrics ->
        listOf(codec.encode(ProtocolConstants.NowPlayingLyrics, powerampGateway.currentLyricsPayload()))

      ProtocolConstants.NowPlayingLfmRating ->
        lfmRatingResponse(message)

      ProtocolConstants.RadioStations ->
        listOf(codec.encode(ProtocolConstants.RadioStations, pagedRadioPayload(pageRequest(message.data))))

      ProtocolConstants.PlaylistList,
      ProtocolConstants.BrowseGenres,
      ProtocolConstants.BrowseArtists,
      ProtocolConstants.BrowseAlbums,
      ProtocolConstants.BrowseTracks ->
        listOf(codec.encode(message.context, emptyPage(pageRequest(message.data))))

      ProtocolConstants.LibraryCover ->
        listOf(codec.encode(ProtocolConstants.LibraryCover, mapOf("status" to 404, "cover" to null)))

      ProtocolConstants.PlayerOutput,
      ProtocolConstants.PlayerOutputSwitch ->
        listOf(codec.encode(message.context, mapOf("devices" to emptyList<String>(), "active" to "")))

      ProtocolConstants.LibraryPlayAll,
      ProtocolConstants.PlayerMute ->
        unsupportedNoOp(message)

      ProtocolConstants.NowPlayingListPlay -> {
        val position = asInt(message.data)
        if (position != null) {
          val queueItems = powerampGateway.readQueueItems()
          val success = if (queueItems.isNotEmpty()) {
            powerampGateway.playQueuePosition(position)
          } else {
            stateRepository.state.value.playback.track.path
              .takeIf { it.isNotBlank() }
              ?.let(powerampGateway::playPath)
              ?: false
          }
          commandResult(success, ProtocolConstants.NowPlayingListPlay)
        } else {
          commandUnavailable(ProtocolConstants.NowPlayingListPlay)
        }
      }

      ProtocolConstants.PlaylistPlay -> {
        val path = rawString(message.data)
        if (path.isNullOrBlank()) {
          commandUnavailable(ProtocolConstants.PlaylistPlay)
        } else {
          stateRepository.recordProtocolEvent("radio_play_candidate_context:${message.context}:path=$path")
          commandResult(powerampGateway.playPath(path), ProtocolConstants.PlaylistPlay)
        }
      }

      else -> {
        if (isLibraryClickContext(message.context)) {
          unsupportedNoOp(message)
        } else {
          listOf(codec.encode(ProtocolConstants.UnknownCommand, message.context))
        }
      }
    }
  }

  fun snapshotMessages(
    state: BridgeUiState,
    includePosition: Boolean = true,
    includeCover: Boolean = false
  ): List<String> = buildList {
    add(codec.encode(ProtocolConstants.PluginVersion, pluginVersion()))
    add(codec.encode(ProtocolConstants.PlayerStatus, statusPayload(state)))
    add(codec.encode(ProtocolConstants.PlayerState, state.playback.state))
    add(codec.encode(ProtocolConstants.PlayerRepeat, state.playback.repeat))
    add(codec.encode(ProtocolConstants.PlayerShuffle, state.playback.shuffle))
    add(codec.encode(ProtocolConstants.PlayerVolume, state.playback.volume))
    add(codec.encode(ProtocolConstants.NowPlayingTrack, trackPayload(state)))
    add(codec.encode(ProtocolConstants.NowPlayingLfmRating, lfmRatingPayload(state)))
    if (includePosition) {
      add(positionMessage(state))
    }
    add(codec.encode(ProtocolConstants.NowPlayingDetails, detailsPayload(state)))
    if (includeCover) {
      add(coverStatusMessage())
    }
  }

  fun positionMessage(state: BridgeUiState): String =
    codec.encode(ProtocolConstants.NowPlayingPosition, positionPayload(state))

  fun coverStatusMessage(): String =
    codec.encode(
      ProtocolConstants.NowPlayingCover,
      mapOf("status" to powerampGateway.currentCoverStatus(), "cover" to null)
    )

  fun lyricsMessage(): String =
    codec.encode(ProtocolConstants.NowPlayingLyrics, powerampGateway.currentLyricsPayload())

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

  private fun lfmRatingResponse(message: IncomingMessage): List<String> {
    val action = rawString(message.data)?.trim()?.lowercase()
    if (action.isNullOrEmpty()) {
      return listOf(codec.encode(ProtocolConstants.NowPlayingLfmRating, lfmRatingPayload(stateRepository.state.value)))
    }
    if (action !in LFM_RATING_ACTIONS || !powerampGateway.setLfmRating(action)) {
      return commandUnavailable(ProtocolConstants.NowPlayingLfmRating)
    }
    return listOf(codec.encode(ProtocolConstants.NowPlayingLfmRating, lfmRatingPayload(stateRepository.state.value)))
  }

  private fun lfmRatingPayload(state: BridgeUiState): String = when (state.playback.track.rating) {
    PowerampRating.LIKE -> "Love"
    PowerampRating.UNLIKE -> "Ban"
    else -> "Normal"
  }

  private fun pagedQueuePayload(request: PageRequest): Map<String, Any> {
    val items = queueItems()
    val pageData = items.drop(request.offset).take(request.limit)
    return mapOf(
      "total" to items.size,
      "offset" to request.offset,
      "limit" to request.limit,
      "data" to pageData
    )
  }

  private fun queueItems(): List<Map<String, Any?>> {
    val queueItems = powerampGateway.readQueueItems()
    if (queueItems.isNotEmpty()) {
      stateRepository.recordProtocolEvent("queue_reply_source:poweramp_queue total=${queueItems.size}")
      return queueItems.mapIndexed { index, item ->
        linkedMapOf<String, Any?>(
          "title" to item.title,
          "artist" to item.artist,
          "album" to item.album,
          "path" to item.path,
          "position" to (index + 1),
          "id" to item.queueId,
          "file_id" to item.fileId
        )
      }
    }

    val fallback = fallbackTrack(stateRepository.state.value)
    if (fallback != null) {
      stateRepository.recordProtocolEvent("queue_reply_source:current_track_fallback total=1")
      return listOf(fallback)
    }

    stateRepository.recordProtocolEvent("queue_reply_source:empty_fallback total=0")
    return emptyList()
  }

  private fun fallbackTrack(state: BridgeUiState): Map<String, Any?>? {
    val track = state.playback.track
    if (track.title.isBlank() && track.path.isBlank()) {
      return null
    }

    return linkedMapOf<String, Any?>(
      "title" to track.title,
      "artist" to track.artist,
      "album" to track.album,
      "path" to track.path,
      "position" to 1,
      "file_id" to track.realId
    )
  }

  private fun queueResponse(message: IncomingMessage): List<String> {
    val queueCommand = queueCommandRequest(message.data)
    if (queueCommand != null) {
      val result = powerampGateway.handleQueueCommand(
        type = queueCommand.type,
        paths = queueCommand.paths,
        playPath = queueCommand.playPath
      )
      stateRepository.recordProtocolEvent(
        "queue_command_result:type=${queueCommand.type} code=${result.code} accepted=${result.accepted} detail=${result.detail}"
      )
      return listOf(
        codec.encode(
          ProtocolConstants.NowPlayingQueue,
          mapOf("code" to result.code)
        )
      )
    }

    return listOf(
      codec.encode(
        ProtocolConstants.NowPlayingQueue,
        pagedQueuePayload(pageRequest(message.data))
      )
    )
  }

  private fun pagedRadioPayload(request: PageRequest): Map<String, Any> {
    val stations = powerampGateway.readRadioStations()
    stateRepository.recordProtocolEvent("radio_reply_source:poweramp_radio total=${stations.size}")
    val items = stations.map { station ->
      linkedMapOf(
        "name" to station.name,
        // MBRC's RadioStationDto deliberately only has these two fields. Keeping the
        // payload exact also lets its refresh replace the old local list cleanly.
        "url" to station.url
      )
    }
    val pageData = items.drop(request.offset).take(request.limit)
    return mapOf(
      "total" to items.size,
      "offset" to request.offset,
      "limit" to request.limit,
      "data" to pageData
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

  private fun emptyPage(request: PageRequest): Map<String, Any> = mapOf(
    "total" to 0,
    "offset" to request.offset,
    "limit" to request.limit,
    "data" to emptyList<Map<String, Any>>()
  )

  private fun extension(path: String): String =
    path.substringAfterLast('.', "").uppercase()

  private fun commandUnavailable(context: String): List<String> =
    listOf(codec.encode(ProtocolConstants.CommandUnavailable, context))

  private fun unsupportedNoOp(message: IncomingMessage): List<String> {
    stateRepository.recordProtocolEvent(
      "unsupported_noop:${message.context}:data=${payloadShape(message.data)}"
    )
    return listOf(
      codec.encode(
        message.context,
        mapOf(
          "status" to 200,
          "accepted" to false,
          "unsupported" to true
        )
      )
    )
  }

  private fun isLibraryClickContext(context: String): Boolean {
    val normalized = context.lowercase()
    return normalized.startsWith("library") ||
      normalized.startsWith("playlist") ||
      normalized.startsWith("browse")
  }

  private fun payloadShape(data: Any?): String = when (data) {
    null -> "null"
    is Map<*, *> -> data.keys.joinToString(prefix = "map[", postfix = "]") { it.toString() }
    is List<*> -> "list[size=${data.size}]"
    is Number -> "number"
    is Boolean -> "boolean"
    is String -> "string"
    else -> data.javaClass.simpleName
  }

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

  private fun asLong(data: Any?): Long? = when (data) {
    is Number -> data.toLong()
    is String -> data.toLongOrNull()
    is Map<*, *> -> {
      val current = data["current"] ?: data["value"] ?: data["position"]
      when (current) {
        is Number -> current.toLong()
        is String -> current.toLongOrNull()
        else -> null
      }
    }
    else -> null
  }

  private fun pageRequest(data: Any?): PageRequest {
    if (data !is Map<*, *>) {
      return PageRequest()
    }

    val offset = when (val raw = data["offset"]) {
      is Number -> raw.toInt()
      is String -> raw.toIntOrNull()
      else -> null
    } ?: 0

    val limit = when (val raw = data["limit"]) {
      is Number -> raw.toInt()
      is String -> raw.toIntOrNull()
      else -> null
    } ?: DEFAULT_PAGE_LIMIT

    return PageRequest(
      offset = offset.coerceAtLeast(0),
      limit = limit.coerceAtLeast(1)
    )
  }

  private data class QueueCommandRequest(
    val type: String,
    val paths: List<String>,
    val playPath: String?
  )

  private fun queueCommandRequest(data: Any?): QueueCommandRequest? {
    if (data !is Map<*, *>) {
      return null
    }

    val type = (data["queue"] as? String)?.trim()?.lowercase()
      ?.takeIf { it.isNotEmpty() }
      ?: return null
    val paths = when (val raw = data["data"]) {
      is List<*> -> raw.mapNotNull { it as? String }.map(String::trim).filter(String::isNotEmpty)
      is String -> listOf(raw.trim()).filter(String::isNotEmpty)
      else -> emptyList()
    }
    val playPath = rawString(data["play"])?.trim()?.takeIf { it.isNotEmpty() }
    return QueueCommandRequest(type = type, paths = paths, playPath = playPath)
  }

  private fun dataAsString(data: Any?): String = when (data) {
    null -> ""
    is String -> data.lowercase()
    is Boolean -> if (data) "on" else "off"
    is Map<*, *> -> JSONObject(data).optString("value").lowercase()
    else -> data.toString().lowercase()
  }

  private fun rawString(data: Any?): String? = when (data) {
    null -> null
    is String -> data
    is Map<*, *> -> {
      val value = data["value"] ?: data["url"] ?: data["path"] ?: data["src"]
      value as? String
    }
    else -> data.toString()
  }

  private companion object {
    const val DEFAULT_PAGE_LIMIT = 800
    val LFM_RATING_ACTIONS = setOf("love", "ban", ProtocolConstants.Toggle)
  }
}

private object PowerampRating {
  const val UNLIKE = 1
  const val LIKE = 5
}
