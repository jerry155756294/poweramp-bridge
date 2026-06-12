package com.jerry155756294.powerampbridge.bridge

interface PowerampController {
  fun currentCoverStatus(): Int
  fun currentCoverPayload(): Map<String, Any?>
  fun playPause(): Boolean
  fun play(): Boolean
  fun pause(): Boolean
  fun stopPlayback(): Boolean
  fun next(): Boolean
  fun previous(): Boolean
  fun setVolume(volumePercent: Int)
  fun refreshVolumeSnapshot()
  fun seekTo(positionMs: Long): Boolean
  fun toggleShuffle(): Boolean
  fun setShuffle(mode: String): Boolean
  fun toggleRepeat(): Boolean
  fun setRepeat(mode: String): Boolean
}
