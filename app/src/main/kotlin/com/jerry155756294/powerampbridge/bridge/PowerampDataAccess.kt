package com.jerry155756294.powerampbridge.bridge

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.maxmpz.poweramp.player.PowerampAPI
import com.maxmpz.poweramp.player.PowerampAPIHelper
import com.maxmpz.poweramp.player.TableDefs
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/** Requests Poweramp's one-time grant for its ContentProvider-backed library data. */
object PowerampDataAccess {
  private const val REQUEST_COOLDOWN_MS = 30_000L
  private const val INITIAL_PROBE_DELAY_MS = 750L
  private const val RETRY_PROBE_DELAY_MS = 2_000L
  private val lastRequestAtMs = AtomicLong(0L)
  private val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  fun request(context: Context, stateRepository: BridgeStateRepository): Boolean {
    val powerampPackage = PowerampAPIHelper.getPowerampPackageName(context)
    if (powerampPackage == null) {
      stateRepository.setPowerampDataAccess(
        PowerampDataAccessStatus.FAILED,
        "找不到 Poweramp，無法要求資料存取權。"
      )
      return false
    }

    val now = SystemClock.elapsedRealtime()
    val lastRequestAt = lastRequestAtMs.getAndSet(now)
    if (now - lastRequestAt < REQUEST_COOLDOWN_MS) {
      stateRepository.setPowerampDataAccess(
        PowerampDataAccessStatus.REQUESTED,
        "已送出資料存取要求，正在驗證 Poweramp 的廣播清單。"
      )
      stateRepository.recordPowerampEvent("data_access_permission_request_throttled")
      return true
    }

    return runCatching {
      context.sendBroadcast(
        Intent(PowerampAPI.ACTION_ASK_FOR_DATA_PERMISSION)
          .setPackage(powerampPackage)
          .putExtra(PowerampAPI.EXTRA_PACKAGE, context.packageName)
      )
      stateRepository.setPowerampDataAccess(
        PowerampDataAccessStatus.REQUESTED,
        "已向 Poweramp 要求資料存取權，正在驗證廣播清單。"
      )
      stateRepository.recordPowerampEvent("data_access_permission_requested")
      verifyAfterRequest(context.applicationContext, stateRepository)
      true
    }.onFailure { error ->
      Timber.w(error, "Failed requesting Poweramp data access")
      stateRepository.setPowerampDataAccess(
        PowerampDataAccessStatus.FAILED,
        "無法要求 Poweramp 資料存取權：${error.javaClass.simpleName}"
      )
      stateRepository.recordPowerampEvent(
        "data_access_permission_request_failed:${error.javaClass.simpleName}:${error.message ?: "no-message"}"
      )
    }.getOrDefault(false)
  }

  private fun verifyAfterRequest(context: Context, stateRepository: BridgeStateRepository) {
    probeScope.launch {
      delay(INITIAL_PROBE_DELAY_MS)
      if (!probe(context, stateRepository)) {
        delay(RETRY_PROBE_DELAY_MS)
        probe(context, stateRepository)
      }
    }
  }

  private fun probe(context: Context, stateRepository: BridgeStateRepository): Boolean {
    val uri = PowerampAPI.ROOT_URI.buildUpon()
      .appendEncodedPath("streams")
      .appendQueryParameter("lim", "1")
      .build()
    return runCatching {
      requireNotNull(
        context.contentResolver.query(
          uri,
          arrayOf(TableDefs.Files._ID),
          null,
          null,
          null
        )
      ) { "Poweramp did not return a cursor" }.use { }
      stateRepository.setPowerampDataAccess(
        PowerampDataAccessStatus.AVAILABLE,
        "已確認可讀取 Poweramp 的廣播清單。"
      )
      stateRepository.recordPowerampEvent("data_access_probe_success")
      true
    }.onFailure { error ->
      Timber.w(error, "Poweramp data access probe failed")
      stateRepository.setPowerampDataAccess(
        PowerampDataAccessStatus.FAILED,
        "Poweramp 未授與資料存取權：${error.javaClass.simpleName}。請先開啟 Poweramp，再重新要求。"
      )
      stateRepository.recordPowerampEvent(
        "data_access_probe_failed:${error.javaClass.simpleName}:${error.message ?: "no-message"}"
      )
    }.getOrDefault(false)
  }
}
