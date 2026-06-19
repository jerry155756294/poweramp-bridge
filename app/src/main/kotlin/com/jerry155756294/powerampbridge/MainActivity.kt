package com.jerry155756294.powerampbridge

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jerry155756294.powerampbridge.bridge.BridgeService
import com.jerry155756294.powerampbridge.bridge.BridgeUiState
import com.jerry155756294.powerampbridge.bridge.LogEntry
import com.jerry155756294.powerampbridge.bridge.serviceStatusLabel
import com.jerry155756294.powerampbridge.bridge.shouldAutoStart
import com.jerry155756294.powerampbridge.data.BridgeSettings
import com.jerry155756294.powerampbridge.data.BridgeSettingsRepository
import com.jerry155756294.powerampbridge.ui.BridgeTheme
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
  private val notificationPermission =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val app = application as BridgeApplication
    setContent {
      BridgeTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
          BridgeApp(
            state = app.appContainer.stateRepository.state,
            settingsRepository = app.appContainer.settingsRepository,
            onStart = { BridgeService.start(this) },
            onStop = { BridgeService.stop(this) }
          )
        }
      }
    }
  }
}

@Composable
private fun BridgeApp(
  state: StateFlow<BridgeUiState>,
  settingsRepository: BridgeSettingsRepository,
  onStart: () -> Unit,
  onStop: () -> Unit
) {
  val uiState by state.collectAsStateWithLifecycle()
  val settings by settingsRepository.settings.collectAsStateWithLifecycle(initialValue = BridgeSettings())
  var selectedDestination by remember { mutableStateOf(BridgeDestination.Settings) }

  LaunchedEffect(settings.autoStart, uiState.serviceRunning, uiState.serviceStopping, uiState.manualStopActive) {
    if (uiState.shouldAutoStart(settings.autoStart)) {
      onStart()
    }
  }

  Scaffold(
    containerColor = MaterialTheme.colorScheme.background,
    bottomBar = {
      BottomNavigationBar(
        selectedDestination = selectedDestination,
        onSelectDestination = { selectedDestination = it }
      )
    }
  ) { padding ->
    when (selectedDestination) {
      BridgeDestination.Settings -> SettingsTab(
        uiState = uiState,
        settings = settings,
        onStart = onStart,
        onStop = onStop,
        repository = settingsRepository,
        modifier = Modifier.padding(padding)
      )
      BridgeDestination.Status -> StatusTab(
        uiState = uiState,
        advancedMode = settings.advancedDiagnosticsEnabled,
        modifier = Modifier.padding(padding)
      )
      BridgeDestination.Debug -> DebugTab(
        uiState = uiState,
        advancedMode = settings.advancedDiagnosticsEnabled,
        modifier = Modifier.padding(padding)
      )
    }
  }
}

private enum class BridgeDestination(
  val title: String,
  val subtitle: String,
  val icon: ImageVector
) {
  Settings(
    title = "Controls",
    subtitle = "Tune startup, networking, and service behavior.",
    icon = Icons.Rounded.Settings
  ),
  Status(
    title = "Live Status",
    subtitle = "Track the bridge, sender session, and Poweramp state.",
    icon = Icons.Rounded.Tune
  ),
  Debug(
    title = "Diagnostics",
    subtitle = "Inspect recent protocol, command, and Poweramp events.",
    icon = Icons.Rounded.BugReport
  )
}

@Composable
private fun BottomNavigationBar(
  selectedDestination: BridgeDestination,
  onSelectDestination: (BridgeDestination) -> Unit
) {
  Surface(
    color = MaterialTheme.colorScheme.background,
    tonalElevation = 0.dp,
    modifier = Modifier.navigationBarsPadding()
  ) {
    Card(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 12.dp),
      shape = MaterialTheme.shapes.extraLarge,
      colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surface
      ),
      elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
    ) {
      NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp
      ) {
        BridgeDestination.entries.forEach { destination ->
          NavigationBarItem(
            selected = selectedDestination == destination,
            onClick = { onSelectDestination(destination) },
            icon = {
              androidx.compose.material3.Icon(
                imageVector = destination.icon,
                contentDescription = destination.title
              )
            },
            label = { Text(destination.title) }
          )
        }
      }
    }
  }
}

