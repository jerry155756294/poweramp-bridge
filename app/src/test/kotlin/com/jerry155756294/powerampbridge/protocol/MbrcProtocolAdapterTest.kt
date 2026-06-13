package com.jerry155756294.powerampbridge.protocol

import com.jerry155756294.powerampbridge.bridge.BridgeStateRepository
import com.jerry155756294.powerampbridge.bridge.PowerampController
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
      ProtocolConstants.NowPlayingQueue,
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
      ProtocolConstants.PlaylistPlay,
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
  fun `unsupported library play actions do not fall through to unknowncommand`() {
    val reply = adapter.handleCommand(IncomingMessage("libraryplay", mapOf("id" to 42))).single()

    assertEquals("libraryplay", JSONObject(reply).getString("context"))
  }

  private class FakePowerampController : PowerampController {
    var coverStatus: Int = 404
    var coverPayload: Map<String, Any?> = mapOf("status" to 404, "cover" to null)
    var coverPayloadCalls = 0

    override fun currentCoverStatus(): Int = coverStatus
    override fun currentCoverPayload(): Map<String, Any?> {
      coverPayloadCalls += 1
      return coverPayload
    }

    override fun playPause(): Boolean = true
    override fun play(): Boolean = true
    override fun pause(): Boolean = true
    override fun stopPlayback(): Boolean = true
    override fun next(): Boolean = true
    override fun previous(): Boolean = true
    override fun setVolume(volumePercent: Int) = Unit
    override fun refreshVolumeSnapshot() = Unit
    override fun seekTo(positionMs: Long): Boolean = true
    override fun toggleShuffle(): Boolean = true
    override fun setShuffle(mode: String): Boolean = true
    override fun toggleRepeat(): Boolean = true
    override fun setRepeat(mode: String): Boolean = true
  }
}
