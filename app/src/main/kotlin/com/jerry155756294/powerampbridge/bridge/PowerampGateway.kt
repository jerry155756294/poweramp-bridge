package com.jerry155756294.powerampbridge.bridge

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
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
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.ceil

class PowerampGateway(
  private val context: Context,
  private val stateRepository: BridgeStateRepository
) : PowerampController {
  private val audioManager =
    context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

  @Volatile
  private var registered = false
  private var audioDeviceCallbackRegistered = false
  private var coverLoadJob: Job? = null
  private var audioDeviceRefreshJob: Job? = null
  private var coverLoadGeneration: Long = 0L
  private val negativeCoverCache = ConcurrentHashMap<Long, NegativeCoverCacheEntry>()
  private val libraryCoverCache = ConcurrentHashMap<String, LibraryCoverCacheEntry>()
  private val missingLibraryCoverCache = ConcurrentHashMap<String, Long>()
  private val libraryAlbumArtistCache = ConcurrentHashMap<Long, String>()
  private val libraryAlbumArtistIdCache = ConcurrentHashMap<Long, String>()
  private val coverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  @Volatile
  private var activeRadioCategory: ActiveRadioCategory? = null

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
          "Poweramp 廣播處理失敗：${error.message ?: error.javaClass.simpleName}"
        )
        stateRepository.recordPowerampEvent(
          "Broadcast failure on $actionLabel extras=$extrasSummary ${error.javaClass.simpleName}: ${error.message ?: "no-message"}"
        )
      }
    }
  }

  private val audioDeviceCallback = object : AudioDeviceCallback() {
    override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
      handleAudioOutputDevicesChanged("added", addedDevices)
    }

    override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
      handleAudioOutputDevicesChanged("removed", removedDevices)
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
    runCatching {
      audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
      audioDeviceCallbackRegistered = true
    }.onFailure { error ->
      Timber.w(error, "Unable to observe audio output device changes")
      stateRepository.recordPowerampEvent(
        "Audio device observer unavailable: ${error.javaClass.simpleName}"
      )
    }
    registered = true
    updateVolumeSnapshot()
  }

  fun stop() {
    if (!registered) return
    runCatching { context.unregisterReceiver(receiver) }
    if (audioDeviceCallbackRegistered) {
      runCatching { audioManager.unregisterAudioDeviceCallback(audioDeviceCallback) }
      audioDeviceCallbackRegistered = false
    }
    registered = false
    coverLoadJob?.cancel()
    audioDeviceRefreshJob?.cancel()
  }

  fun refreshAvailability(): Boolean {
    val powerampPackage = PowerampAPIHelper.getPowerampPackageName(context)
    val available = powerampPackage != null && runCatching {
      context.packageManager.getPackageInfo(powerampPackage, 0)
    }.isSuccess
    stateRepository.setPowerampAvailable(available)
    return available
  }

  fun requestDataAccessPermission(): Boolean =
    PowerampDataAccess.request(context, stateRepository)

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

  override fun readLibraryPage(libraryContext: String, offset: Int, limit: Int): PowerampLibraryPage {
    if (!refreshAvailability()) return unavailableLibraryPage(offset, limit, "poweramp_unavailable")
    if (libraryContext == "browsealbums" && offset == 0) {
      // A new metadata sync starts from offset zero. Do not carry album-artist
      // names across a Poweramp rescan or tag edit.
      libraryAlbumArtistCache.clear()
      libraryAlbumArtistIdCache.clear()
    }
    val definition = libraryQueryDefinition(libraryContext)
      ?: return unavailableLibraryPage(offset, limit, "unknown_context=$libraryContext")
    return runCatching {
      context.contentResolver.query(
        definition.uri,
        definition.projection,
        null,
        null,
        definition.sortOrder
      )?.use { cursor ->
        val total = cursor.count
        val data = buildList {
          if (offset < total && cursor.moveToPosition(offset)) {
            do {
              add(definition.mapper(cursor))
            } while (size < limit && cursor.moveToNext())
          }
        }
        stateRepository.setPowerampDataAccess(PowerampDataAccessStatus.AVAILABLE)
        stateRepository.recordProtocolEvent("library_reply:$libraryContext total=$total offset=$offset count=${data.size}")
        PowerampLibraryPage(total, offset, limit, data)
      } ?: unavailableLibraryPage(offset, limit, "null_cursor")
    }.onFailure { error ->
      Timber.w(error, "Poweramp library query failed: %s", libraryContext)
      stateRepository.setPowerampDataAccess(PowerampDataAccessStatus.FAILED, error.javaClass.simpleName)
      stateRepository.recordPowerampEvent("Library query failed $libraryContext: ${error.javaClass.simpleName}")
    }.getOrElse { unavailableLibraryPage(offset, limit, it.javaClass.simpleName) }
  }

  override fun readLibraryCover(request: Map<*, *>?): Map<String, Any?> {
    val artist = request?.get("artist") as? String ?: ""
    val album = request?.get("album") as? String ?: ""
    if (album.isBlank()) return mapOf("status" to 400, "cover" to null)
    if (!refreshAvailability()) return mapOf("status" to 503, "cover" to null)
    val size = ((request?.get("size") as? Number)?.toInt() ?: (request?.get("size") as? String)?.toIntOrNull() ?: DEFAULT_LIBRARY_COVER_SIZE)
      .coerceIn(MIN_LIBRARY_COVER_SIZE, MAX_LIBRARY_COVER_SIZE)
    val requestHash = request?.get("hash") as? String
    val albumKey = "$artist\u0000$album"
    val missingUntil = missingLibraryCoverCache[albumKey]
    if (missingUntil != null && missingUntil > SystemClock.elapsedRealtime()) {
      return mapOf("status" to 404, "artist" to artist, "album" to album, "cover" to null)
    }
    val realId = findLibraryCoverRealId(artist, album) ?: run {
      missingLibraryCoverCache[albumKey] = SystemClock.elapsedRealtime() + NEGATIVE_COVER_CACHE_MS
      return mapOf("status" to 404, "artist" to artist, "album" to album, "cover" to null)
    }
    val cacheKey = "$albumKey\u0000$realId\u0000$size"
    val cached = libraryCoverCache[cacheKey] ?: loadLibraryCover(realId, size)?.also {
      libraryCoverCache[cacheKey] = it
      missingLibraryCoverCache.remove(albumKey)
    } ?: run {
      missingLibraryCoverCache[albumKey] = SystemClock.elapsedRealtime() + NEGATIVE_COVER_CACHE_MS
      return mapOf("status" to 404, "artist" to artist, "album" to album, "cover" to null)
    }
    if (requestHash == cached.hash) {
      return mapOf("status" to 304, "artist" to artist, "album" to album, "cover" to null, "hash" to cached.hash)
    }
    return mapOf("status" to 200, "artist" to artist, "album" to album, "cover" to cached.base64, "hash" to cached.hash)
  }

  override fun readLibraryNavigation(context: String, query: String): List<Map<String, Any?>> {
    if (!refreshAvailability() || query.isBlank()) return emptyList()
    return runCatching {
      when (context) {
        "libraryalbumtracks" -> readLibraryTracksWhere(
          "${TableDefs.Albums.ALBUM}=?", arrayOf(query)
        )
        "libraryartistalbums" -> readLibraryArtistAlbums(query)
        "librarygenreartists" -> readLibraryGenreArtists(query)
        else -> emptyList()
      }
    }.onFailure { error ->
      Timber.w(error, "Poweramp library navigation failed: %s", context)
      stateRepository.recordPowerampEvent(
        "Library navigation failed $context: ${error.javaClass.simpleName}"
      )
    }.getOrDefault(emptyList())
  }

  private fun unavailableLibraryPage(offset: Int, limit: Int, detail: String): PowerampLibraryPage {
    stateRepository.recordProtocolEvent("library_unavailable:$detail")
    return PowerampLibraryPage(0, offset, limit, emptyList(), available = false)
  }

  private fun libraryQueryDefinition(context: String): LibraryQueryDefinition? = when (context) {
    "browsegenres" -> LibraryQueryDefinition(
      "genres",
      arrayOf(
        TableDefs.Genres.GENRE,
        TableDefs.Genres.NUM_FILES.substringAfterLast('.') + " AS count"
      ),
      "${TableDefs.Genres.GENRE} COLLATE NOCASE"
    ) { cursor -> linkedMapOf(
      "genre" to cursor.stringOrBlank("genre"),
      "count" to (cursor.longOrNull("count") ?: 0L).toInt()
    ) }
    "browseartists" -> LibraryQueryDefinition(
      "artists",
      arrayOf(
        TableDefs.Artists.ARTIST,
        TableDefs.Artists.NUM_FILES.substringAfterLast('.') + " AS count"
      ),
      "${TableDefs.Artists.ARTIST} COLLATE NOCASE"
    ) { cursor -> linkedMapOf(
      "artist" to cursor.stringOrBlank("artist"),
      "count" to (cursor.longOrNull("count") ?: 0L).toInt()
    ) }
    "browsealbums" -> LibraryQueryDefinition(
      "albums",
      arrayOf(
        // Keep this projection identical to Poweramp's official API example.
        // Some provider builds expose `album`/`_id` but reject the otherwise
        // documented `num_files` column with SQLiteException.
        TableDefs.Albums._ID,
        TableDefs.Albums.ALBUM
      ),
      TableDefs.Albums.ALBUM
    ) { cursor ->
      val albumId = cursor.longOrNull("_id")
        ?: cursor.longOrNull(TableDefs.Albums._ID)
        ?: 0L
      linkedMapOf(
        // The /albums provider endpoint deliberately exposes album columns only. Do not use
        // ad-hoc joins here: several Poweramp versions reject them with SQLiteException.
        "album" to cursor.stringOrBlank("album"),
        "artist" to firstArtistForAlbum(albumId),
        "count" to countTracksForAlbum(albumId)
      )
    }
    "browsetracks" -> LibraryQueryDefinition(
      "files",
      libraryTrackProjection(),
      "${TableDefs.Files.TITLE_TAG} COLLATE NOCASE, ${TableDefs.Files._ID}",
      ::libraryTrackPayload
    )
    else -> null
  }

  private fun findLibraryCoverRealId(artist: String, album: String): Long? = runCatching {
    val uri = PowerampAPI.ROOT_URI.buildUpon().appendEncodedPath("files").build()
    context.contentResolver.query(
      uri,
      arrayOf(
        "${TableDefs.Files._ID} AS real_id",
        "${TableDefs.Files.ALBUM_ARTIST_ID} AS album_artist_id",
        "${TableDefs.Artists.ARTIST} AS track_artist"
      ),
      "${TableDefs.Albums.ALBUM}=?",
      arrayOf(album),
      "${TableDefs.Files._ID} ASC"
    )?.use { cursor ->
      var fallbackRealId: Long? = null
      var matchedRealId: Long? = null
      while (cursor.moveToNext()) {
        val realId = cursor.longOrNull("real_id") ?: continue
        if (fallbackRealId == null) fallbackRealId = realId
        val albumArtist = albumArtistName(cursor.longOrNull("album_artist_id") ?: 0L)
          .ifBlank { cursor.stringOrBlank("track_artist") }
        if (artist.isBlank() || artist == albumArtist) {
          matchedRealId = realId
          break
        }
      }
      if (artist.isBlank()) fallbackRealId else matchedRealId
    }
  }.getOrNull()

  private fun firstArtistForAlbum(albumId: Long): String {
    if (albumId <= 0L) return ""
    libraryAlbumArtistCache[albumId]?.let { return it }
    val uri = PowerampAPI.ROOT_URI.buildUpon().appendEncodedPath("files").build()
    val resolved = runCatching {
      context.contentResolver.query(
        uri,
        arrayOf(
          "${TableDefs.Artists.ARTIST} AS artist",
          "${TableDefs.Files.ALBUM_ARTIST_ID} AS album_artist_id"
        ),
        "${TableDefs.Files.ALBUM_ID}=?",
        arrayOf(albumId.toString()),
        "${TableDefs.Files._ID} ASC"
      )?.use { cursor ->
        var fallbackArtist = ""
        var albumArtist = ""
        while (cursor.moveToNext()) {
          if (fallbackArtist.isBlank()) fallbackArtist = cursor.stringOrBlank("artist")
          val albumArtistId = cursor.longOrNull("album_artist_id") ?: 0L
          if (albumArtistId > 0L) {
            albumArtist = albumArtistName(albumArtistId)
            if (albumArtist.isNotBlank()) break
          }
        }
        albumArtist.ifBlank { fallbackArtist }
      } ?: ""
    }.getOrDefault("")
    if (resolved.isNotBlank()) libraryAlbumArtistCache[albumId] = resolved
    return resolved
  }

  private fun albumArtistForTrack(albumId: Long, trackArtist: String): String {
    if (albumId <= 0L) return trackArtist
    return libraryAlbumArtistCache[albumId]
      ?: firstArtistForAlbum(albumId).ifBlank { trackArtist }
  }

  private fun countTracksForAlbum(albumId: Long): Int {
    if (albumId <= 0L) return 0
    val uri = PowerampAPI.ROOT_URI.buildUpon().appendEncodedPath("files").build()
    return runCatching {
      context.contentResolver.query(
        uri,
        arrayOf(TableDefs.Files._ID),
        "${TableDefs.Files.ALBUM_ID}=?",
        arrayOf(albumId.toString()),
        TableDefs.Files._ID
      )?.use { cursor -> cursor.count } ?: 0
    }.getOrDefault(0)
  }

  private fun albumArtistName(id: Long): String {
    if (id <= 0L) return ""
    libraryAlbumArtistIdCache[id]?.let { return it }
    val uri = PowerampAPI.ROOT_URI.buildUpon().appendEncodedPath("album_artists").build()
    val name = runCatching {
      context.contentResolver.query(
        uri,
        arrayOf(TableDefs.AlbumArtists.ALBUM_ARTIST),
        "${TableDefs.AlbumArtists._ID}=?",
        arrayOf(id.toString()),
        null
      )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.stringOrBlank("album_artist") else ""
      } ?: ""
    }.getOrDefault("")
    if (name.isNotBlank()) libraryAlbumArtistIdCache[id] = name
    return name
  }

  private fun readLibraryArtistAlbums(artist: String): List<Map<String, Any?>> {
    val uri = PowerampAPI.ROOT_URI.buildUpon().appendEncodedPath("files").build()
    return context.contentResolver.query(
      uri,
      arrayOf(
        "${TableDefs.Files.ALBUM_ID} AS album_id",
        "${TableDefs.Albums.ALBUM} AS album"
      ),
      "${TableDefs.Artists.ARTIST}=? AND ${TableDefs.Files.ALBUM_ID}>0",
      arrayOf(artist),
      "${TableDefs.Albums.ALBUM} COLLATE NOCASE, ${TableDefs.Files._ID}"
    )?.use { cursor ->
      val albumNames = linkedMapOf<Long, String>()
      val counts = linkedMapOf<Long, Int>()
      while (cursor.moveToNext()) {
        val albumId = cursor.longOrNull("album_id") ?: continue
        val album = cursor.stringOrBlank("album")
        if (album.isNotBlank()) {
          albumNames.putIfAbsent(albumId, album)
          counts[albumId] = (counts[albumId] ?: 0) + 1
        }
      }
      counts.mapNotNull { (albumId, count) ->
        val album = albumNames[albumId] ?: return@mapNotNull null
        mapOf(
          "album" to album,
          "artist" to albumArtistForTrack(albumId, artist),
          "count" to count
        )
      }
    } ?: emptyList()
  }

  private fun readLibraryGenreArtists(genre: String): List<Map<String, Any?>> {
    // Genres are a category relation in Poweramp. There is no stable
    // /genres/{id}/artists endpoint across provider versions, so enumerate the
    // documented /genres/{id}/files endpoint and aggregate the artist tag.
    val genreUri = PowerampAPI.ROOT_URI.buildUpon().appendEncodedPath("genres").build()
    val genreId = context.contentResolver.query(
      genreUri, arrayOf("${TableDefs.Genres._ID} AS genre_id"), "${TableDefs.Genres.GENRE}=?",
      arrayOf(genre), null
    )?.use { cursor -> if (cursor.moveToFirst()) cursor.longOrNull("genre_id") else null } ?: return emptyList()
    val filesUri = PowerampAPI.ROOT_URI.buildUpon()
      .appendEncodedPath("genres").appendEncodedPath(genreId.toString()).appendEncodedPath("files").build()
    return context.contentResolver.query(
      filesUri,
      arrayOf("${TableDefs.Artists.ARTIST} AS artist"),
      null, null, "${TableDefs.Artists.ARTIST} COLLATE NOCASE"
    )?.use { cursor ->
      val counts = linkedMapOf<String, Int>()
      while (cursor.moveToNext()) {
        val artist = cursor.stringOrBlank("artist")
        if (artist.isNotBlank()) counts[artist] = (counts[artist] ?: 0) + 1
      }
      counts.map { (artist, count) -> mapOf("artist" to artist, "count" to count) }
    } ?: emptyList()
  }

  private fun readLibraryTracksWhere(selection: String, selectionArgs: Array<String>): List<Map<String, Any?>> {
    val uri = PowerampAPI.ROOT_URI.buildUpon().appendEncodedPath("files").build()
    return context.contentResolver.query(
      uri,
      libraryTrackProjection(),
      selection, selectionArgs,
      "${TableDefs.Files.DISC} ASC, ${TableDefs.Files.TRACK_TAG} ASC, ${TableDefs.Files._ID} ASC"
    )?.use { cursor ->
      buildList {
        while (cursor.moveToNext()) add(libraryTrackPayload(cursor))
      }
    } ?: emptyList()
  }

  private fun libraryTrackProjection(): Array<String> = arrayOf(
    "${TableDefs.Files.FULL_PATH} AS src",
    "${TableDefs.Files.ALBUM_ID} AS album_id",
    "${TableDefs.Artists.ARTIST} AS artist",
    "${TableDefs.Files.TITLE_TAG} AS title",
    "${TableDefs.Files.TRACK_TAG} AS trackno",
    "${TableDefs.Files.DISC} AS disc",
    "${TableDefs.Albums.ALBUM} AS album",
    "${TableDefs.Files.YEAR} AS year"
  )

  private fun libraryTrackPayload(cursor: Cursor): Map<String, Any?> {
    val albumId = cursor.longOrNull("album_id") ?: 0L
    val artist = cursor.stringOrBlank("artist")
    return linkedMapOf(
      "src" to cursor.stringOrBlank("src"),
      "artist" to artist,
      "title" to cursor.stringOrBlank("title"),
      "trackno" to (cursor.longOrNull("trackno") ?: 0L).toInt(),
      "disc" to (cursor.longOrNull("disc") ?: 0L).toInt(),
      "album" to cursor.stringOrBlank("album"),
      "album_artist" to albumArtistForTrack(albumId, artist),
      "genre" to "",
      "year" to cursor.stringOrBlank("year")
    )
  }

  private fun loadLibraryCover(realId: Long, size: Int): LibraryCoverCacheEntry? {
    val uri = PowerampAPI.AA_ROOT_URI.buildUpon().appendEncodedPath("files").appendEncodedPath(realId.toString()).build()
    val result = loadScaledCoverBase64Detailed(uri, size, LIBRARY_COVER_JPEG_QUALITY)
    val base64 = (result as? CoverLoadResult.Ready)?.base64 ?: return null
    // Include the requested size in the validator. If the source image is already
    // smaller than two requested sizes, the encoded bytes may be identical; a
    // byte-only hash would then incorrectly return 304 for the wrong variant.
    return LibraryCoverCacheEntry(base64, sha1(size, Base64.decode(base64, Base64.NO_WRAP)))
  }

  private fun sha1(size: Int, bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-1")
    digest.update("mbrc-cover-v1:$size:".toByteArray(Charsets.UTF_8))
    return digest.digest(bytes).joinToString("") { "%02x".format(it) }
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
      }?.also {
        stateRepository.setPowerampDataAccess(PowerampDataAccessStatus.AVAILABLE)
      } ?: emptyList()
    }.onFailure { error ->
      Timber.w(error, "Failed querying Poweramp queue")
      stateRepository.setPowerampDataAccess(
        PowerampDataAccessStatus.FAILED,
        "無法讀取 Poweramp 清單：${error.javaClass.simpleName}"
      )
      stateRepository.recordPowerampEvent(
        "Queue query failed: ${error.javaClass.simpleName}:${error.message ?: "no-message"}"
      )
    }.getOrDefault(emptyList())
  }

  override fun readRadioStations(): List<PowerampRadioStation> {
    if (!refreshAvailability()) {
      return emptyList()
    }

    val streamStations = readStoredRadioStations()
    if (streamStations.isNotEmpty()) {
      stateRepository.recordPowerampEvent("Radio query source=poweramp_streams total=${streamStations.size}")
      return streamStations
    }

    val playlistStations = readPlaylistRadioStations()
    if (playlistStations.isNotEmpty()) {
      stateRepository.recordPowerampEvent(
        "Radio query source=poweramp_playlists total=${playlistStations.size}"
      )
      return playlistStations
    }

    val libraryStations = readLibraryRadioStations()
    if (libraryStations.isNotEmpty()) {
      stateRepository.recordPowerampEvent(
        "Radio query source=poweramp_files total=${libraryStations.size}"
      )
      return libraryStations
    }

    // Built-in/imported radio can be exposed as virtual `playlist:` items only. The track
    // broadcast carries a Poweramp category URI that remains a valid OPEN_TO_PLAY target.
    val categoryStations = readActiveRadioCategoryStations()
    if (categoryStations.isNotEmpty()) {
      stateRepository.recordPowerampEvent(
        "Radio query source=poweramp_active_category total=${categoryStations.size}"
      )
      return categoryStations
    }

    // Poweramp can load a radio M3U directly into its current queue without creating a row in
    // the /streams collection. This is the final fallback for playlist-backed radio sources.
    val rawQueueItems = readQueueItems()
    val queuedStations = rawQueueItems
      .asSequence()
      .filter { it.path.isHttpUrl() }
      .mapIndexed { index, item ->
        PowerampRadioStation(
          streamId = item.fileId ?: -(index + 1L),
          name = item.title.ifBlank { item.artist }.ifBlank { item.path },
          url = item.path,
          artist = item.artist,
          album = item.album
        )
      }
      .toList()
      .normalizeRadioStations()
    stateRepository.recordPowerampEvent(
      "Radio query source=${if (queuedStations.isEmpty()) "poweramp_queue_no_http" else "poweramp_queue"} " +
        "streams=${streamStations.size} playlists=${playlistStations.size} files=${libraryStations.size} " +
        "category=${activeRadioCategory?.categoryUri?.path ?: "none"} " +
        "queue_total=${rawQueueItems.size} total=${queuedStations.size}"
    )
    return queuedStations
  }

  private fun readStoredRadioStations(): List<PowerampRadioStation> {
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
      }?.also {
        stateRepository.setPowerampDataAccess(PowerampDataAccessStatus.AVAILABLE)
      }?.normalizeRadioStations() ?: emptyList()
    }.onFailure { error ->
      Timber.w(error, "Failed querying Poweramp streams")
      stateRepository.setPowerampDataAccess(
        PowerampDataAccessStatus.FAILED,
        "無法讀取 Poweramp 廣播清單：${error.javaClass.simpleName}"
      )
      stateRepository.recordPowerampEvent(
        "Radio query failed: ${error.javaClass.simpleName}:${error.message ?: "no-message"}"
      )
    }.getOrDefault(emptyList())
  }

  private fun readPlaylistRadioStations(): List<PowerampRadioStation> {
    val playlistsUri = PowerampAPI.ROOT_URI.buildUpon()
      .appendEncodedPath("playlists")
      .build()
    val playlists = runCatching {
      context.contentResolver.query(
        playlistsUri,
        arrayOf(
          "${TableDefs.Playlists._ID} AS playlist_id",
          "${TableDefs.Playlists.PLAYLIST} AS playlist_name"
        ),
        null,
        null,
        null
      )?.use { cursor ->
        buildList {
          while (cursor.moveToNext()) {
            val id = cursor.longOrNull("playlist_id") ?: continue
            add(id to cursor.stringOrBlank("playlist_name"))
          }
        }
      } ?: emptyList()
    }.onFailure { error ->
      Timber.w(error, "Failed querying Poweramp playlists")
      stateRepository.recordPowerampEvent(
        "Radio playlist query failed: ${error.javaClass.simpleName}:${error.message ?: "no-message"}"
      )
    }.getOrDefault(emptyList())

    return playlists.flatMap { (playlistId, playlistName) ->
      readPlaylistRadioEntries(playlistId, playlistName)
    }.normalizeRadioStations()
  }

  private fun readLibraryRadioStations(): List<PowerampRadioStation> {
    val uri = PowerampAPI.ROOT_URI.buildUpon()
      .appendEncodedPath("files")
      .build()
    return runCatching {
      context.contentResolver.query(
        uri,
        arrayOf(
          "${TableDefs.Files._ID} AS file_id",
          "${TableDefs.Files.NAME_WITHOUT_NUMBER} AS station_name",
          "${TableDefs.Files.TITLE_TAG} AS title_tag",
          "${TableDefs.Files.ARTIST_TAG} AS artist_tag",
          "${TableDefs.Files.ALBUM_TAG} AS album_tag",
          "${TableDefs.Files.URL} AS station_url",
          "${TableDefs.Files.FULL_PATH} AS station_path"
        ),
        "${TableDefs.Files.URL} IS NOT NULL",
        null,
        null
      )?.use { cursor ->
        buildList {
          while (cursor.moveToNext()) {
            val fileId = cursor.longOrNull("file_id") ?: continue
            val url = cursor.firstNonBlank("station_url", "station_path")
            if (!url.isHttpUrl()) continue
            add(
              PowerampRadioStation(
                streamId = fileId,
                name = cursor.firstNonBlank("station_name", "title_tag", "station_url"),
                url = url,
                artist = cursor.stringOrBlank("artist_tag"),
                album = cursor.stringOrBlank("album_tag")
              )
            )
          }
        }
      }?.also {
        stateRepository.setPowerampDataAccess(PowerampDataAccessStatus.AVAILABLE)
      }?.normalizeRadioStations() ?: emptyList()
    }.onFailure { error ->
      Timber.w(error, "Failed querying Poweramp URL files")
      stateRepository.recordPowerampEvent(
        "Radio files query failed: ${error.javaClass.simpleName}:${error.message ?: "no-message"}"
      )
    }.getOrDefault(emptyList())
  }

  private fun readActiveRadioCategoryStations(): List<PowerampRadioStation> {
    val category = activeRadioCategory ?: return emptyList()
    val categoryUri = category.categoryUri
    val entryIdColumn = when {
      categoryUri.pathSegments.contains("queue") -> TableDefs.Queue._ID
      categoryUri.pathSegments.contains("playlists") -> TableDefs.PlaylistEntries._ID
      else -> TableDefs.Files._ID
    }
    val queryUri = categoryUri.buildUpon()
      .clearQuery()
      .appendQueryParameter("lim", "800")
      .build()

    val stations = runCatching {
      context.contentResolver.query(
        queryUri,
        arrayOf(
          "$entryIdColumn AS entry_id",
          "${TableDefs.Files.NAME_WITHOUT_NUMBER} AS station_name",
          "${TableDefs.Files.TITLE_TAG} AS title_tag",
          "${TableDefs.Files.ARTIST_TAG} AS artist_tag",
          "${TableDefs.Files.ALBUM_TAG} AS album_tag",
          "${TableDefs.Files.URL} AS station_url",
          "${TableDefs.Files.FULL_PATH} AS station_path"
        ),
        null,
        null,
        null
      )?.use { cursor ->
        buildList {
          while (cursor.moveToNext()) {
            val entryId = cursor.longOrNull("entry_id") ?: continue
            val rawTarget = cursor.firstNonBlank("station_url", "station_path")
            val target = rawTarget.takeIf { it.isHttpUrl() }
              ?: categoryUri.buildUpon().appendEncodedPath(entryId.toString()).build().toString()
            add(
              PowerampRadioStation(
                streamId = entryId,
                name = cursor.firstNonBlank("station_name", "title_tag")
                  .ifBlank { rawTarget }
                  .ifBlank { "廣播 $entryId" },
                url = target,
                artist = cursor.stringOrBlank("artist_tag"),
                album = cursor.stringOrBlank("album_tag")
              )
            )
          }
        }
      } ?: emptyList()
    }.onFailure { error ->
      Timber.w(error, "Failed querying active Poweramp radio category")
      stateRepository.recordPowerampEvent(
        "Radio category query failed: ${error.javaClass.simpleName}:${error.message ?: "no-message"}"
      )
    }.getOrDefault(emptyList()).normalizeRadioStations()

    if (stations.isNotEmpty()) return stations

    // Track.ID is the category entry ID paired with CAT_URI in Poweramp's track broadcast.
    // This preserves at least the current virtual station when its category can't be enumerated.
    return category.entryId.takeIf { it > 0L }?.let { entryId ->
      listOf(
        PowerampRadioStation(
          streamId = entryId,
          name = category.title.ifBlank { category.path }.ifBlank { "目前廣播" },
          url = categoryUri.buildUpon().appendEncodedPath(entryId.toString()).build().toString(),
          artist = category.artist,
          album = category.album
        )
      )
    } ?: emptyList()
  }

  private fun readPlaylistRadioEntries(
    playlistId: Long,
    playlistName: String
  ): List<PowerampRadioStation> {
    val entriesUri = PowerampAPI.ROOT_URI.buildUpon()
      .appendEncodedPath("playlists")
      .appendEncodedPath(playlistId.toString())
      .appendEncodedPath("files")
      .build()
    return runCatching {
      context.contentResolver.query(
        entriesUri,
        arrayOf(
          "${TableDefs.PlaylistEntries._ID} AS entry_id",
          "${TableDefs.PlaylistEntries.FILE_NAME} AS entry_file_name",
          "${TableDefs.Files.NAME_WITHOUT_NUMBER} AS station_name",
          "${TableDefs.Files.TITLE_TAG} AS title_tag",
          "${TableDefs.Files.ARTIST_TAG} AS artist_tag",
          "${TableDefs.Files.ALBUM_TAG} AS album_tag",
          "${TableDefs.Files.URL} AS entry_url",
          "${TableDefs.Files.FULL_PATH} AS entry_path"
        ),
        null,
        null,
        null
      )?.use { cursor ->
        buildList {
          while (cursor.moveToNext()) {
            val entryId = cursor.longOrNull("entry_id") ?: continue
            val url = sequenceOf("entry_url", "entry_path", "entry_file_name")
              .map(cursor::stringOrBlank)
              .firstOrNull { it.isHttpUrl() }
              ?: continue
            add(
              PowerampRadioStation(
                streamId = entryId,
                name = cursor.firstNonBlank("station_name", "title_tag").ifBlank { playlistName }.ifBlank { url },
                url = url,
                artist = cursor.stringOrBlank("artist_tag"),
                album = cursor.stringOrBlank("album_tag")
              )
            )
          }
        }
      } ?: emptyList()
    }.onFailure { error ->
      Timber.w(error, "Failed querying Poweramp playlist entries")
      stateRepository.recordPowerampEvent(
        "Radio playlist entries failed: id=$playlistId ${error.javaClass.simpleName}:${error.message ?: "no-message"}"
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

    // Poweramp explicitly supports http(s) data URIs for OPEN_TO_PLAY. Using the URL directly
    // also works for playlist-backed radios, which have no usable /streams row to address.
    val uri = parsePlayableUri(trimmed) ?: return false

    stateRepository.recordPowerampEvent(
      "Path play dispatch: source=direct_uri target=$uri"
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
        if (paths.size > 1) {
          return enqueueLibraryPaths(paths, playPath, normalizedType)
        }
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

      "add-all", "next", "last" -> enqueueLibraryPaths(paths, playPath, normalizedType)

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

  private fun enqueueLibraryPaths(
    paths: List<String>,
    playPath: String?,
    type: String
  ): QueueCommandResult {
    val fileIds = paths.mapNotNull { path -> findFileIdByPath(path)?.let { path to it } }
    if (fileIds.isEmpty()) {
      return QueueCommandResult(404, accepted = false, detail = "queue paths not found count=${paths.size}")
    }
    val queueUri = PowerampAPI.ROOT_URI.buildUpon().appendEncodedPath("queue").build()
    val maxSort = context.contentResolver.query(
      queueUri, arrayOf("MAX(${TableDefs.Queue.SORT})"), null, null, null
    )?.use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getInt(0) else 0 } ?: 0
    var sort = maxSort + 1
    val inserted = buildList {
      fileIds.forEach { (path, fileId) ->
        val values = ContentValues().apply {
          put(TableDefs.Queue.FOLDER_FILE_ID.substringAfterLast('.'), fileId)
          put(TableDefs.Queue.SORT.substringAfterLast('.'), sort++)
        }
        context.contentResolver.insert(queueUri, values)?.let { entryUri ->
          add(path to entryUri)
        }
      }
    }
    if (inserted.isEmpty()) {
      return QueueCommandResult(500, accepted = false, detail = "queue insert failed count=${fileIds.size}")
    }
    notifyPowerampQueueChanged()
    val target = playPath?.let { requested ->
      inserted.firstOrNull { it.first.equals(requested, ignoreCase = true) }?.second
    } ?: inserted.first().second
    val shouldPlay = type == "add-all" || type == "now" || type == "default"
    val accepted = !shouldPlay || openToPlay(target)
    stateRepository.recordPowerampEvent(
      "Queue insert: type=$type requested=${paths.size} inserted=${inserted.size} play=$shouldPlay accepted=$accepted"
    )
    return QueueCommandResult(
      if (accepted) 200 else 500,
      accepted,
      "queue command type=$type inserted=${inserted.size}"
    )
  }

  private fun findFileIdByPath(path: String): Long? = runCatching {
    val uri = PowerampAPI.ROOT_URI.buildUpon().appendEncodedPath("files").build()
    context.contentResolver.query(
      uri,
      arrayOf("${TableDefs.Files._ID} AS file_id"),
      "${TableDefs.Files.FULL_PATH}=? OR ${TableDefs.Files.FILE_PATH}=? OR ${TableDefs.Files.URL}=?",
      arrayOf(path, path, path),
      "${TableDefs.Files._ID} ASC"
    )?.use { cursor -> if (cursor.moveToFirst()) cursor.longOrNull("file_id") else null }
  }.getOrNull()

  private fun notifyPowerampQueueChanged() {
    val packageName = PowerampAPIHelper.getPowerampPackageName(context) ?: return
    context.sendBroadcast(
      Intent(PowerampAPI.ACTION_RELOAD_DATA)
        .setPackage(packageName)
        .putExtra(PowerampAPI.EXTRA_PACKAGE, context.packageName)
        .putExtra(PowerampAPI.EXTRA_TABLE, TableDefs.Queue.TABLE)
    )
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

  private fun handleAudioOutputDevicesChanged(change: String, devices: Array<AudioDeviceInfo>) {
    val outputDevices = devices.filter { it.isSink }
    if (outputDevices.isEmpty()) return

    // USB DAC and wired headset routing can complete after the callback. Refresh now for a
    // responsive UI and once more after the platform has applied the output-specific range.
    updateVolumeSnapshot()
    audioDeviceRefreshJob?.cancel()
    audioDeviceRefreshJob = coverScope.launch {
      delay(AUDIO_DEVICE_VOLUME_SETTLE_DELAY_MS)
      if (!registered) return@launch
      updateVolumeSnapshot()
      stateRepository.recordPowerampEvent(
        "Audio output device $change: ${outputDevices.size}; music volume=${stateRepository.state.value.playback.volume}%"
      )
    }
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
    captureActiveRadioCategory(track)
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

  @Suppress("DEPRECATION")
  private fun captureActiveRadioCategory(track: Bundle) {
    val path = track.getString(PowerampAPI.Track.PATH).orEmpty()
    val category = PowerampBroadcastDiagnostics.readInt(track, PowerampAPI.Track.CAT)
    if (!path.isRadioLikePath() && category != PowerampAPI.Cats.STREAM_FILES) {
      activeRadioCategory = null
      return
    }

    val categoryUri = track.getParcelable(PowerampAPI.Track.CAT_URI) as? Uri ?: return
    val entryId = PowerampBroadcastDiagnostics.readLong(track, PowerampAPI.Track.ID) ?: 0L
    activeRadioCategory = ActiveRadioCategory(
      categoryUri = categoryUri.buildUpon().clearQuery().build(),
      entryId = entryId,
      title = track.getString(PowerampAPI.Track.TITLE).orEmpty(),
      artist = track.getString(PowerampAPI.Track.ARTIST).orEmpty(),
      album = track.getString(PowerampAPI.Track.ALBUM).orEmpty(),
      path = path
    )
    stateRepository.recordPowerampEvent(
      "Radio category captured: cat=$category id=$entryId uri=${categoryUri.buildUpon().clearQuery().build()}"
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
      stateRepository.setError("找不到 Poweramp")
      return false
    }

    val intent = PowerampAPIHelper.newAPIIntent(context)
      .putExtra(PowerampAPI.EXTRA_COMMAND, command)
    configure(intent)
    val result = PowerampAPIHelper.sendPAIntent(context, intent)

    if (result) {
      stateRepository.recordPowerampEvent("api_command_sent:${commandLabel(command)}")
    } else {
      stateRepository.setError("無法傳送 Poweramp 命令 $command")
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

  private fun loadScaledCoverBase64Detailed(
    uri: Uri,
    maxDimension: Int = MAX_COVER_DIMENSION,
    quality: Int = COVER_JPEG_QUALITY
  ): CoverLoadResult {
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

      val sampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
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

      val base64 = bitmap.useScaledBase64(maxDimension, quality)
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

  private fun Bitmap.useScaledBase64(maxDimension: Int, quality: Int): String {
    val scaled = scaleBitmapIfNeeded(this, maxDimension)
    return try {
      ByteArrayOutputStream().use { output ->
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, output)
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

  private data class LibraryCoverCacheEntry(
    val base64: String,
    val hash: String
  )

  private data class LibraryQueryDefinition(
    val path: String,
    val projection: Array<String>,
    val sortOrder: String,
    val mapper: (Cursor) -> Map<String, Any?>
  ) {
    val uri: Uri get() = PowerampAPI.ROOT_URI.buildUpon().appendEncodedPath(path).build()
  }

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
    const val AUDIO_DEVICE_VOLUME_SETTLE_DELAY_MS = 350L
    // android.media.MediaMetadataRetriever.METADATA_KEY_LYRIC
    const val METADATA_KEY_LYRIC = 1000
    const val NEGATIVE_COVER_CACHE_MS = 30_000L
    const val MAX_COVER_DIMENSION = 1024
    const val COVER_JPEG_QUALITY = 95
    const val DEFAULT_LIBRARY_COVER_SIZE = 160
    const val MIN_LIBRARY_COVER_SIZE = 64
    const val MAX_LIBRARY_COVER_SIZE = 256
    const val LIBRARY_COVER_JPEG_QUALITY = 80
  }
}

private data class ActiveRadioCategory(
  val categoryUri: Uri,
  val entryId: Long,
  val title: String,
  val artist: String,
  val album: String,
  val path: String
)

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

private fun List<PowerampRadioStation>.normalizeRadioStations(): List<PowerampRadioStation> =
  asSequence()
    // Poweramp data URIs are valid OPEN_TO_PLAY targets and preserve virtual radio entries
    // whose original HTTP URLs are intentionally not exposed by Poweramp.
    .filter { it.url.isHttpUrl() || it.url.isPowerampDataUri() }
    .distinctBy { it.url.trim().lowercase() }
    .sortedBy { it.name.lowercase() }
    .toList()

private fun String.isHttpUrl(): Boolean =
  startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)

private fun String.isRadioLikePath(): Boolean =
  isHttpUrl() || startsWith("playlist:", ignoreCase = true)

private fun String.isPowerampDataUri(): Boolean =
  startsWith("content://com.maxmpz.audioplayer.data/", ignoreCase = true)

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
