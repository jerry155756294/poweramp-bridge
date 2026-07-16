package com.jerry155756294.powerampbridge.protocol

import com.jerry155756294.powerampbridge.bridge.BridgeStateRepository
import com.jerry155756294.powerampbridge.bridge.PowerampQueueItem
import com.jerry155756294.powerampbridge.bridge.PowerampRadioStation
import com.jerry155756294.powerampbridge.bridge.PowerampController
import com.jerry155756294.powerampbridge.bridge.PowerampLibraryPage
import com.jerry155756294.powerampbridge.bridge.PowerampPlaylistPage
import com.jerry155756294.powerampbridge.bridge.QueueCommandResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MbrcProtocolAdapterTest {
  private val codec = JsonMessageCodec()
  private val repository = BridgeStateRepository()
  private val controller = FakePowerampController(repository)
  private val adapter = MbrcProtocolAdapter(codec, repository, controller)

  @Test
  fun `nowplayingcover returns immediate gateway payload`() {
    controller.coverPayload = mapOf("status" to 404, "cover" to null)

    val replies = adapter.handleCommand(IncomingMessage(ProtocolConstants.NowPlayingCover, null))

    assertEquals(1, controller.coverPayloadCalls)
    assertEquals(1, replies.size)
    val json = JSONObject(replies.single())
    assertEquals(ProtocolConstants.NowPlayingCover, json.getString("context"))
    assertEquals(404, json.getJSONObject("data").getInt("status"))
    assertTrue(json.getJSONObject("data").isNull("cover"))
  }

  @Test
  fun `cached nowplayingcover payload is returned unchanged`() {
    controller.coverPayload = mapOf("status" to 200, "cover" to "abc")
    controller.coverStatus = 1

    val reply = adapter.handleCommand(IncomingMessage(ProtocolConstants.NowPlayingCover, null)).single()
    val data = JSONObject(reply).getJSONObject("data")

    assertEquals(200, data.getInt("status"))
    assertEquals("abc", data.getString("cover"))
  }

  @Test
  fun `cover status message keeps sender compatible status only payload`() {
    controller.coverStatus = 1

    val reply = adapter.coverStatusMessage()
    val data = JSONObject(reply).getJSONObject("data")

    assertEquals(1, data.getInt("status"))
    assertTrue(data.isNull("cover"))
  }

  @Test
  fun `browse and queue commands return page shaped payloads`() {
    val contexts = listOf(
      ProtocolConstants.PlaylistList,
      ProtocolConstants.BrowseGenres,
      ProtocolConstants.BrowseArtists,
      ProtocolConstants.BrowseAlbums,
      ProtocolConstants.BrowseTracks
    )

    contexts.forEach { context ->
      val reply = adapter.handleCommand(IncomingMessage(context, null)).single()
      val data = JSONObject(reply).getJSONObject("data")
      assertTrue(data.has("total"))
      assertTrue(data.has("offset"))
      assertTrue(data.has("limit"))
      assertTrue(data.has("data"))
    }
  }

  @Test
  fun `unsupported library play actions return sender safe noop`() {
    val contexts = listOf(
      ProtocolConstants.LibraryPlayAll,
      "libraryplay",
      "librarytrackplay"
    )

    contexts.forEach { context ->
      val reply = adapter.handleCommand(IncomingMessage(context, mapOf("id" to 42))).single()
      val json = JSONObject(reply)
      val data = json.getJSONObject("data")
      assertEquals(context, json.getString("context"))
      assertEquals(200, data.getInt("status"))
      assertFalse(data.getBoolean("accepted"))
      assertTrue(data.getBoolean("unsupported"))
    }
  }

  @Test
  fun `playlistlist returns Poweramp playlist urls in a paged response`() {
    controller.playlistPage = PowerampPlaylistPage(
      total = 1,
      offset = 0,
      limit = 20,
      data = listOf(
        mapOf(
          "name" to "Favorites",
          "url" to "content://com.maxmpz.audioplayer.data/playlists/7"
        )
      )
    )

    val data = JSONObject(
      adapter.handleCommand(
        IncomingMessage(ProtocolConstants.PlaylistList, mapOf("offset" to 0, "limit" to 20))
      ).single()
    ).getJSONObject("data")
    val playlist = data.getJSONArray("data").getJSONObject(0)

    assertEquals(1, data.getInt("total"))
    assertEquals(20, data.getInt("limit"))
    assertEquals("Favorites", playlist.getString("name"))
    assertEquals(
      "content://com.maxmpz.audioplayer.data/playlists/7",
      playlist.getString("url")
    )
  }

  @Test
  fun `browsealbums returns the gateway page instead of an empty compatibility page`() {
    controller.libraryPages = mapOf(
      ProtocolConstants.BrowseAlbums to PowerampLibraryPage(
        total = 1,
        offset = 0,
        limit = 800,
        data = listOf(mapOf("artist" to "Artist", "album" to "Album", "count" to 2))
      )
    )

    val data = JSONObject(adapter.handleCommand(IncomingMessage(ProtocolConstants.BrowseAlbums, null)).single())
      .getJSONObject("data")

    assertEquals(1, data.getInt("total"))
    assertEquals("Album", data.getJSONArray("data").getJSONObject(0).getString("album"))
  }

  @Test
  fun `unavailable Poweramp library page fails sync rather than reporting an empty library`() {
    controller.libraryPages = mapOf(
      ProtocolConstants.BrowseTracks to PowerampLibraryPage(0, 0, 800, emptyList(), available = false)
    )

    val reply = JSONObject(adapter.handleCommand(IncomingMessage(ProtocolConstants.BrowseTracks, null)).single())

    assertEquals(ProtocolConstants.CommandUnavailable, reply.getString("context"))
    assertEquals(ProtocolConstants.BrowseTracks, reply.getString("data"))
  }

  @Test
  fun `album drilldown returns navigation tracks`() {
    controller.libraryNavigation = mapOf(
      ProtocolConstants.LibraryAlbumTracks to listOf(
        mapOf("src" to "/music/one.mp3", "title" to "One", "album" to "Album")
      )
    )

    val data = JSONObject(
      adapter.handleCommand(
        IncomingMessage(ProtocolConstants.LibraryAlbumTracks, "Album")
      ).single()
    ).getJSONArray("data")

    assertEquals(1, data.length())
    assertEquals("/music/one.mp3", data.getJSONObject(0).getString("src"))
  }

  @Test
  fun `library cover is delegated with its hash request`() {
    controller.libraryCoverPayload = mapOf("status" to 304, "cover" to null, "hash" to "same")

    val data = JSONObject(
      adapter.handleCommand(IncomingMessage(ProtocolConstants.LibraryCover, mapOf("artist" to "A", "album" to "B", "hash" to "same"))).single()
    ).getJSONObject("data")

    assertEquals(304, data.getInt("status"))
    assertEquals("same", data.getString("hash"))
  }

  @Test
  fun `nowplayinglyrics returns the gateway payload`() {
    controller.lyricsPayload = mapOf("status" to 200, "lyrics" to "[00:01.00]First line")

    val reply = adapter.handleCommand(IncomingMessage(ProtocolConstants.NowPlayingLyrics, null)).single()
    val data = JSONObject(reply).getJSONObject("data")

    assertEquals(200, data.getInt("status"))
    assertEquals("[00:01.00]First line", data.getString("lyrics"))
  }

  @Test
  fun `nowplayingdetails keeps tag metadata and statistics from the gateway`() {
    controller.trackDetailsPayload = mapOf(
      "trackCount" to "12",
      "discCount" to "1",
      "publisher" to "Label",
      "playCount" to "15",
      "duration" to "245000"
    )

    val data = JSONObject(
      adapter.handleCommand(IncomingMessage(ProtocolConstants.NowPlayingDetails, null)).single()
    ).getJSONObject("data")

    assertEquals("12", data.getString("trackCount"))
    assertEquals("1", data.getString("discCount"))
    assertEquals("Label", data.getString("publisher"))
    assertEquals("15", data.getString("playCount"))
    assertEquals("245000", data.getString("duration"))
  }

  @Test
  fun `nowplayinglfmrating maps love to Poweramp like rating`() {
    val reply = adapter.handleCommand(
      IncomingMessage(ProtocolConstants.NowPlayingLfmRating, "love")
    ).single()

    assertEquals("love", controller.lastLfmRatingAction)
    assertEquals("Love", JSONObject(reply).getString("data"))
    assertEquals(5, repository.state.value.playback.track.rating)
  }

  @Test
  fun `nowplayinglfmrating clears love when love is requested again`() {
    repository.updatePlayback { it.copy(track = it.track.copy(rating = 5)) }

    val reply = adapter.handleCommand(
      IncomingMessage(ProtocolConstants.NowPlayingLfmRating, "love")
    ).single()

    assertEquals("Normal", JSONObject(reply).getString("data"))
    assertEquals(0, repository.state.value.playback.track.rating)
  }

  @Test
  fun `nowplayinglfmrating clears ban when ban is requested again`() {
    repository.updatePlayback { it.copy(track = it.track.copy(rating = 1)) }

    val reply = adapter.handleCommand(
      IncomingMessage(ProtocolConstants.NowPlayingLfmRating, "ban")
    ).single()

    assertEquals("Normal", JSONObject(reply).getString("data"))
    assertEquals(0, repository.state.value.playback.track.rating)
  }

  @Test
  fun `nowplayinglfmrating maps toggle to clearing the Poweramp rating`() {
    repository.updatePlayback { it.copy(track = it.track.copy(rating = 1)) }

    val reply = adapter.handleCommand(
      IncomingMessage(ProtocolConstants.NowPlayingLfmRating, ProtocolConstants.Toggle)
    ).single()

    assertEquals("toggle", controller.lastLfmRatingAction)
    assertEquals("Normal", JSONObject(reply).getString("data"))
    assertEquals(0, repository.state.value.playback.track.rating)
  }

  @Test
  fun `playlistplay without a path returns commandunavailable`() {
    val reply = adapter.handleCommand(IncomingMessage(ProtocolConstants.PlaylistPlay, mapOf("id" to 42))).single()
    val json = JSONObject(reply)

    assertEquals(ProtocolConstants.CommandUnavailable, json.getString("context"))
    assertEquals(ProtocolConstants.PlaylistPlay, json.getString("data"))
  }

  @Test
  fun `playlistplay returns true after opening the Poweramp playlist uri`() {
    val path = "content://com.maxmpz.audioplayer.data/playlists/7"

    val reply = adapter.handleCommand(
      IncomingMessage(ProtocolConstants.PlaylistPlay, mapOf("url" to path))
    ).single()

    assertEquals(ProtocolConstants.PlaylistPlay, JSONObject(reply).getString("context"))
    assertTrue(JSONObject(reply).getBoolean("data"))
    assertEquals(path, controller.playedPath)
  }

  @Test
  fun `unsupported library play actions do not fall through to unknowncommand`() {
    val reply = adapter.handleCommand(IncomingMessage("libraryplay", mapOf("id" to 42))).single()

      assertEquals("libraryplay", JSONObject(reply).getString("context"))
  }

  @Test
  fun `nowplayingqueue returns poweramp queue items`() {
    controller.queueItems = listOf(
      PowerampQueueItem(
        queueId = 10L,
        fileId = 101L,
        title = "Track A",
        artist = "Artist A",
        album = "Album A",
        path = "/music/a.mp3"
      )
    )

    val reply = adapter.handleCommand(IncomingMessage(ProtocolConstants.NowPlayingQueue, null)).single()
    val data = JSONObject(reply).getJSONObject("data")
    val item = data.getJSONArray("data").getJSONObject(0)

    assertEquals(1, data.getInt("total"))
    assertEquals("Track A", item.getString("title"))
    assertEquals(10L, item.getLong("id"))
    assertEquals(101L, item.getLong("file_id"))
    assertEquals(1, item.getInt("position"))
  }

  @Test
  fun `nowplayingqueue returns the active Poweramp category instead of stale manual queue`() {
    controller.queueItems = listOf(
      PowerampQueueItem(queueId = 10L, fileId = 101L, title = "Stale queue item")
    )
    controller.nowPlayingItems = listOf(
      PowerampQueueItem(
        fileId = 202L,
        title = "Album category item",
        artist = "Artist",
        album = "Album",
        path = "/music/category.flac",
        playUri = "content://com.maxmpz.audioplayer.data/albums/2/files/202"
      )
    )

    val reply = adapter.handleCommand(IncomingMessage(ProtocolConstants.NowPlayingQueue, null)).single()
    val item = JSONObject(reply).getJSONObject("data").getJSONArray("data").getJSONObject(0)

    assertEquals("Album category item", item.getString("title"))
    assertEquals(202L, item.getLong("id"))
  }

  @Test
  fun `nowplayinglistplay dispatches into the active Poweramp category`() {
    controller.nowPlayingItems = listOf(
      PowerampQueueItem(fileId = 202L, title = "Album category item", playUri = "content://example/files/202")
    )

    adapter.handleCommand(
      IncomingMessage(ProtocolConstants.NowPlayingListPlay, 1)
    )

    assertEquals(1, controller.playedQueuePosition)
  }

  @Test
  fun `nowplaying list changed message uses the mbrc context`() {
    val message = JSONObject(adapter.nowPlayingListChangedMessage())

    assertEquals(ProtocolConstants.NowPlayingListChanged, message.getString("context"))
    assertEquals(true, message.getBoolean("data"))
  }

  @Test
  fun `nowplayingqueue falls back to current track when queue is empty`() {
    repository.updatePlayback {
      it.copy(
        track = it.track.copy(
          realId = 77L,
          title = "Fallback Track",
          artist = "Fallback Artist",
          album = "Fallback Album",
          path = "/music/fallback.mp3"
        )
      )
    }

    val reply = adapter.handleCommand(IncomingMessage(ProtocolConstants.NowPlayingQueue, null)).single()
    val data = JSONObject(reply).getJSONObject("data")
    val item = data.getJSONArray("data").getJSONObject(0)

    assertEquals(1, data.getInt("total"))
    assertEquals("Fallback Track", item.getString("title"))
    assertEquals(77L, item.getLong("file_id"))
  }

  @Test
  fun `nowplayingqueue returns empty page when queue and current track are empty`() {
    val reply = adapter.handleCommand(IncomingMessage(ProtocolConstants.NowPlayingQueue, null)).single()
    val data = JSONObject(reply).getJSONObject("data")

    assertEquals(0, data.getInt("total"))
    assertEquals(0, data.getJSONArray("data").length())
  }

  @Test
  fun `nowplayingqueue queue command returns code payload`() {
    controller.queueCommandResult = QueueCommandResult(
      code = 200,
      accepted = true,
      detail = "played"
    )

    val reply = adapter.handleCommand(
      IncomingMessage(
        ProtocolConstants.NowPlayingQueue,
        mapOf("queue" to "now", "data" to listOf("https://radio.example/stream"))
      )
    ).single()

    val data = JSONObject(reply).getJSONObject("data")
    assertEquals(200, data.getInt("code"))
    assertEquals("now", controller.lastQueueCommandType)
    assertEquals(listOf("https://radio.example/stream"), controller.lastQueueCommandPaths)
  }

  @Test
  fun `radiostations returns poweramp streams`() {
    controller.radioStations = listOf(
      PowerampRadioStation(
        streamId = 5L,
        name = "Station One",
        url = "https://radio.example/1",
        artist = "DJ",
        album = "Live"
      )
    )

    val reply = adapter.handleCommand(IncomingMessage(ProtocolConstants.RadioStations, null)).single()
    val data = JSONObject(reply).getJSONObject("data")
    val item = data.getJSONArray("data").getJSONObject(0)

    assertEquals(1, data.getInt("total"))
    assertEquals("Station One", item.getString("name"))
    assertEquals("https://radio.example/1", item.getString("url"))
    assertFalse(item.has("id"))
  }

  private class FakePowerampController(
    private val repository: BridgeStateRepository
  ) : PowerampController {
    var coverStatus: Int = 404
    var coverPayload: Map<String, Any?> = mapOf("status" to 404, "cover" to null)
    var lyricsPayload: Map<String, Any> = mapOf("status" to 404, "lyrics" to "")
    var trackDetailsPayload: Map<String, String> = emptyMap()
    var coverPayloadCalls = 0
    var nowPlayingItems: List<PowerampQueueItem> = emptyList()
    var queueItems: List<PowerampQueueItem> = emptyList()
    var radioStations: List<PowerampRadioStation> = emptyList()
    var libraryPages: Map<String, PowerampLibraryPage> = emptyMap()
    var playlistPage: PowerampPlaylistPage = PowerampPlaylistPage(0, 0, 800, emptyList())
    var libraryNavigation: Map<String, List<Map<String, Any?>>> = emptyMap()
    var libraryCoverPayload: Map<String, Any?> = mapOf("status" to 404, "cover" to null)
    var queueCommandResult: QueueCommandResult = QueueCommandResult(200, true, "ok")
    var lastQueueCommandType: String? = null
    var lastQueueCommandPaths: List<String>? = null
    var lastQueueCommandPlayPath: String? = null
    var playedQueuePosition: Int? = null
    var playedPath: String? = null
    var lastLfmRatingAction: String? = null

    override fun currentCoverStatus(): Int = coverStatus
    override fun currentCoverPayload(): Map<String, Any?> {
      coverPayloadCalls += 1
      return coverPayload
    }
    override fun currentLyricsPayload(): Map<String, Any> = lyricsPayload
    override fun currentTrackDetailsPayload(): Map<String, String> = trackDetailsPayload
    override fun readNowPlayingItems(): List<PowerampQueueItem> = nowPlayingItems.ifEmpty { queueItems }
    override fun readQueueItems(): List<PowerampQueueItem> = queueItems
    override fun readRadioStations(): List<PowerampRadioStation> = radioStations
    override fun readLibraryPage(context: String, offset: Int, limit: Int): PowerampLibraryPage =
      libraryPages[context] ?: PowerampLibraryPage(0, offset, limit, emptyList())
    override fun readPlaylistPage(offset: Int, limit: Int): PowerampPlaylistPage = playlistPage
    override fun readLibraryNavigation(context: String, query: String): List<Map<String, Any?>> =
      libraryNavigation[context].orEmpty()
    override fun readLibraryCover(request: Map<*, *>?): Map<String, Any?> = libraryCoverPayload

    override fun playPause(): Boolean = true
    override fun play(): Boolean = true
    override fun pause(): Boolean = true
    override fun stopPlayback(): Boolean = true
    override fun next(): Boolean = true
    override fun previous(): Boolean = true
    override fun playQueuePosition(position: Int): Boolean {
      playedQueuePosition = position
      return true
    }
    override fun playPath(path: String): Boolean {
      playedPath = path
      return true
    }
    override fun handleQueueCommand(
      type: String,
      paths: List<String>,
      playPath: String?
    ): QueueCommandResult {
      lastQueueCommandType = type
      lastQueueCommandPaths = paths
      lastQueueCommandPlayPath = playPath
      return queueCommandResult
    }
    override fun setVolume(volumePercent: Int) = Unit
    override fun refreshVolumeSnapshot() = Unit
    override fun seekTo(positionMs: Long): Boolean = true
    override fun setLfmRating(action: String): Boolean {
      lastLfmRatingAction = action
      val currentRating = repository.state.value.playback.track.rating
      val rating = when (action) {
        "love" -> if (currentRating == 5) 0 else 5
        "ban" -> if (currentRating == 1) 0 else 1
        else -> 0
      }
      repository.updatePlayback { playback ->
        playback.copy(track = playback.track.copy(rating = rating))
      }
      return true
    }
    override fun toggleShuffle(): Boolean = true
    override fun setShuffle(mode: String): Boolean = true
    override fun toggleRepeat(): Boolean = true
    override fun setRepeat(mode: String): Boolean = true
  }
}
