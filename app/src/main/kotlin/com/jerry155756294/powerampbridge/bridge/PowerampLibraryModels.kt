package com.jerry155756294.powerampbridge.bridge

data class PowerampQueueItem(
  val queueId: Long? = null,
  val fileId: Long? = null,
  val title: String = "",
  val artist: String = "",
  val album: String = "",
  val path: String = ""
)

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
