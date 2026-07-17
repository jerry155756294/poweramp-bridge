package com.jerry155756294.powerampbridge.bridge

import android.content.Context
import android.content.Intent
import com.maxmpz.poweramp.player.PowerampAPI
import com.maxmpz.poweramp.player.PowerampAPIHelper
import com.maxmpz.poweramp.player.TableDefs
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/** Keeps requesting and probing Poweramp's library grant until the provider is readable. */
object PowerampDataAccess {
  private const val RETRY_INTERVAL_MS = 1_500L
  private const val LOG_EVERY_ATTEMPTS = 20
  private val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val granted = AtomicBoolean(false)
  private var retryJob: Job? = null
  private var appContext: Context? = null
  private var repository: BridgeStateRepository? = null

  @Synchronized
  fun start(context: Context, stateRepository: BridgeStateRepository) {
    appContext = context.applicationContext
    repository = stateRepository
    if (!granted.get()) startRetryLoop()
  }

  @Synchronized
  fun stop() {
    retryJob?.cancel()
    retryJob = null
  }

  @Synchronized
  fun markAvailable() {
    if (granted.compareAndSet(false, true)) {
      repository?.recordPowerampEvent("data_access_confirmed")
    }
  }

  @Synchronized
  fun markUnavailable() {
    if (granted.compareAndSet(true, false)) {
      repository?.recordPowerampEvent("data_access_lost_retrying")
    }
    if (appContext != null && repository != null) startRetryLoop()
  }

  @Synchronized
  private fun startRetryLoop() {
    if (retryJob?.isActive == true || granted.get()) return
    val context = appContext ?: return
    val stateRepository = repository ?: return
    retryJob = probeScope.launch {
      var attempts = 0
      while (isActive && !granted.get()) {
        attempts += 1
        if (probe(context)) {
          markAvailable()
          break
        }
        requestPermission(context)
        if (attempts == 1 || attempts % LOG_EVERY_ATTEMPTS == 0) {
          stateRepository.recordPowerampEvent("data_access_retry:attempt=$attempts")
        }
        delay(RETRY_INTERVAL_MS)
      }
    }
  }

  private fun requestPermission(context: Context) {
    val powerampPackage = PowerampAPIHelper.getPowerampPackageName(context) ?: return
    runCatching {
      context.sendBroadcast(
        Intent(PowerampAPI.ACTION_ASK_FOR_DATA_PERMISSION)
          .setPackage(powerampPackage)
          .putExtra(PowerampAPI.EXTRA_PACKAGE, context.packageName)
      )
    }.onFailure { error ->
      Timber.w(error, "Failed requesting Poweramp data access")
    }
  }

  private fun probe(context: Context): Boolean {
    if (PowerampAPIHelper.getPowerampPackageName(context) == null) return false
    val uri = PowerampAPI.ROOT_URI.buildUpon()
      .appendEncodedPath("albums")
      .build()
    return runCatching {
      requireNotNull(
        context.contentResolver.query(
          uri,
          arrayOf(TableDefs.Albums._ID, TableDefs.Albums.ALBUM),
          null,
          null,
          TableDefs.Albums.ALBUM
        )
      ) { "Poweramp did not return a cursor for /albums" }.use { cursor ->
        repository?.recordProtocolEvent("data_access_probe_rows:${cursor.count}")
      }
      repository?.recordPowerampEvent("data_access_probe_success")
      true
    }.getOrDefault(false)
  }
}
