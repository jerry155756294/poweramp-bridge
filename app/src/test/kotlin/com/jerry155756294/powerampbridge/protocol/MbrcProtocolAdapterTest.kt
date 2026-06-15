package com.jerry155756294.powerampbridge.protocol

import com.jerry155756294.powerampbridge.bridge.BridgeStateRepository
import com.jerry155756294.powerampbridge.bridge.PowerampQueueItem
import com.jerry155756294.powerampbridge.bridge.PowerampRadioStation
import com.jerry155756294.powerampbridge.bridge.PowerampController
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
  private val controller = FakePowerampController()
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
  fun `playlistplay without a path returns commandunavailable`() {
    val reply = adapter.handleCommand(IncomingMessage(ProtocolConstants.PlaylistPlay, mapOf("id" to 42))).single()
    val json = JSONObject(reply)

    assertEquals(ProtocolConstants.CommandUnavailable, json.getString("context"))
    assertEquals(ProtocolConstants.PlaylistPlay, json.getString("data"))
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
    assertEquals(5L, item.getLong("id"))
  }

  private class FakePowerampController : PowerampController {
    var coverStatus: Int = 404
    var coverPayload: Map<String, Any?> = mapOf("status" to 404, "cover" to null)
    var coverPayloadCalls = 0
    var queueItems: List<PowerampQueueItem> = emptyList()
    var radioStations: List<PowerampRadioStation> = emptyList()
    var queueCommandResult: QueueCommandResult = QueueCommandResult(200, true, "ok")
    var lastQueueCommandType: String? = null
    var lastQueueCommandPaths: List<String>? = null
    var lastQueueCommandPlayPath: String? = null
    var playedQueuePosition: Int? = null
    var playedPath: String? = null

    override fun currentCoverStatus(): Int = coverStatus
    override fun currentCoverPayload(): Map<String, Any?> {
      coverPayloadCalls += 1
      return coverPayload
    }
    override fun readQueueItems(): List<PowerampQueueItem> = queueItems
    override fun readRadioStations(): List<PowerampRadioStation> = radioStations

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
    override fun toggleShuffle(): Boolean = true
    override fun setShuffle(mode: String): Boolean = true
    override fun toggleRepeat(): Boolean = true
    override fun setRepeat(mode: String): Boolean = true
  }
}
