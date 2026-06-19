package com.jerry155756294.powerampbridge.bridge

class BroadcastKeepaliveTracker(
  private val idleThresholdMs: Long = DEFAULT_IDLE_THRESHOLD_MS,
  private val clock: () -> Long
) {
  private var lastActivityAtMs: Long? = null

  fun recordActivity(nowMs: Long = clock()) {
    lastActivityAtMs = nowMs
  }

  fun clear() {
    lastActivityAtMs = null
  }

  fun shouldSendKeepalive(nowMs: Long = clock()): Boolean {
    val lastActivity = lastActivityAtMs ?: return false
    return nowMs - lastActivity >= idleThresholdMs
  }

  companion object {
    const val DEFAULT_IDLE_THRESHOLD_MS = 25_000L
  }
}
