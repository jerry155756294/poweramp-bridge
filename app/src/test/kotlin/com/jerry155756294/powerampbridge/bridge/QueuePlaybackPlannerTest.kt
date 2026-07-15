package com.jerry155756294.powerampbridge.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueuePlaybackPlannerTest {
  @Test
  fun `single local track uses all tracks category`() {
    val track = track("/music/one.flac", fileId = 11L, albumId = 7L)

    val plan = QueuePlaybackPlanner.create(listOf(track.source), track.source, listOf(track))

    assertEquals(QueuePlaybackPlan.AllTracks(track), plan)
  }

  @Test
  fun `same album selection uses album category and requested target`() {
    val first = track("/music/one.flac", fileId = 11L, albumId = 7L)
    val second = track("/music/two.flac", fileId = 12L, albumId = 7L)

    val plan = QueuePlaybackPlanner.create(
      paths = listOf(first.source, second.source),
      playPath = second.source,
      resolvedTracks = listOf(first, second)
    )

    assertEquals(QueuePlaybackPlan.Album(second), plan)
  }

  @Test
  fun `mixed album selection rebuilds queue in sender order`() {
    val first = track("/music/one.flac", fileId = 11L, albumId = 7L)
    val second = track("/music/two.flac", fileId = 12L, albumId = 8L)

    val plan = QueuePlaybackPlanner.create(
      paths = listOf(first.source, second.source),
      playPath = second.source,
      resolvedTracks = listOf(first, second)
    )

    assertEquals(QueuePlaybackPlan.RebuildQueue(listOf(first, second), second), plan)
  }

  @Test
  fun `partially resolved multi track selection does not replace existing queue`() {
    val first = track("/music/one.flac", fileId = 11L, albumId = 7L)

    val plan = QueuePlaybackPlanner.create(
      paths = listOf(first.source, "/music/missing.flac"),
      playPath = first.source,
      resolvedTracks = listOf(first)
    )

    assertNull(plan)
  }

  @Test
  fun `single unresolved path remains directly playable for streams`() {
    val plan = QueuePlaybackPlanner.create(
      paths = listOf("https://radio.example/stream"),
      playPath = null,
      resolvedTracks = emptyList()
    )

    assertEquals(QueuePlaybackPlan.DirectPath("https://radio.example/stream"), plan)
  }

  @Test
  fun `genre mapper retains alphabetically first primary genre`() {
    assertEquals("Ambient", primaryGenre("Rock", "Ambient"))
    assertEquals("Jazz", primaryGenre("", "Jazz"))
  }

  private fun track(source: String, fileId: Long, albumId: Long) =
    PowerampLibraryTrackRef(source = source, fileId = fileId, albumId = albumId)
}
