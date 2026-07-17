package com.jerry155756294.powerampbridge.bridge

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import com.jerry155756294.powerampbridge.R

internal data class NotificationTextResources(
  val bridgeTitle: String,
  val bridgeStoppedTitle: String,
  val notificationNoTrack: String,
  val stopLabel: String,
  val restartLabel: String,
  val stoppingTitle: String = "Poweramp Bridge is stopping",
  val manualStoppedTitle: String = "Poweramp Bridge was stopped manually",
  val portLabel: (Int) -> String = { port -> "Port $port" },
  val controllerLabel: (String) -> String = { client -> "Controller $client" },
  val idLabel: (String) -> String = { id -> "ID $id" },
  val broadcastReady: String = "Broadcast ready",
  val broadcastWaiting: String = "Broadcast waiting for initialization",
  val broadcastNone: String = "No broadcast socket",
  val requestsLabel: (Int) -> String = { count -> "Requests $count" }
)

internal data class NotificationSnapshot(
  val title: String,
  val summary: String,
  val subText: String,
  val ongoing: Boolean
)

internal class BridgeNotificationPresenter(
  private val textResources: NotificationTextResources,
  private val context: Context? = null,
  private val launchIntent: PendingIntent? = null,
  private val stopIntent: PendingIntent? = null,
  private val restartIntent: PendingIntent? = null
) {
  constructor(
    context: Context,
    launchIntent: PendingIntent,
    stopIntent: PendingIntent,
    restartIntent: PendingIntent
  ) : this(
    textResources = NotificationTextResources(
      bridgeTitle = context.getString(R.string.bridge_notification_title),
      bridgeStoppedTitle = context.getString(R.string.bridge_notification_stopped),
      notificationNoTrack = context.getString(R.string.notification_no_track),
      stopLabel = context.getString(R.string.notification_stop),
      restartLabel = context.getString(R.string.notification_restart),
      stoppingTitle = context.getString(R.string.notification_stopping_title),
      manualStoppedTitle = context.getString(R.string.notification_manual_stopped_title),
      portLabel = { port -> context.getString(R.string.notification_port, port) },
      controllerLabel = { client -> context.getString(R.string.notification_controller, client) },
      idLabel = { id -> context.getString(R.string.notification_id, id) },
      broadcastReady = context.getString(R.string.notification_broadcast_ready),
      broadcastWaiting = context.getString(R.string.notification_broadcast_waiting),
      broadcastNone = context.getString(R.string.notification_broadcast_none),
      requestsLabel = { count -> context.getString(R.string.notification_requests, count) }
    ),
    context = context,
    launchIntent = launchIntent,
    stopIntent = stopIntent,
    restartIntent = restartIntent
  )

  var foregroundPersistent: Boolean = true

  fun snapshot(state: BridgeUiState): NotificationSnapshot =
    NotificationSnapshot(
      title = when {
        state.serviceStopping -> textResources.stoppingTitle
        state.manualStopActive && !state.serviceRunning -> textResources.manualStoppedTitle
        state.listenerActive -> textResources.bridgeTitle
        else -> textResources.bridgeStoppedTitle
      },
      summary = if (state.playback.track.title.isNotBlank()) {
        "${state.playback.track.title} - ${state.playback.track.artist}"
      } else {
        textResources.notificationNoTrack
      },
      subText = buildList {
        add(textResources.portLabel(state.listenPort))
        state.activeClient?.let { add(textResources.controllerLabel(it)) }
        state.clientId?.let { add(textResources.idLabel(it)) }
        add(
          when {
            state.broadcastInitialized -> textResources.broadcastReady
            state.broadcastSocketConnected -> textResources.broadcastWaiting
            else -> textResources.broadcastNone
          }
        )
        if (state.requestSocketConnected) {
          add(textResources.requestsLabel(state.activeRequestSocketCount))
        }
      }.joinToString(" | "),
      ongoing = foregroundPersistent
    )

  fun build(state: BridgeUiState): Notification {
    val snapshot = snapshot(state)
    return NotificationCompat.Builder(requireNotNull(context), BridgeService.CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_media_play)
      .setContentTitle(snapshot.title)
      .setContentText(snapshot.summary)
      .setSubText(snapshot.subText)
      .setContentIntent(requireNotNull(launchIntent))
      .setOngoing(snapshot.ongoing)
      .addAction(0, textResources.stopLabel, requireNotNull(stopIntent))
      .addAction(0, textResources.restartLabel, requireNotNull(restartIntent))
      .build()
  }
}
