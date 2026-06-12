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
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jerry155756294.powerampbridge.BridgeApplication
import com.jerry155756294.powerampbridge.MainActivity
import com.jerry155756294.powerampbridge.R
import com.jerry155756294.powerampbridge.data.BridgeSettings
import com.jerry155756294.powerampbridge.protocol.IncomingMessage
import com.jerry155756294.powerampbridge.protocol.JsonMessageCodec
import com.jerry155756294.powerampbridge.protocol.LogicalClientSnapshot
import com.jerry155756294.powerampbridge.protocol.ConnectionDebugSnapshot
import com.jerry155756294.powerampbridge.protocol.MbrcProtocolAdapter
import com.jerry155756294.powerampbridge.protocol.MbrcProtocolServer
import com.jerry155756294.powerampbridge.protocol.ProtocolClientInfo
import com.jerry155756294.powerampbridge.protocol.ProtocolConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timber.log.Timber

class BridgeService : Service() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private lateinit var app: BridgeApplication
  private lateinit var powerampGateway: PowerampGateway
  private lateinit var adapter: MbrcProtocolAdapter
  private lateinit var server: MbrcProtocolServer
  private var settingsJob: Job? = null
  private var stateJob: Job? = null
  private var positionSyncJob: Job? = null
  private var activeSettings = BridgeSettings()
  private var startedPort: Int? = null
  private var lastStatusBroadcastPayload: List<String>? = null
  private var lastBroadcastPositionMs: Long? = null
  private var lastCoverSignalTrackId: Long? = null
  private var lastCoverSignalRevision: Long? = null
  private var pendingLatencyMeasurement: PendingLatencyMeasurement? = null
  private var latencyTimeoutJob: Job? = null
  private var lastObservedState: BridgeUiState? = null

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
      override suspend fun onCommand(
        clientInfo: ProtocolClientInfo,
        message: IncomingMessage
      ): List<String> {
        val startedAt = SystemClock.elapsedRealtime()
        val replies = adapter.handleCommand(message)
        val dispatchMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
        recordRequestTiming(message, dispatchMs, replies)
        recordLatencyMeasurement(message, dispatchMs, startedAt)
        return replies
      }

      override suspend fun onBroadcastReady(clientInfo: ProtocolClientInfo): List<String> =
        adapter.snapshotMessages(
          app.appContainer.stateRepository.state.value,
          includePosition = true,
          includeCover = true
        ).also {
          val state = app.appContainer.stateRepository.state.value
          lastStatusBroadcastPayload = adapter.snapshotMessages(
            state,
            includePosition = false,
            includeCover = false
          )
          lastBroadcastPositionMs = state.playback.track.positionMs
          lastCoverSignalTrackId = state.playback.track.realId
          lastCoverSignalRevision = state.coverSignalRevision
        }

      override suspend fun onSessionChanged(snapshot: LogicalClientSnapshot?) {
        app.appContainer.stateRepository.updateSession(snapshot)
        if (snapshot?.broadcastInitialized != true) {
          stopPositionTicker()
          lastStatusBroadcastPayload = null
          lastBroadcastPositionMs = null
          lastCoverSignalTrackId = null
          lastCoverSignalRevision = null
        }
      }

      override suspend fun onProbe(remoteAddress: String) {
        app.appContainer.stateRepository.recordProbe(remoteAddress)
      }

      override suspend fun onProtocolEvent(message: String) {
        app.appContainer.stateRepository.recordProtocolEvent(message)
      }

      override suspend fun onConnectionRejected(snapshot: ConnectionDebugSnapshot, reason: String) {
        app.appContainer.stateRepository.recordRejectedConnection(snapshot.toEventSnapshot(), reason)
      }

      override suspend fun onConnectionClosed(snapshot: ConnectionDebugSnapshot, reason: String) {
        app.appContainer.stateRepository.recordDisconnect(snapshot.toEventSnapshot(), reason)
      }
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
    positionSyncJob?.cancel()
    runBlocking { server.stop() }
    powerampGateway.stop()
    app.appContainer.stateRepository.setServiceRunning(false)
    app.appContainer.stateRepository.setListenerState(false, activeSettings.port)
    app.appContainer.stateRepository.updateSession(null)
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
        lastObservedState?.let { previous ->
          pendingLatencyMeasurement?.takeIf { measurement ->
            didObserveCommandEffect(previous, state, measurement.command)
          }?.let { measurement ->
            latencyTimeoutJob?.cancel()
            app.appContainer.stateRepository.recordLatencySample(
              command = measurement.command,
              dispatchMs = measurement.dispatchMs,
              observedMs = (SystemClock.elapsedRealtime() - measurement.startedAt).coerceAtLeast(0L),
              effectStatus = "confirmed:${expectedEffectType(measurement.command)}"
            )
            pendingLatencyMeasurement = null
          }
        }
        lastObservedState = state

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(state))
        syncPositionTicker(state)

        if (!state.broadcastInitialized) {
          return@collectLatest
        }

        val statusPayload = adapter.snapshotMessages(
          state,
          includePosition = false,
          includeCover = false
        )
        val shouldSendCoverSignal =
          (
            state.playback.track.realId != lastCoverSignalTrackId &&
              (state.playback.track.realId > 0L || lastCoverSignalTrackId != null)
            ) ||
            state.coverSignalRevision != lastCoverSignalRevision

        val messages = buildList {
          if (statusPayload != lastStatusBroadcastPayload) {
            addAll(statusPayload)
          }
          if (lastBroadcastPositionMs != state.playback.track.positionMs) {
            add(adapter.positionMessage(state))
          }
          if (shouldSendCoverSignal) {
            add(adapter.coverStatusMessage())
          }
        }

        if (messages.isNotEmpty()) {
          lastStatusBroadcastPayload = statusPayload
          lastBroadcastPositionMs = state.playback.track.positionMs
          if (shouldSendCoverSignal) {
            lastCoverSignalTrackId = state.playback.track.realId
            lastCoverSignalRevision = state.coverSignalRevision
          }
          launch(Dispatchers.IO) {
            server.sendBroadcast(messages)
          }
        }
      }
    }
  }

  private fun syncPositionTicker(state: BridgeUiState) {
    val shouldRun = state.broadcastInitialized && state.playback.state == "playing"
    if (!shouldRun) {
      stopPositionTicker()
      return
    }

    if (positionSyncJob?.isActive == true) {
      return
    }

    app.appContainer.stateRepository.setPositionSyncActive(true)
    positionSyncJob = scope.launch(Dispatchers.IO) {
      var tickCount = 0
      while (true) {
        delay(POSITION_TICK_MS)
        val current = app.appContainer.stateRepository.state.value
        if (!current.broadcastInitialized || current.playback.state != "playing") {
          break
        }

        app.appContainer.stateRepository.tickPlaybackPosition(POSITION_TICK_MS)
        val updated = app.appContainer.stateRepository.state.value
        server.sendBroadcast(listOf(adapter.positionMessage(updated)))
        lastBroadcastPositionMs = updated.playback.track.positionMs

        tickCount += 1
        if (tickCount % POSITION_RESYNC_INTERVAL == 0) {
          powerampGateway.requestPositionSync()
        }
      }
      app.appContainer.stateRepository.setPositionSyncActive(false)
    }
  }

  private fun stopPositionTicker() {
    positionSyncJob?.cancel()
    positionSyncJob = null
    app.appContainer.stateRepository.setPositionSyncActive(false)
  }

  private fun recordLatencyMeasurement(
    message: IncomingMessage,
    dispatchMs: Long,
    startedAt: Long
  ) {
    if (!shouldMeasureLatency(message)) {
      return
    }

    if (expectsObservedUpdate(message)) {
      pendingLatencyMeasurement?.let { previous ->
        latencyTimeoutJob?.cancel()
        app.appContainer.stateRepository.recordLatencySample(
          command = previous.command,
          dispatchMs = previous.dispatchMs,
          observedMs = null,
          effectStatus = "superseded:${expectedEffectType(previous.command)}"
        )
      }
      pendingLatencyMeasurement = PendingLatencyMeasurement(
        command = message.context,
        dispatchMs = dispatchMs,
        startedAt = startedAt
      )
      latencyTimeoutJob?.cancel()
      latencyTimeoutJob = scope.launch {
        delay(LATENCY_CONFIRMATION_TIMEOUT_MS)
        val pending = pendingLatencyMeasurement ?: return@launch
        if (pending.command == message.context && pending.startedAt == startedAt) {
          app.appContainer.stateRepository.recordLatencySample(
            command = pending.command,
            dispatchMs = pending.dispatchMs,
            observedMs = null,
            effectStatus = "unconfirmed:${expectedEffectType(pending.command)}"
          )
          pendingLatencyMeasurement = null
        }
      }
      return
    }

    app.appContainer.stateRepository.recordLatencySample(
      command = message.context,
      dispatchMs = dispatchMs,
      observedMs = null,
      effectStatus = "dispatch_only"
    )
  }

  private suspend fun recordRequestTiming(
    message: IncomingMessage,
    dispatchMs: Long,
    replies: List<String>
  ) {
    val replyBytes = replies.sumOf { it.length }
    val logLine = "request_timing:${message.context}:dispatch=${dispatchMs}ms:replies=${replies.size}:bytes=$replyBytes"
    when {
      dispatchMs >= VERY_SLOW_REQUEST_MS -> {
        Timber.w("Very slow request: %s", logLine)
        app.appContainer.stateRepository.recordProtocolEvent("very_slow_$logLine")
      }
      dispatchMs >= SLOW_REQUEST_MS -> {
        Timber.d("Slow request: %s", logLine)
        app.appContainer.stateRepository.recordProtocolEvent("slow_$logLine")
      }
    }
  }

  private fun shouldMeasureLatency(message: IncomingMessage): Boolean = when (message.context) {
    ProtocolConstants.PlayerPlayPause,
    ProtocolConstants.PlayerPlay,
    ProtocolConstants.PlayerPause,
    ProtocolConstants.PlayerStop,
    ProtocolConstants.PlayerNext,
    ProtocolConstants.PlayerPrevious,
    ProtocolConstants.PlayerShuffle,
    ProtocolConstants.PlayerRepeat -> true
    ProtocolConstants.PlayerVolume -> message.data != null
    ProtocolConstants.NowPlayingPosition -> message.data != null
    else -> false
  }

  private fun expectsObservedUpdate(message: IncomingMessage): Boolean = shouldMeasureLatency(message)
    && when (message.context) {
      ProtocolConstants.PlayerPlayPause,
      ProtocolConstants.PlayerPlay,
      ProtocolConstants.PlayerPause,
      ProtocolConstants.PlayerStop,
      ProtocolConstants.PlayerNext,
      ProtocolConstants.PlayerPrevious,
      ProtocolConstants.NowPlayingPosition -> true
      else -> false
    }

  private fun didObserveCommandEffect(
    previous: BridgeUiState,
    current: BridgeUiState,
    command: String
  ): Boolean = when (command) {
    ProtocolConstants.PlayerPlayPause,
    ProtocolConstants.PlayerPlay,
    ProtocolConstants.PlayerPause,
    ProtocolConstants.PlayerStop ->
      previous.playback.state != current.playback.state
        || previous.playback.track.positionMs != current.playback.track.positionMs
    ProtocolConstants.PlayerNext,
    ProtocolConstants.PlayerPrevious ->
      previous.playback.track.realId != current.playback.track.realId ||
        previous.playback.track.path != current.playback.track.path ||
        previous.playback.track.title != current.playback.track.title
    ProtocolConstants.PlayerVolume ->
      previous.playback.volume != current.playback.volume
    ProtocolConstants.PlayerShuffle ->
      previous.playback.shuffle != current.playback.shuffle
    ProtocolConstants.PlayerRepeat ->
      previous.playback.repeat != current.playback.repeat
    ProtocolConstants.NowPlayingPosition ->
      previous.playback.track.positionMs != current.playback.track.positionMs
    else -> false
  }

  private fun expectedEffectType(command: String): String = when (command) {
    ProtocolConstants.PlayerPlayPause,
    ProtocolConstants.PlayerPlay,
    ProtocolConstants.PlayerPause,
    ProtocolConstants.PlayerStop -> "playback_state"
    ProtocolConstants.PlayerNext,
    ProtocolConstants.PlayerPrevious -> "track_change"
    ProtocolConstants.PlayerVolume -> "volume"
    ProtocolConstants.PlayerShuffle -> "shuffle"
    ProtocolConstants.PlayerRepeat -> "repeat"
    ProtocolConstants.NowPlayingPosition -> "position"
    else -> "none"
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

    val sessionSummary = buildList {
      add("Port ${state.listenPort}")
      state.activeClient?.let { add("Client $it") }
      state.clientId?.let { add("ID $it") }
      add(
        when {
          state.broadcastInitialized -> "Broadcast ready"
          state.broadcastSocketConnected -> "Broadcast waiting init"
          else -> "No broadcast socket"
        }
      )
      if (state.requestSocketConnected) {
        add("Request ${state.activeRequestSocketCount}")
      }
    }.joinToString(" | ")

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_media_play)
      .setContentTitle(
        if (state.listenerActive) getString(R.string.bridge_notification_title)
        else getString(R.string.bridge_notification_stopped)
      )
      .setContentText(summary)
      .setSubText(sessionSummary)
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
    private const val POSITION_TICK_MS = 1000L
    private const val POSITION_RESYNC_INTERVAL = 5
    private const val LATENCY_CONFIRMATION_TIMEOUT_MS = 1500L
    private const val SLOW_REQUEST_MS = 250L
    private const val VERY_SLOW_REQUEST_MS = 1000L
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

  private data class PendingLatencyMeasurement(
    val command: String,
    val dispatchMs: Long,
    val startedAt: Long
  )
}

private fun ConnectionDebugSnapshot.toEventSnapshot(): ConnectionEventSnapshot = ConnectionEventSnapshot(
  socketId = socketId,
  remoteAddress = remoteAddress,
  clientId = clientId,
  role = role,
  handshakeState = handshakeState.name.lowercase(),
  broadcastInitialized = broadcastInitialized,
  requestSocketCount = requestSocketCount,
  disconnectCategory = disconnectCategory,
  lastIncomingContext = lastIncomingContext,
  lastOutgoingContext = lastOutgoingContext
)
