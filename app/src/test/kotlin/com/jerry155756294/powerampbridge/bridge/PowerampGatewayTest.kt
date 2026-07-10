package com.jerry155756294.powerampbridge.bridge

import android.content.Intent
import android.os.Bundle
import com.maxmpz.poweramp.player.PowerampAPI
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PowerampGatewayTest {
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
