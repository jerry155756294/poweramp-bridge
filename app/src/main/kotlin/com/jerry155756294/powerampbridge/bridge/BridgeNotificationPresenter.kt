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
  val restartLabel: String
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
      restartLabel = context.getString(R.string.notification_restart)
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
        state.serviceStopping -> "Poweramp Bridge stopping"
        state.manualStopActive && !state.serviceRunning -> "Poweramp Bridge stopped manually"
        state.listenerActive -> textResources.bridgeTitle
        else -> textResources.bridgeStoppedTitle
      },
      summary = if (state.playback.track.title.isNotBlank()) {
        "${state.playback.track.title} - ${state.playback.track.artist}"
      } else {
        textResources.notificationNoTrack
      },
      subText = state.serviceStopSummary ?: buildList {
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