@Composable
private fun SettingsTab(
  uiState: BridgeUiState,
  settings: BridgeSettings,
  onStart: () -> Unit,
  onStop: () -> Unit,
  repository: BridgeSettingsRepository,
  modifier: Modifier = Modifier
) {
  val scope = rememberCoroutineScope()
  var portText by remember(settings.port) { mutableStateOf(settings.port.toString()) }

  Column(
    modifier = modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp, vertical = 20.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    ScreenHero(
      destination = BridgeDestination.Settings,
      accent = "TCP ${settings.port}",
      summary = if (uiState.listenerActive) "Listener ready for LAN or Tailscale control." else "Bridge is idle until you start the service."
    )

    SectionCard("Network") {
      OutlinedTextField(
        value = portText,
        onValueChange = {
          portText = it.filter(Char::isDigit)
          portText.toIntOrNull()?.let { port ->
            scope.launch { repository.updatePort(port) }
          }
        },
        label = { Text("TCP Port") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = OutlinedTextFieldDefaults.colors(
          focusedContainerColor = MaterialTheme.colorScheme.surface,
          unfocusedContainerColor = MaterialTheme.colorScheme.surface,
          focusedBorderColor = MaterialTheme.colorScheme.primary,
          unfocusedBorderColor = MaterialTheme.colorScheme.outline
        )
      )
      Text(
        text = "Use this LAN/Tailscale address and port from your MBRC sender.",
        style = MaterialTheme.typography.bodySmall
      )
    }

    SectionCard("Local Addresses") {
      if (uiState.localAddresses.isEmpty()) {
        Text("No active address yet.")
      } else {
        uiState.localAddresses.forEach { address ->
          StatusLine("IP", address)
        }
      }
    }

    SectionCard("Service") {
      SettingSwitch("Auto start bridge with app", settings.autoStart) {
        scope.launch { repository.updateAutoStart(it) }
      }
      SettingSwitch("Start bridge on boot", settings.startOnBoot) {
        scope.launch { repository.updateStartOnBoot(it) }
      }
      SettingSwitch("Persistent foreground notification", settings.foregroundPersistent) {
        scope.launch { repository.updateForegroundPersistent(it) }
      }
      SettingSwitch("Minimal notification debug mode", settings.minimalForegroundNotification) {
        scope.launch { repository.updateMinimalForegroundNotification(it) }
      }
      SettingSwitch("Professional diagnostics mode", settings.advancedDiagnosticsEnabled) {
        scope.launch { repository.updateAdvancedDiagnostics(it) }
      }
      Text(
        text = if (settings.advancedDiagnosticsEnabled) {
          "Shows raw protocol categories, handshake state, and low-level debug events."
        } else {
          "Shows simpler health summaries without low-level protocol terminology."
        },
        style = MaterialTheme.typography.bodySmall
      )
      if (settings.minimalForegroundNotification) {
        Text(
          text = "Foreground notification debug mode keeps the bridge notification mostly static to test System UI pause interactions.",
          style = MaterialTheme.typography.bodySmall
        )
      }
    }

    ServiceActionRow(
      serviceRunning = uiState.serviceRunning,
      onStart = onStart,
      onStop = onStop
    )

    uiState.serviceStopSummary?.let { summary ->
      SectionCard("Stop State") {
        StatusLine("Status", summary)
        StatusLine("Behavior", uiState.serviceStopDetail ?: "No extra detail.")
      }
    }
  }
}

@Composable
private fun StatusTab(
  uiState: BridgeUiState,
  advancedMode: Boolean,
  modifier: Modifier = Modifier
) {
  Column(
    modifier = modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp, vertical = 20.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    ScreenHero(
      destination = BridgeDestination.Status,
      accent = uiState.serviceStatusLabel(),
      summary = uiState.activeClient?.let { "Connected to $it" } ?: "No sender is currently attached."
    )

    StatusHighlights(uiState)

    SectionCard("Bridge") {
      StatusLine("Service", uiState.serviceStatusLabel())
      StatusLine("Listener", if (uiState.listenerActive) "TCP ${uiState.listenPort}" else "Offline")
      StatusLine("Primary IP", uiState.localAddresses.firstOrNull() ?: "Unavailable")
      StatusLine("Client IP", uiState.activeClient ?: "None")
      StatusLine("Client ID", uiState.clientId ?: "None")
      StatusLine("Protocol", uiState.protocolVersion?.toString() ?: "Unknown")
      StatusLine("Broadcast Socket", socketStatus(uiState.broadcastSocketConnected, uiState.broadcastInitialized))
      StatusLine("Request Sockets", uiState.activeRequestSocketCount.toString())
      StatusLine("Position Sync", if (uiState.positionSyncActive) "Active" else "Idle")
      StatusLine("Last Verifyconnection", uiState.lastProbeAt ?: "None yet")
      StatusLine("Stop Summary", uiState.serviceStopSummary ?: "None")
      StatusLine("Stop Detail", uiState.serviceStopDetail ?: "None")
      StatusLine("Last Rejection", uiState.lastRejectedReason ?: "None yet")
      StatusLine(
        "Last Disconnect",
        if (advancedMode) uiState.lastDisconnectReason ?: "None yet" else summarizeDisconnect(uiState)
      )
      if (advancedMode) {
        StatusLine("Disconnect Details", formatDisconnectSummary(uiState))
      }
    }

    SectionCard("Control Latency") {
      StatusLine("Rating", latencyRating(uiState))
      StatusLine("Last Command", uiState.latencySummary.lastCommand ?: "None yet")
      StatusLine(
        if (advancedMode) "Latency Details" else "Latency Summary",
        formatLatency(uiState, advancedMode)
      )
    }

    SectionCard("Poweramp") {
      StatusLine("Availability", if (uiState.powerampAvailable) "Connected" else "Not detected")
      StatusLine("Playback State", uiState.playback.state)
      StatusLine("Repeat / Shuffle", "${uiState.playback.repeat} / ${uiState.playback.shuffle}")
      StatusLine("Volume", "${uiState.playback.volume}%")
      StatusLine("Cover State", uiState.coverState.summary())
      StatusLine("Cover Detail", uiState.coverState.detail())
    }

    SectionCard("Now Playing") {
      StatusLine("Title", uiState.playback.track.title.ifBlank { "Unknown" })
      StatusLine("Artist", uiState.playback.track.artist.ifBlank { "Unknown" })
      StatusLine("Album", uiState.playback.track.album.ifBlank { "Unknown" })
      StatusLine("Path", uiState.playback.track.path.ifBlank { "Unknown" })
      StatusLine(
        "Position",
        "${uiState.playback.track.positionMs / 1000}s / ${uiState.playback.track.durationMs / 1000}s"
      )
    }
  }
}

@Composable
private fun DebugTab(
  uiState: BridgeUiState,
  advancedMode: Boolean,
  modifier: Modifier = Modifier
) {
  Column(
    modifier = modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp, vertical = 20.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    ScreenHero(
      destination = BridgeDestination.Debug,
      accent = if (advancedMode) "Advanced mode" else "Simple mode",
      summary = uiState.lastError ?: "No active bridge errors recorded."
    )

    SectionCard("Last Error") {
      Text(uiState.lastError ?: "No error recorded.")
    }

    SectionCard(if (advancedMode) "Protocol Events" else "Bridge Events") {
      EventList(uiState.recentProtocolEvents, advancedMode)
    }

    SectionCard("Recent Commands") {
      EventList(uiState.recentCommands, true)
    }

    SectionCard("Poweramp Events") {
      EventList(uiState.recentPowerampEvents, true)
    }
  }
}

@Composable
private fun ScreenHero(
  destination: BridgeDestination,
  accent: String,
  summary: String
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.extraLarge,
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.primaryContainer
    )
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(
          modifier = Modifier.weight(1f),
          verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          Text(
            text = "Poweramp Bridge",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
          )
          Text(
            text = destination.title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
          )
        }
        Surface(
          shape = MaterialTheme.shapes.large,
          color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.1f)
        ) {
          Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            androidx.compose.material3.Icon(
              imageVector = destination.icon,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onPrimaryContainer,
              modifier = Modifier.size(24.dp)
            )
          }
        }
      }
      Text(
        text = destination.subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f)
      )
      Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)
      ) {
        Text(
          text = "$accent | $summary",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
        )
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusHighlights(uiState: BridgeUiState) {
  FlowRow(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    HighlightChip("Listener", if (uiState.listenerActive) "Online" else "Offline")
    HighlightChip("Client", uiState.activeClient ?: "None")
    HighlightChip("Playback", uiState.playback.state)
    HighlightChip("Position Sync", if (uiState.positionSyncActive) "Active" else "Idle")
  }
}

@Composable
private fun HighlightChip(label: String, value: String) {
  Surface(
    shape = MaterialTheme.shapes.large,
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f)
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
      Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
      Text(
        text = value,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
    }
  }
}

