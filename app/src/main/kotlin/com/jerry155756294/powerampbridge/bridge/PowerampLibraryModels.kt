package com.jerry155756294.powerampbridge.bridge

data class PowerampQueueItem(
  val queueId: Long? = null,
  val fileId: Long? = null,
  val title: String = "",
  val artist: String = "",
  val album: String = "",
  val path: String = "",
  /** Canonical Poweramp category URI used to play this item in its current context. */
  val playUri: String? = null
)

/** Local Poweramp identity resolved from a path sent by mbrc. */
internal data class PowerampLibraryTrackRef(
  val source: String,
  val fileId: Long,
  val albumId: Long
)

/**
 * mbrc's queue request only carries paths. This planner converts the path set into the
 * richest Poweramp playback context that can be inferred without changing the sender protocol.
 */
internal sealed interface QueuePlaybackPlan {
  data class AllTracks(val target: PowerampLibraryTrackRef) : QueuePlaybackPlan
  data class Album(val target: PowerampLibraryTrackRef) : QueuePlaybackPlan
  data class RebuildQueue(
    val tracks: List<PowerampLibraryTrackRef>,
    val target: PowerampLibraryTrackRef
  ) : QueuePlaybackPlan

  data class DirectPath(val path: String) : QueuePlaybackPlan
}

internal object QueuePlaybackPlanner {
  fun create(
    paths: List<String>,
    playPath: String?,
    resolvedTracks: List<PowerampLibraryTrackRef>
  ): QueuePlaybackPlan? {
    val normalizedPaths = paths.map(String::trim).filter(String::isNotEmpty)
    if (normalizedPaths.isEmpty()) return null

    val targetPath = playPath?.trim()?.takeIf { requested ->
      normalizedPaths.any { it.equals(requested, ignoreCase = true) }
    } ?: normalizedPaths.first()
    val target = resolvedTracks.firstOrNull { it.source.equals(targetPath, ignoreCase = true) }
      ?: resolvedTracks.firstOrNull()

    if (normalizedPaths.size == 1) {
      return target?.let(QueuePlaybackPlan::AllTracks)
        ?: QueuePlaybackPlan.DirectPath(targetPath)
    }

    // A partial local lookup must not clear the existing Poweramp queue: that would make an
    // unsupported source path destructive. Callers can return a normal command failure instead.
    if (resolvedTracks.size != normalizedPaths.size || target == null) return null

    val albumIds = resolvedTracks.map(PowerampLibraryTrackRef::albumId).toSet()
    return if (albumIds.size == 1 && albumIds.single() > 0L) {
      QueuePlaybackPlan.Album(target)
    } else {
      QueuePlaybackPlan.RebuildQueue(resolvedTracks, target)
    }
  }
}

/**
 * Builds a replacement queue without changing the current playback command.
 *
 * Poweramp's queue provider does not expose an insert-at-position operation, so the bridge
 * preserves the existing file IDs and writes the requested order back as one queue update.
 */
internal object QueueInsertionPlanner {
  fun queueNext(
    existingFileIds: List<Long>,
    currentFileId: Long,
    addedFileIds: List<Long>
  ): List<Long>? {
    if (currentFileId <= 0L || addedFileIds.isEmpty()) return null
    val currentIndex = existingFileIds.indexOf(currentFileId)
    if (currentIndex < 0) return null
    return buildList(existingFileIds.size + addedFileIds.size) {
      addAll(existingFileIds.take(currentIndex + 1))
      addAll(addedFileIds)
      addAll(existingFileIds.drop(currentIndex + 1))
    }
  }

  fun queueLast(existingFileIds: List<Long>, addedFileIds: List<Long>): List<Long>? =
    addedFileIds.takeIf { it.isNotEmpty() }?.let { existingFileIds + it }
}

/** mbrc has one genre field per track; retain the same deterministic primary genre everywhere. */
internal fun primaryGenre(first: String, second: String): String =
  sequenceOf(first, second)
    .map(String::trim)
    .filter(String::isNotEmpty)
    .minWithOrNull(String.CASE_INSENSITIVE_ORDER)
    .orEmpty()

data class PowerampRadioStation(
  val streamId: Long,
  val name: String,
  val url: String,
  val artist: String = "",
  val album: String = ""
)

data class QueueCommandResult(
  val code: Int,
  val accepted: Boolean,
  val detail: String
)

/** A read-only MBRC library response. A failure is deliberately distinct from an empty page. */
data class PowerampLibraryPage(
  val total: Int,
  val offset: Int,
  val limit: Int,
  val data: List<Map<String, Any?>>,
  val available: Boolean = true
)

/** A read-only MBRC playlist response backed by Poweramp's playlist provider. */
data class PowerampPlaylistPage(
  val total: Int,
  val offset: Int,
  val limit: Int,
  val data: List<Map<String, Any?>>,
  val available: Boolean = true
)

/**
 * Poweramp broadcasts positions as whole seconds while the bridge ticker advances in smaller
 * increments. Ignore small corrections during playback so the sender sees a monotonic clock.
 */
internal object PositionCorrectionPolicy {
  const val SMALL_DRIFT_MS = 1_000L

  fun shouldApply(
    currentPositionMs: Long,
    incomingPositionMs: Long,
    playbackState: String,
    force: Boolean = false
  ): Boolean = force ||
    playbackState != "playing" ||
    kotlin.math.abs(incomingPositionMs - currentPositionMs) > SMALL_DRIFT_MS
}
