package com.jerry155756294.powerampbridge.bridge

interface PowerampController {
  fun currentCoverStatus(): Int
  fun currentCoverPayload(): Map<String, Any?>
  /** Returns the active track's lyrics in MBRC v3+ payload form. */
  fun currentLyricsPayload(): Map<String, Any>
  fun readQueueItems(): List<PowerampQueueItem>
  fun readRadioStations(): List<PowerampRadioStation>
  fun readLibraryPage(context: String, offset: Int, limit: Int): PowerampLibraryPage
  fun readLibraryCover(request: Map<*, *>?): Map<String, Any?>
  fun playPause(): Boolean
  fun play(): Boolean
  fun pause(): Boolean
  fun stopPlayback(): Boolean
  fun next(): Boolean
  fun previous(): Boolean
  fun playQueuePosition(position: Int): Boolean
  fun playPath(path: String): Boolean
  fun handleQueueCommand(type: String, paths: List<String>, playPath: String? = null): QueueCommandResult
  fun setVolume(volumePercent: Int)
  fun refreshVolumeSnapshot()
  fun seekTo(positionMs: Long): Boolean
  fun setLfmRating(action: String): Boolean
  fun toggleShuffle(): Boolean
  fun setShuffle(mode: String): Boolean
  fun toggleRepeat(): Boolean
  fun setRepeat(mode: String): Boolean
}