@Composable
private fun ServiceActionRow(
  serviceRunning: Boolean,
  onStart: () -> Unit,
  onStop: () -> Unit
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    Button(
      onClick = onStart,
      modifier = Modifier.weight(1f),
      shape = MaterialTheme.shapes.large,
      colors = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.primary
      )
    ) {
      Text(if (serviceRunning) "Restart Bridge" else "Start Bridge")
    }
    TextButton(
      onClick = onStop,
      modifier = Modifier.weight(1f),
      shape = MaterialTheme.shapes.large
    ) {
      Text("Stop Bridge")
    }
  }
}

@Composable
private fun EventList(events: List<LogEntry>, advancedMode: Boolean) {
  if (events.isEmpty()) {
    Text("No entries yet.")
    return
  }

  events.forEach { entry ->
    StatusLine(entry.timestamp, if (advancedMode) entry.message else humanizeProtocolEvent(entry.message))
  }
}

@Composable
private fun SectionCard(
  title: String,
  content: @Composable () -> Unit
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.extraLarge,
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surface
    ),
    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(18.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold
      )
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
      content()
    }
  }
}

@Composable
private fun SettingSwitch(
  label: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(label, modifier = Modifier.weight(1f).padding(end = 12.dp))
    Switch(checked = checked, onCheckedChange = onCheckedChange)
  }
}

