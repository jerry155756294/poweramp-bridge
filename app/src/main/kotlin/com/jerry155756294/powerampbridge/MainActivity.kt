package com.jerry155756294.powerampbridge

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
  var selectedTab by remember { mutableIntStateOf(0) }

  LaunchedEffect(settings.autoStart, uiState.serviceRunning, uiState.serviceStopping, uiState.manualStopActive) {
    if (uiState.shouldAutoStart(settings.autoStart)) {
      onStart()
    }
  }

  Scaffold { padding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .padding(padding)
    ) {
      TabRow(selectedTabIndex = selectedTab) {
        listOf("Settings", "Status", "Debug").forEachIndexed { index, title ->
          Tab(
            selected = selectedTab == index,
            onClick = { selectedTab = index },
            text = { Text(title) }
          )
        }
      }

      when (selectedTab) {
        0 -> SettingsTab(uiState, settings, onStart, onStop, settingsRepository)
        1 -> StatusTab(uiState, settings.advancedDiagnosticsEnabled)
        else -> DebugTab(uiState, settings.advancedDiagnosticsEnabled)
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
  repository: BridgeSettingsRepository
) {
  val scope = rememberCoroutineScope()
  var portText by remember(settings.port) { mutableStateOf(settings.port.toString()) }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
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
        modifier = Modifier.fillMaxWidth()
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
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Button(onClick = onStart) { Text("Start Bridge") }
      Button(onClick = onStop) { Text("Stop Bridge") }
    }

    uiState.serviceStopSummary?.let { summary ->
      SectionCard("Stop State") {
        StatusLine("Status", summary)
        StatusLine("Behavior", uiState.serviceStopDetail ?: "No extra detail.")
      }
    }
  }
}

@Composable
private fun StatusTab(uiState: BridgeUiState, advancedMode: Boolean) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
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
private fun DebugTab(uiState: BridgeUiState, advancedMode: Boolean) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
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
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold
      )
      Divider()
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
    horizontalArrangement = Arrangement.SpaceBetween
  ) {
    Text(label, modifier = Modifier.weight(1f))
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
