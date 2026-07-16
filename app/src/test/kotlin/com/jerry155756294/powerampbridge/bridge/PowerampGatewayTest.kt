package com.jerry155756294.powerampbridge.bridge

import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.os.Bundle
import com.maxmpz.poweramp.player.PowerampAPI
import com.maxmpz.poweramp.player.TableDefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PowerampGatewayTest {
  @Test
  fun `position correction ignores small playing drift`() {
    assertFalse(
      PositionCorrectionPolicy.shouldApply(
        currentPositionMs = 10_000L,
        incomingPositionMs = 9_000L,
        playbackState = "playing"
      )
    )
    assertTrue(
      PositionCorrectionPolicy.shouldApply(
        currentPositionMs = 10_000L,
        incomingPositionMs = 8_500L,
        playbackState = "playing"
      )
    )
  }

  @Test
  fun `position correction applies exact position while paused or forced`() {
    assertTrue(
      PositionCorrectionPolicy.shouldApply(
        currentPositionMs = 10_000L,
        incomingPositionMs = 9_000L,
        playbackState = "paused"
      )
    )
    assertTrue(
      PositionCorrectionPolicy.shouldApply(
        currentPositionMs = 10_000L,
        incomingPositionMs = 10_250L,
        playbackState = "playing",
        force = true
      )
    )
  }

  @Test
  fun `library track projection uses local music metadata columns instead of stream tags`() {
    val gateway = PowerampGateway(RuntimeEnvironment.getApplication(), BridgeStateRepository())
    val projectionMethod = PowerampGateway::class.java.getDeclaredMethod("libraryTrackProjection")
      .apply { isAccessible = true }

    val projection = projectionMethod.invoke(gateway) as Array<*>

    assertTrue(projection.contains("${TableDefs.Artists.ARTIST} AS artist"))
    assertTrue(projection.contains("${TableDefs.Albums.ALBUM} AS album"))
    assertTrue(projection.contains("${TableDefs.Files.YEAR} AS year"))
    assertFalse(projection.contains("${TableDefs.Files.ARTIST_TAG} AS artist"))
    assertFalse(projection.contains("${TableDefs.Files.ALBUM_TAG} AS album"))
  }

  @Test
  @Suppress("UNCHECKED_CAST")
  fun `library track payload keeps local metadata fields`() {
    val gateway = PowerampGateway(RuntimeEnvironment.getApplication(), BridgeStateRepository())
    val cursor = MatrixCursor(
      arrayOf("file_id", "src", "album_id", "artist", "title", "trackno", "disc", "album", "year")
    ).apply {
      addRow(arrayOf<Any?>(42L, "/music/one.flac", 0L, "Artist", "Song", 2L, 1L, "Album", "2024"))
      assertTrue(moveToFirst())
    }
    @Suppress("UNCHECKED_CAST")
    val genreCache = PowerampGateway::class.java.getDeclaredField("libraryGenreCache")
      .apply { isAccessible = true }
      .get(gateway) as MutableMap<Long, String>
    genreCache[42L] = "Jazz"
    val payloadMethod = PowerampGateway::class.java.getDeclaredMethod(
      "libraryTrackPayload",
      Cursor::class.java
    ).apply { isAccessible = true }

    val payload = payloadMethod.invoke(gateway, cursor) as Map<String, Any?>

    assertEquals("Artist", payload["artist"])
    assertEquals("Artist", payload["album_artist"])
    assertEquals("Album", payload["album"])
    assertEquals("2024", payload["year"])
    assertEquals("Jazz", payload["genre"])
    assertEquals("42", payload["real_id"])
    assertEquals("0", payload["album_id"])
    assertTrue((payload["library_source_id"] as? String).orEmpty().isNotBlank())
  }

  @Test
  fun `duplicate track broadcast keeps cached cover`() {
    val repository = BridgeStateRepository().apply {
      updatePlayback { playback ->
        playback.copy(track = playback.track.copy(realId = 42L, title = "Song"))
      }
      updateCoverState(
        CoverSnapshot(
          realId = 42L,
          status = CoverStateStatus.READY,
          base64 = "cached-cover"
        )
      )
    }
    val coverBefore = repository.state.value.coverState
    val revisionBefore = repository.state.value.coverSignalRevision
    val gateway = PowerampGateway(RuntimeEnvironment.getApplication(), repository)
    val intent = Intent(PowerampAPI.ACTION_TRACK_CHANGED).apply {
      putExtra(
        PowerampAPI.EXTRA_TRACK,
        Bundle().apply {
          putLong(PowerampAPI.Track.REAL_ID, 42L)
          putString(PowerampAPI.Track.TITLE, "Song")
        }
      )
    }

    val handleTrack = PowerampGateway::class.java.getDeclaredMethod(
      "handleTrack",
      Intent::class.java,
      String::class.java
    ).apply { isAccessible = true }
    handleTrack.invoke(gateway, intent, "TRACK_CHANGED")

    assertEquals(coverBefore, repository.state.value.coverState)
    assertEquals(revisionBefore, repository.state.value.coverSignalRevision)
  }
}
