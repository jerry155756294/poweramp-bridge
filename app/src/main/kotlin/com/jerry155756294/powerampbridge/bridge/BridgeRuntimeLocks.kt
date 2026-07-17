package com.jerry155756294.powerampbridge.bridge

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import timber.log.Timber

/**
 * Keeps the process and Wi-Fi transport available while the Bridge foreground service is active.
 *
 * A foreground-service notification alone does not keep a CPU awake during Doze. Without these
 * locks an incoming TCP packet can remain queued until the user wakes the device, which makes a
 * remote-control command appear to execute in a burst when the app is opened.
 */
internal class BridgeRuntimeLocks(context: Context) {
  private val powerManager = context.getSystemService(PowerManager::class.java)
  private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)

  private val cpuWakeLock: PowerManager.WakeLock? = powerManager
    ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$LOCK_TAG:cpu")
    ?.apply { setReferenceCounted(false) }

  @Suppress("DEPRECATION")
  private val wifiLock: WifiManager.WifiLock? = wifiManager
    ?.createWifiLock(
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        WifiManager.WIFI_MODE_FULL_LOW_LATENCY
      } else {
        WifiManager.WIFI_MODE_FULL_HIGH_PERF
      },
      "$LOCK_TAG:wifi"
    )
    ?.apply { setReferenceCounted(false) }

  fun acquire(onEvent: (String) -> Unit) {
    acquireLock(cpuWakeLock, "cpu", onEvent)
    acquireLock(wifiLock, "wifi", onEvent)
  }

  fun release(onEvent: (String) -> Unit) {
    releaseLock(wifiLock, "wifi", onEvent)
    releaseLock(cpuWakeLock, "cpu", onEvent)
  }

  private fun acquireLock(lock: Any?, name: String, onEvent: (String) -> Unit) {
    try {
      when (lock) {
        is PowerManager.WakeLock -> if (!lock.isHeld) lock.acquire()
        is WifiManager.WifiLock -> if (!lock.isHeld) lock.acquire()
        null -> {
          onEvent("runtime_lock_unavailable:$name")
          return
        }
      }
      onEvent("runtime_lock_acquired:$name")
    } catch (error: RuntimeException) {
      Timber.w(error, "Unable to acquire Bridge %s runtime lock", name)
      onEvent("runtime_lock_acquire_failed:$name:${error.javaClass.simpleName}")
    }
  }

  private fun releaseLock(lock: Any?, name: String, onEvent: (String) -> Unit) {
    try {
      when (lock) {
        is PowerManager.WakeLock -> if (lock.isHeld) lock.release()
        is WifiManager.WifiLock -> if (lock.isHeld) lock.release()
        null -> return
      }
      onEvent("runtime_lock_released:$name")
    } catch (error: RuntimeException) {
      Timber.w(error, "Unable to release Bridge %s runtime lock", name)
      onEvent("runtime_lock_release_failed:$name:${error.javaClass.simpleName}")
    }
  }

  private companion object {
    const val LOCK_TAG = "PowerampBridge"
  }
}