@Composable
private fun StatusLine(label: String, value: String) {
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Text(label, style = MaterialTheme.typography.labelMedium)
    Text(value, style = MaterialTheme.typography.bodyMedium)
  }
}

private fun socketStatus(connected: Boolean, initialized: Boolean): String = when {
  initialized -> "Ready"
  connected -> "Connected, waiting for init"
  else -> "Disconnected"
}

private fun formatDisconnectSummary(uiState: BridgeUiState): String = buildList {
  uiState.lastDisconnectCategory?.let { add("category=$it") }
  uiState.lastDisconnectSocketRole?.let { add("role=$it") }
  uiState.lastDisconnectHandshakeState?.let { add("handshake=$it") }
  uiState.lastDisconnectLastCommand?.let { add("last_in=$it") }
  uiState.lastDisconnectLastReply?.let { add("last_out=$it") }
}.ifEmpty {
  listOf("No disconnect details yet.")
}.joinToString(" | ")

private fun summarizeDisconnect(uiState: BridgeUiState): String {
  val category = uiState.lastDisconnectCategory ?: return "No disconnect details yet."
  return humanizeDisconnectCategory(category)
}

private fun formatLatency(uiState: BridgeUiState, advancedMode: Boolean): String {
  val latency = uiState.latencySummary
  val average = latency.averageMs ?: return "No latency sample yet."
  val max = latency.maxMs ?: average
  val last = latency.lastObservedMs ?: latency.lastDispatchMs ?: average
  return if (advancedMode) {
    val observed = latency.lastObservedMs?.let { "${it}ms" } ?: "unconfirmed"
    val effect = latency.lastEffectStatus ?: "unknown"
    "last=${last}ms | dispatch=${latency.lastDispatchMs ?: 0}ms | observed=$observed | effect=$effect | avg=${average}ms | max=${max}ms | samples=${latency.sampleCount}"
  } else {
    "avg ${average}ms | max ${max}ms | last ${last}ms"
  }
}

private fun latencyRating(uiState: BridgeUiState): String {
  val average = uiState.latencySummary.averageMs ?: return "Unknown"
  return when {
    average <= 150L -> "Low"
    average <= 400L -> "Medium"
    else -> "High"
  }
}

private fun humanizeDisconnectCategory(category: String): String = when (category) {
  "probe_socket_completed" -> "Verifyconnection probe completed normally."
  "request_socket_completed" -> "A request socket completed normally."
  "request_socket_peer_closed_after_command" -> "A request socket closed after finishing a command."
  "broadcast_replaced_by_same_client" -> "The main broadcast socket was replaced by the same sender."
  "broadcast_peer_closed_before_player" -> "The connection closed before the sender introduced itself."
  "broadcast_peer_closed_during_handshake" -> "The connection closed during handshake."
  "broadcast_peer_closed_after_init" -> "The main broadcast socket closed after initialization."
  "single_client_only" -> "A different sender was rejected because only one sender is allowed."
  "protocol_violation_before_player" -> "The sender talked before the player handshake step."
  "protocol_violation_before_protocol" -> "The sender skipped the protocol handshake step."
  "protocol_violation_before_init" -> "The sender sent commands before init completed."
  else -> if (category.startsWith("socket_read_error:")) {
    "The socket closed because of a read error."
  } else {
    category
  }
}

private fun humanizeProtocolEvent(message: String): String = when {
  message.startsWith("listener_started:") -> "Listener started."
  message.startsWith("listener_stopped") -> "Listener stopped."
  message.startsWith("socket_accepted:") -> "A client connected."
  message.startsWith("socket_rejected:") -> "A client was rejected."
  message.startsWith("broadcast_ready:") -> "Broadcast channel is ready."
  message.startsWith("socket_closed:") -> {
    val category = message.substringAfterLast(':', "")
    if (category.isBlank()) "A socket closed." else humanizeDisconnectCategory(category)
  }
  message.startsWith("socket_in:") -> "A protocol message was received."
  else -> message
}
