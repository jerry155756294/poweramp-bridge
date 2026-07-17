package com.jerry155756294.powerampbridge.bridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.ServiceCompat
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

class BridgeService : Service() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val codec = JsonMessageCodec()
  private lateinit var app: BridgeApplication
  private lateinit var powerampGateway: PowerampGateway
  private lateinit var discoveryResponder: MbrcDiscoveryResponder
  private lateinit var adapter: MbrcProtocolAdapter
  private lateinit var server: MbrcProtocolServer
  private var settingsJob: Job? = null
  private var stateJob: Job? = null
  private var positionSyncJob: Job? = null
  private var keepaliveJob: Job? = null
  private var activeSettings = BridgeSettings()
  @Volatile private var startedPort: Int? = null
  private val lifecycleMutex = Mutex()
  private var lifecycleGeneration = 0L
  private var listenerStartJob: Job? = null
  private var restartJob: Job? = null
  private var lastStatusBroadcastPayload: List<String>? = null
  private var lastBroadcastPositionMs: Long? = null
  private var lastCoverSignalTrackId: Long? = null
  private var lastCoverSignalRevision: Long? = null
  private var lastQueueSignalRevision: Long? = null
  private var lastLyricsSignalTrackKey: String? = null
  private var pendingLatencyMeasurement: PendingLatencyMeasurement? = null
  private var latencyTimeoutJob: Job? = null
  private var commandAckJob: Job? = null
  private var lastObservedState: BridgeUiState? = null
  private var stopJob: Job? = null
  private var manualStopRequested = false
  private var stopCompleted = false
  private var lastNotificationSnapshot: NotificationSnapshot? = null
  private val broadcastKeepaliveTracker = BroadcastKeepaliveTracker(clock = System::currentTimeMillis)
  private lateinit var notificationPresenter: BridgeNotificationPresenter
  private lateinit var commandPipeline: PlaybackCommandPipeline
  private lateinit var observationPipeline: PlaybackObservationPipeline
  private lateinit var runtimeLocks: BridgeRuntimeLocks

  override fun onCreate() {
    super.onCreate()
    app = application as BridgeApplication
    val stateRepository = app.appContainer.stateRepository
    runtimeLocks = BridgeRuntimeLocks(this)
    powerampGateway = PowerampGateway(this, app.appContainer.stateRepository)
    discoveryResponder = MbrcDiscoveryResponder(this, stateRepository::recordProtocolEvent)
    adapter = MbrcProtocolAdapter(
      codec = codec,
      stateRepository = stateRepository,
      powerampGateway = powerampGateway
    )
    commandPipeline = PlaybackCommandPipeline(adapter)
    observationPipeline = PlaybackObservationPipeline()
    notificationPresenter = BridgeNotificationPresenter(
      context = this,
      launchIntent = PendingIntent.getActivity(
        this,
        1,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
      ),
      stopIntent = PendingIntent.getService(
        this,
        2,
        Intent(this, BridgeService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
      ),
      restartIntent = PendingIntent.getService(
        this,
        3,
        restartIntent(this),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
      )
    )
    server = MbrcProtocolServer(codec, object : MbrcProtocolServer.Listener {
      override suspend fun onCommand(
        clientInfo: ProtocolClientInfo,
        message: IncomingMessage
      ): List<String> {
        if (stopJob?.isActive == true) {
          stateRepository.recordProtocolEvent("service_stopping_rejected:${message.context}")
          return listOf(codec.encode(ProtocolConstants.CommandUnavailable, message.context))
        }
        val observedAt = SystemClock.elapsedRealtime()
        val startedAt = SystemClock.elapsedRealtime()
        stateRepository.recordProtocolEvent("command_received:${message.context}")
        val result = commandPipeline.handle(
          message = message,
          nowMs = observedAt,
          currentPlaybackState = stateRepository.state.value.playback.state
        )
        result.protocolEvents.forEach(stateRepository::recordProtocolEvent)
        result.powerampEvents.forEach(stateRepository::recordPowerampEvent)
        observationPipeline.onCommandResult(result, observedAt)
        result.optimisticPlaybackState?.let { optimisticState ->
          applyOptimisticPlaybackAck(result.intent, optimisticState, observedAt)
        }
        val replies = result.replies
        val dispatchMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
        if (result.executed) {
          recordRequestTiming(message, dispatchMs, replies)
          recordLatencyMeasurement(message, dispatchMs, startedAt)
        }
        return replies
      }

      override suspend fun onBroadcastReady(clientInfo: ProtocolClientInfo): List<String> {
        if (stopJob?.isActive == true) {
          return emptyList()
        }

        val nowMs = SystemClock.elapsedRealtime()
        val senderProjection = observationPipeline.senderFacingState(stateRepository.state.value, nowMs)
        senderProjection.protocolEvents.forEach(stateRepository::recordProtocolEvent)
        val initialMessages = adapter.snapshotMessages(
          senderProjection.state,
          includePosition = true,
          includeCover = true
        )
        val state = senderProjection.state
        lastStatusBroadcastPayload = adapter.snapshotMessages(
          state,
          includePosition = false,
          includeCover = false
        )
        lastBroadcastPositionMs = state.playback.track.positionMs
        lastCoverSignalTrackId = state.playback.track.realId
        lastCoverSignalRevision = state.coverSignalRevision
        lastQueueSignalRevision = state.queueSignalRevision
        lastLyricsSignalTrackKey = lyricsTrackKey(state)
        stateRepository.recordProtocolEvent("lyrics_initial_broadcast:track=${lyricsTrackKey(state)}")
        return initialMessages + adapter.lyricsMessage()
      }

      override suspend fun onSessionChanged(snapshot: LogicalClientSnapshot?) {
        stateRepository.updateSession(snapshot)
        if (snapshot?.broadcastInitialized != true) {
          stopPositionTicker()
          stopKeepaliveLoop()
          broadcastKeepaliveTracker.clear()
          lastStatusBroadcastPayload = null
          lastBroadcastPositionMs = null
          lastCoverSignalTrackId = null
          lastCoverSignalRevision = null
          lastQueueSignalRevision = null
          lastLyricsSignalTrackKey = null
        }
      }

      override suspend fun onProbe(remoteAddress: String) {
        stateRepository.recordProbe(remoteAddress)
      }

      override suspend fun onBroadcastTraffic(contexts: List<String>) {
        if (contexts.isNotEmpty()) {
          broadcastKeepaliveTracker.recordActivity()
        }
      }

      override suspend fun onProtocolEvent(message: String) {
        stateRepository.recordProtocolEvent(message)
      }

      override suspend fun onConnectionRejected(snapshot: ConnectionDebugSnapshot, reason: String) {
        stateRepository.recordRejectedConnection(snapshot.toEventSnapshot(), reason)
      }

      override suspend fun onConnectionClosed(snapshot: ConnectionDebugSnapshot, reason: String) {
        stateRepository.recordDisconnect(snapshot.toEventSnapshot(), reason)
      }
    })

    createNotificationChannel()
    stateRepository.markServiceStarted()
    notificationPresenter.foregroundPersistent = activeSettings.foregroundPersistent
    val initialState = stateRepository.state.value
    lastNotificationSnapshot = notificationPresenter.snapshot(initialState)
    ServiceCompat.startForeground(
      this,
      NOTIFICATION_ID,
      notificationPresenter.build(initialState),
      ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    )
    runtimeLocks.acquire(stateRepository::recordProtocolEvent)
    powerampGateway.start()
    observeSettings()
    observeState()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_STOP -> beginServiceStop(manualStop = true)
      ACTION_RESTART -> beginServiceRestart()
    }
    return START_STICKY
  }

  override fun onTimeout(startId: Int, fgsType: Int) {
    if (::app.isInitialized) {
      app.appContainer.stateRepository.recordProtocolEvent("foreground_service_timeout:type=$fgsType")
    }
    beginServiceStop(manualStop = false)
  }

  override fun onDestroy() {
    if (!stopCompleted) {
      lifecycleGeneration += 1L
      listenerStartJob?.cancel()
      restartJob?.cancel()
      runBlocking {
        lifecycleMutex.withLock { performStopSequence() }
      }
    }
    if (::discoveryResponder.isInitialized) {
      discoveryResponder.close()
    }
    scope.cancel()
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun observeSettings() {
    settingsJob?.cancel()
    settingsJob = scope.launch {
      app.appContainer.settingsRepository.settings.collectLatest { settings ->
        activeSettings = settings
        notificationPresenter.foregroundPersistent = settings.foregroundPersistent
        lastObservedState?.let { state ->
          val notificationSnapshot = notificationPresenter.snapshot(state)
          if (notificationSnapshot != lastNotificationSnapshot) {
            lastNotificationSnapshot = notificationSnapshot
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, notificationPresenter.build(state))
          }
        }
        if (
          startedPort != settings.port &&
          listenerStartJob?.isActive != true &&
          stopJob?.isActive != true &&
          !stopCompleted
        ) {
          val generation = lifecycleGeneration
          listenerStartJob = launch(Dispatchers.IO) {
            startListener(settings, generation)
          }
        }
      }
    }
  }

  private suspend fun startListener(settings: BridgeSettings, generation: Long) {
    var serverStarted = false
    try {
      server.start(settings.port)
      serverStarted = true
      if (!isListenerStartCurrent(generation)) {
        server.stop()
        return
      }

      discoveryResponder.start(settings.port)
      if (!isListenerStartCurrent(generation)) {
        discoveryResponder.stop()
        server.stop()
        return
      }

      startedPort = settings.port
      app.appContainer.stateRepository.setListenerState(true, settings.port)
      app.appContainer.stateRepository.setError(null)
      app.appContainer.stateRepository.recordProtocolEvent(
        "listener_start_committed:generation=$generation port=${settings.port}"
      )
    } catch (error: Throwable) {
      if (error is CancellationException) {
        throw error
      }
      if (serverStarted) {
        discoveryResponder.stop()
        runCatching { server.stop() }
          .onFailure { stopError -> Timber.w(stopError, "Failed to roll back listener start") }
      }
      if (isListenerStartCurrent(generation)) {
        app.appContainer.stateRepository.setListenerState(false, settings.port)
        app.appContainer.stateRepository.setError(error.message ?: "listener_start_failed")
      }
    }
  }

  private fun isListenerStartCurrent(generation: Long): Boolean =
    generation == lifecycleGeneration &&
      !stopCompleted &&
      stopJob?.isActive != true

  private fun beginServiceRestart() {
    if (stopCompleted || stopJob?.isActive == true) {
      app.appContainer.stateRepository.recordProtocolEvent("listener_restart_ignored_stopping")
      return
    }

    manualStopRequested = false
    app.appContainer.stateRepository.markServiceRestarting()
    lifecycleGeneration += 1L
    listenerStartJob?.cancel()
    restartJob?.cancel()
    restartJob = scope.launch(Dispatchers.IO) {
      lifecycleMutex.withLock {
        if (stopCompleted || stopJob?.isActive == true) {
          return@withLock
        }

        val generation = lifecycleGeneration
        val restartStartedAt = SystemClock.elapsedRealtime()
        app.appContainer.stateRepository.recordProtocolEvent(
          "listener_restart_begin:generation=$generation"
        )
        settingsJob?.cancelAndJoin()
        listenerStartJob?.cancelAndJoin()
        listenerStartJob = null
        discoveryResponder.stop()
        try {
          server.stop()
        } catch (error: CancellationException) {
          throw error
        } catch (error: Throwable) {
          Timber.w(error, "Failed to stop protocol server before restart")
        }
        startedPort = null
        app.appContainer.stateRepository.setListenerState(false, activeSettings.port)
        delay(
          (RESTART_STATUS_MINIMUM_MS - (SystemClock.elapsedRealtime() - restartStartedAt))
            .coerceAtLeast(0L)
        )
        observeSettings()
        app.appContainer.stateRepository.recordProtocolEvent(
          "listener_restart_observing:generation=$generation"
        )
      }
    }
  }

  private fun observeState() {
    stateJob?.cancel()
    stateJob = scope.launch {
      app.appContainer.stateRepository.state.collectLatest { state ->
        val nowMs = SystemClock.elapsedRealtime()
        val observationResult = observationPipeline.onPlaybackObserved(lastObservedState, state, nowMs)
        observationResult.protocolEvents.forEach(app.appContainer.stateRepository::recordProtocolEvent)
        observationResult.powerampEvents.forEach(app.appContainer.stateRepository::recordPowerampEvent)
        if (observationResult.action == ObservationAction.REPLAY_PLAY) {
          observationPipeline.onRecoveryPlayIssued(nowMs)
          launch(Dispatchers.IO) {
            powerampGateway.play()
          }
        }

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
        val notificationSnapshot = notificationPresenter.snapshot(state)
        if (notificationSnapshot != lastNotificationSnapshot) {
          lastNotificationSnapshot = notificationSnapshot
          val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
          manager.notify(NOTIFICATION_ID, notificationPresenter.build(state))
        }
        syncPositionTicker(state)
        syncKeepaliveLoop(state)

        if (!state.broadcastInitialized) {
          return@collectLatest
        }

        val senderProjection = observationPipeline.senderFacingState(state, nowMs)
        senderProjection.protocolEvents.forEach(app.appContainer.stateRepository::recordProtocolEvent)
        val senderState = senderProjection.state
        val statusPayload = adapter.snapshotMessages(
          senderState,
          includePosition = false,
          includeCover = false
        )
        val shouldSendCoverSignal =
          (
            state.playback.track.realId != lastCoverSignalTrackId &&
              (state.playback.track.realId > 0L || lastCoverSignalTrackId != null)
            ) ||
            state.coverSignalRevision != lastCoverSignalRevision
        val lyricsTrackKey = lyricsTrackKey(senderState)
        val shouldSendLyricsSignal = lyricsTrackKey != lastLyricsSignalTrackKey
        val shouldSendQueueSignal = state.queueSignalRevision != lastQueueSignalRevision

        val statusChanged = statusPayload != lastStatusBroadcastPayload
        val messages = buildList {
          if (statusChanged) {
            addAll(statusPayload)
          }
          if (
            lastBroadcastPositionMs != senderState.playback.track.positionMs &&
              positionSyncJob?.isActive != true
          ) {
            add(adapter.positionMessage(senderState))
          }
          if (shouldSendCoverSignal) {
            add(adapter.coverStatusMessage())
          }
          if (shouldSendQueueSignal) {
            add(adapter.nowPlayingListChangedMessage())
          }
        }

        if (messages.isNotEmpty() || shouldSendLyricsSignal) {
          val senderOverride = state.senderPlaybackOverride
          lastStatusBroadcastPayload = statusPayload
          lastBroadcastPositionMs = senderState.playback.track.positionMs
          if (shouldSendCoverSignal) {
            lastCoverSignalTrackId = senderState.playback.track.realId
            lastCoverSignalRevision = senderState.coverSignalRevision
          }
          if (shouldSendQueueSignal) {
            lastQueueSignalRevision = state.queueSignalRevision
          }
          if (shouldSendLyricsSignal) {
            lastLyricsSignalTrackKey = lyricsTrackKey
          }
          launch(Dispatchers.IO) {
            if (messages.isNotEmpty()) {
              server.sendBroadcast(messages)
            }
            if (shouldSendLyricsSignal) {
              app.appContainer.stateRepository.recordProtocolEvent(
                "lyrics_track_broadcast:track=$lyricsTrackKey"
              )
              server.sendBroadcast(listOf(adapter.lyricsMessage()))
            }
            if (statusChanged && senderOverride != null) {
              app.appContainer.stateRepository.recordProtocolEvent(
                "sender_status_sent:${senderState.playback.state} reason=${senderOverride.reason}"
              )
            }
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
      // Do not leave a newly connected sender waiting for the periodic position resync.
      powerampGateway.requestPositionSync()
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

  private fun syncKeepaliveLoop(state: BridgeUiState) {
    if (!state.broadcastInitialized) {
      stopKeepaliveLoop()
      return
    }

    if (keepaliveJob?.isActive == true) {
      return
    }

    keepaliveJob = scope.launch(Dispatchers.IO) {
      while (true) {
        delay(BROADCAST_KEEPALIVE_CHECK_MS)
        val current = app.appContainer.stateRepository.state.value
        if (!current.broadcastInitialized) {
          break
        }

        val nowMs = System.currentTimeMillis()
        if (!broadcastKeepaliveTracker.shouldSendKeepalive(nowMs)) {
          continue
        }

        val sent = server.sendKeepalivePing()
        if (sent) {
          broadcastKeepaliveTracker.recordActivity(nowMs)
        }
      }
    }
  }

  private fun stopKeepaliveLoop() {
    keepaliveJob?.cancel()
    keepaliveJob = null
  }

  private fun applyOptimisticPlaybackAck(
    intent: PlaybackCommandIntent,
    state: String,
    nowMs: Long
  ) {
    val reason = when (intent) {
      PlaybackCommandIntent.PLAY -> "command_ack_play"
      PlaybackCommandIntent.PLAY_PAUSE -> "command_ack_playpause"
      PlaybackCommandIntent.PAUSE -> "command_ack_pause"
      PlaybackCommandIntent.STOP -> "command_ack_stop"
      else -> return
    }
    val repository = app.appContainer.stateRepository
    val expiresAt = nowMs + COMMAND_ACK_TTL_MS
    repository.recordProtocolEvent(
      "sender_state_override:$state reason=$reason ttl=$COMMAND_ACK_TTL_MS"
    )
    repository.overrideSenderPlaybackState(state, reason, expiresAt)

    commandAckJob?.cancel()
    commandAckJob = scope.launch {
      delay(COMMAND_ACK_TTL_MS)
      repository.clearSenderPlaybackOverride(reason)
      repository.recordProtocolEvent("sender_state_override_expired:$state reason=$reason")
    }
  }

  private fun beginServiceStop(manualStop: Boolean) {
    if (manualStop) {
      manualStopRequested = true
      app.appContainer.stateRepository.markManualStopRequested()
    } else {
      app.appContainer.stateRepository.markServiceStopping(manualStopRequested)
    }

    if (stopJob?.isActive == true || stopCompleted) {
      return
    }

    // Invalidate any listener start that may still be waiting on the provider. The stop job is
    // serialized with restart below, so a cancelled restart cannot resurrect the listener.
    lifecycleGeneration += 1L
    listenerStartJob?.cancel()
    restartJob?.cancel()
    stopJob = scope.launch {
      lifecycleMutex.withLock { performStopSequence() }
      stopSelf()
    }
  }

  private suspend fun performStopSequence() {
    if (stopCompleted) {
      return
    }

    stopCompleted = true
    lifecycleGeneration += 1L
    if (::app.isInitialized) {
      app.appContainer.stateRepository.markServiceStopping(manualStopRequested)
    }
    settingsJob?.cancelAndJoin()
    stateJob?.cancelAndJoin()
    listenerStartJob?.cancelAndJoin()
    listenerStartJob = null
    latencyTimeoutJob?.cancel()
    commandAckJob?.cancel()
    if (::app.isInitialized) {
      app.appContainer.stateRepository.clearSenderPlaybackOverride()
    }
    pendingLatencyMeasurement = null
    stopPositionTicker()
    stopKeepaliveLoop()
    broadcastKeepaliveTracker.clear()
    lastStatusBroadcastPayload = null
    lastBroadcastPositionMs = null
    lastCoverSignalTrackId = null
    lastCoverSignalRevision = null
    lastLyricsSignalTrackKey = null
    lastNotificationSnapshot = null

    if (::discoveryResponder.isInitialized) {
      discoveryResponder.stop()
    }

    if (::server.isInitialized) {
      runCatching { server.stop() }
        .onFailure { Timber.w(it, "Failed to stop protocol server cleanly") }
    }

    if (::powerampGateway.isInitialized) {
      runCatching { powerampGateway.stop() }
        .onFailure { Timber.w(it, "Failed to stop Poweramp gateway cleanly") }
    }

    if (::runtimeLocks.isInitialized) {
      runtimeLocks.release(app.appContainer.stateRepository::recordProtocolEvent)
    }

    if (::app.isInitialized) {
      app.appContainer.stateRepository.updateSession(null)
      app.appContainer.stateRepository.setListenerState(false, activeSettings.port)
      app.appContainer.stateRepository.markServiceStopped(manualStopRequested)
    }

    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    // Be explicit as well: some Android builds keep the old notification row until the manager
    // is notified after the foreground record is removed.
    (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
      .cancel(NOTIFICATION_ID)
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

  private fun lyricsTrackKey(state: BridgeUiState): String =
    "${state.playback.track.realId}:${state.playback.track.path}"

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
    internal const val CHANNEL_ID = "poweramp_bridge"
    private const val NOTIFICATION_ID = 1001
    private const val POSITION_TICK_MS = 250L
    private const val POSITION_RESYNC_INTERVAL = 20
    private const val BROADCAST_KEEPALIVE_CHECK_MS = 5_000L
    private const val COMMAND_ACK_TTL_MS = 1_500L
    private const val LATENCY_CONFIRMATION_TIMEOUT_MS = 1500L
    private const val SLOW_REQUEST_MS = 250L
    private const val VERY_SLOW_REQUEST_MS = 1000L
    private const val RESTART_STATUS_MINIMUM_MS = 350L
    private const val ACTION_STOP = "com.jerry155756294.powerampbridge.action.STOP"
    internal const val ACTION_RESTART = "com.jerry155756294.powerampbridge.action.RESTART"

    fun start(context: Context) {
      ContextCompat.startForegroundService(
        context,
        startIntent(context)
      )
    }

    fun startOrRestart(context: Context, serviceRunning: Boolean) {
      if (serviceRunning) {
        restart(context)
      } else {
        start(context)
      }
    }

    fun restart(context: Context) {
      ContextCompat.startForegroundService(context, restartIntent(context))
    }

    fun stop(context: Context) {
      context.startService(
        Intent(context, BridgeService::class.java).setAction(ACTION_STOP)
      )
    }

    private fun startIntent(context: Context): Intent = Intent(context, BridgeService::class.java)

    private fun restartIntent(context: Context): Intent =
      Intent(context, BridgeService::class.java).setAction(ACTION_RESTART)
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
