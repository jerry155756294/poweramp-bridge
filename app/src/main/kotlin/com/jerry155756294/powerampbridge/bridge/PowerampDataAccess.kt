package com.jerry155756294.powerampbridge.bridge

import android.content.Context
import android.content.Intent
import com.maxmpz.poweramp.player.PowerampAPI
import com.maxmpz.poweramp.player.PowerampAPIHelper
import timber.log.Timber

/** Requests Poweramp's one-time grant for its ContentProvider-backed library data. */
object PowerampDataAccess {
  fun request(context: Context, stateRepository: BridgeStateRepository): Boolean {
    val powerampPackage = PowerampAPIHelper.getPowerampPackageName(context)
    if (powerampPackage == null) {
      stateRepository.setPowerampDataAccess(
        PowerampDataAccessStatus.FAILED,
        "找不到 Poweramp，無法要求資料存取權。"
      )
      return false
    }

    return runCatching {
      context.sendBroadcast(
        Intent(PowerampAPI.ACTION_ASK_FOR_DATA_PERMISSION)
          .setPackage(powerampPackage)
          .putExtra(PowerampAPI.EXTRA_PACKAGE, context.packageName)
      )
      stateRepository.setPowerampDataAccess(
        PowerampDataAccessStatus.REQUESTED,
        "已向 Poweramp 要求資料存取權；請確認 Poweramp 正在執行。"
      )
      stateRepository.recordPowerampEvent("data_access_permission_requested")
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
}
