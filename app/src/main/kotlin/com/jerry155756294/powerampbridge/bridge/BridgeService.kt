package com.jerry155756294.powerampbridge.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jerry155756294.powerampbridge.BridgeApplication
import com.jerry155756294.powerampbridge.MainActivity
import com.jerry155756294.powerampbridge.R
import com.jerry155756294.powerampbridge.data.BridgeSettings
import com.jerry155756294.powerampbridge.protocol.IncomingMessage
import com.jerry155756294.powerampbridge.protocol.JsonMessageCodec
import com.jerry155756294.powerampbridge.protocol.MbrcProtocolAdapter
import com.jerry155756294.powerampbridge.protocol.MbrcProtocolServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class BridgeService : Service() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private lateinit var app: BridgeApplication
  private lateinit var powerampGateway: PowerampGateway
  private lateinit var adapter: MbrcProtocolAdapter
  private lateinit var server: MbrcProtocolServer
  private var settingsJob: Job? = null
  private var stateJob: Job? = null
  private var activeSettings = BridgeSettings()
  private var startedPort: Int? = null

  override fun onCreate() {
    super.onCreate()
    app = application as BridgeApplication
    powerampGateway = PowerampGateway(this, app.appContainer.stateRepository)
    adapter = MbrcProtocolAdapter(
      codec = JsonMessageCodec(),
      stateRepository = app.appContainer.stateRepository,
      powerampGateway = powerampGateway
    )
    server = MbrcProtocolServer(JsonMessageCodec(), object : MbrcProtocolServer.Listener {
      override suspend fun onClientConnected(remoteAddress: String): Boolean {
        val allowed = allowClient(remoteAddress)
        if (allowed) {
          app.appContainer.stateRepository.setActiveClient(remoteAddress)
          adapter.onClientConnected()
        } else {
          app.appContainer.stateRepository.setActiveClient(null)
        }
        return allowed
      }

      override suspend fun onClientDisconnected(remoteAddress: String) {
        app.appContainer.stateRepository.setActiveClient(null)
        adapter.onClientDisconnected()
      }

      override suspend fun onMessage(
        remoteAddress: String,
        message: IncomingMessage
      ): List<String> = adapter.handleMessage(message, activeSettings)
    })

    createNotificationChannel()
    startForeground(NOTIFICATION_ID, buildNotification(app.appContainer.stateRepository.state.value))
    app.appContainer.stateRepository.setServiceRunning(true)
    powerampGateway.start()
    observeSettings()
    observeState()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_STOP -> stopSelf()
      ACTION_RESTART -> {
        startedPort = null
        observeSettings()
      }
    }
    return START_STICKY
  }

  override fun onDestroy() {
    settingsJob?.cancel()
    stateJob?.cancel()
    runBlocking { server.stop() }
    powerampGateway.stop()
    app.appContainer.stateRepository.setServiceRunning(false)
    app.appContainer.stateRepository.setListenerState(false, activeSettings.port)
    scope.cancel()
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun observeSettings() {
    settingsJob?.cancel()
    settingsJob = scope.launch {
      app.appContainer.settingsRepository.settings.collectLatest { settings ->
        activeSettings = settings
        if (startedPort != settings.port) {
          launch(Dispatchers.IO) {
            runCatching {
              server.start(settings.port)
              startedPort = settings.port
              app.appContainer.stateRepository.setListenerState(true, settings.port)
              app.appContainer.stateRepository.setError(null)
            }.onFailure { error ->
              app.appContainer.stateRepository.setListenerState(false, settings.port)
              app.appContainer.stateRepository.setError(error.message ?: "Failed to start listener")
            }
          }
        }
      }
    }
  }

  private fun observeState() {
    stateJob?.cancel()
    stateJob = scope.launch {
      app.appContainer.stateRepository.state.collectLatest { state ->
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(state))

        if (adapter.canBroadcastState()) {
          launch(Dispatchers.IO) {
            server.sendToActive(adapter.snapshotMessages(state))
          }
        }
      }
    }
  }

  private fun allowClient(remoteAddress: String): Boolean {
    if (!activeSettings.requireTokenForRemote || activeSettings.sharedToken.isBlank()) {
      return true
    }
    return remoteAddress == "127.0.0.1" || remoteAddress == "::1"
  }

  private fun buildNotification(state: BridgeUiState): Notification {
    val launchIntent = PendingIntent.getActivity(
      this,
      1,
      Intent(this, MainActivity::class.java),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val stopIntent = PendingIntent.getService(
      this,
      2,
      Intent(this, BridgeService::class.java).setAction(ACTION_STOP),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val restartIntent = PendingIntent.getService(
      this,
      3,
      Intent(this, BridgeService::class.java).setAction(ACTION_RESTART),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val summary = if (state.playback.track.title.isNotBlank()) {
      "${state.playback.track.title} - ${state.playback.track.artist}"
    } else {
      getString(R.string.notification_no_track)
    }

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_media_play)
      .setContentTitle(
        if (state.listenerActive) getString(R.string.bridge_notification_title)
        else getString(R.string.bridge_notification_stopped)
      )
      .setContentText(summary)
      .setSubText(
        listOfNotNull(
          "Port ${state.listenPort}",
          state.activeClient?.let { "Client $it" },
          if (state.powerampAvailable) getString(R.string.poweramp_present)
          else getString(R.string.poweramp_missing)
        ).joinToString(" | ")
      )
      .setContentIntent(launchIntent)
      .setOngoing(activeSettings.foregroundPersistent)
      .addAction(0, getString(R.string.notification_stop), stopIntent)
      .addAction(0, getString(R.string.notification_restart), restartIntent)
      .build()
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val channel = NotificationChannel(
      CHANNEL_ID,
      getString(R.string.bridge_channel_name),
      NotificationManager.IMPORTANCE_LOW
    ).apply {
      description = getString(R.string.bridge_channel_description)
    }
    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.createNotificationChannel(channel)
  }

  companion object {
    private const val CHANNEL_ID = "poweramp_bridge"
    private const val NOTIFICATION_ID = 1001
    private const val ACTION_STOP = "com.jerry155756294.powerampbridge.action.STOP"
    private const val ACTION_RESTART = "com.jerry155756294.powerampbridge.action.RESTART"

    fun start(context: Context) {
      ContextCompat.startForegroundService(
        context,
        Intent(context, BridgeService::class.java)
      )
    }

    fun stop(context: Context) {
      context.startService(
        Intent(context, BridgeService::class.java).setAction(ACTION_STOP)
      )
    }
  }
}
