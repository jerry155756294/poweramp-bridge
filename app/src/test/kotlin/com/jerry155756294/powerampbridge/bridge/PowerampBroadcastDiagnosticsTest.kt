package com.jerry155756294.powerampbridge.bridge

import android.os.Bundle
import com.maxmpz.poweramp.player.PowerampAPI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PowerampBroadcastDiagnosticsTest {
  @Test
  fun `extract duration prefers long duration ms`() {
    val track = Bundle().apply {
      putLong(PowerampAPI.Track.DURATION_MS, 123456L)
      putInt(PowerampAPI.Track.DURATION, 99)
    }

    assertEquals(123456L, PowerampBroadcastDiagnostics.extractDurationMs(track))
  }

  @Test
  fun `extract duration accepts int duration ms`() {
    val track = Bundle().apply {
      putInt(PowerampAPI.Track.DURATION_MS, 321000)
    }

    assertEquals(321000L, PowerampBroadcastDiagnostics.extractDurationMs(track))
  }

  @Test
  fun `extract duration accepts string duration ms`() {
    val track = Bundle().apply {
      putString(PowerampAPI.Track.DURATION_MS, "654000")
    }

    assertEquals(654000L, PowerampBroadcastDiagnostics.extractDurationMs(track))
  }

  @Test
  fun `extract duration falls back to seconds when duration ms is invalid`() {
    val track = Bundle().apply {
      putString(PowerampAPI.Track.DURATION_MS, "bad-value")
      putInt(PowerampAPI.Track.DURATION, 187)
    }

    assertEquals(187000L, PowerampBroadcastDiagnostics.extractDurationMs(track))
  }

  @Test
  fun `extract duration returns zero for invalid values`() {
    val track = Bundle().apply {
      putString(PowerampAPI.Track.DURATION_MS, "not-a-number")
      putString(PowerampAPI.Track.DURATION, "still-bad")
    }

    assertEquals(0L, PowerampBroadcastDiagnostics.extractDurationMs(track))
  }

  @Test
  fun `read int returns null for out of range long`() {
    val bundle = Bundle().apply {
      putLong("value", Int.MAX_VALUE.toLong() + 1L)
    }

    assertNull(PowerampBroadcastDiagnostics.readInt(bundle, "value"))
  }

  @Test
  fun `describe extras includes nested bundle key types`() {
    val extras = Bundle().apply {
      putBundle(
        PowerampAPI.EXTRA_TRACK,
        Bundle().apply {
          putInt(PowerampAPI.Track.DURATION_MS, 123)
          putString(PowerampAPI.Track.TITLE, "Song")
        }
      )
      putInt(PowerampAPI.Track.POSITION, 42)
    }

    assertEquals(
      "[position:Integer,track:Bundle[durMs:Integer,title:String]]",
      PowerampBroadcastDiagnostics.describeExtras(extras)
    )
  }
}
