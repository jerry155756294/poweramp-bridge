package com.jerry155756294.powerampbridge.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BridgeNotificationPresenterTest {
  private val presenter = BridgeNotificationPresenter(
    textResources = NotificationTextResources(
      bridgeTitle = "Bridge Running",
      bridgeStoppedTitle = "Bridge Stopped",
      notificationNoTrack = "No track",
      stopLabel = "Stop",
      restartLabel = "Restart"
    )
  )

  @Test
  fun `same state yields same snapshot`() {
    presenter.foregroundPersistent = true
    val state = BridgeUiState(listenerActive = true, listenPort = 3000)

    val first = presenter.snapshot(state)
    val second = presenter.snapshot(state)

    assertEquals(first, second)
  }

  @Test
  fun `snapshot changes when title or summary changes`() {
    presenter.foregroundPersistent = true
    val stopped = presenter.snapshot(BridgeUiState())
    val running = presenter.snapshot(
      BridgeUiState(
        listenerActive = true,
        playback = PlaybackSnapshot(
          track = TrackSnapshot(title = "Song", artist = "Artist")
        )
      )
    )

    assertNotEquals(stopped, running)
    assertEquals("Bridge Running", running.title)
    assertEquals("Song - Artist", running.summary)
  }

  @Test
  fun `disabling persistent mode makes the notification dismissible`() {
    presenter.foregroundPersistent = false

    val snapshot = presenter.snapshot(BridgeUiState(listenerActive = true))

    assertFalse(snapshot.ongoing)
  }
}
