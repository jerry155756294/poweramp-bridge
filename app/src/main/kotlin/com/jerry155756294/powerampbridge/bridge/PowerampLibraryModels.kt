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
