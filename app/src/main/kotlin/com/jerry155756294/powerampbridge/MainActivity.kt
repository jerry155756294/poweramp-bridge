package com.jerry155756294.powerampbridge

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jerry155756294.powerampbridge.bridge.BridgeService
import com.jerry155756294.powerampbridge.bridge.BridgeUiState
import com.jerry155756294.powerampbridge.bridge.LogEntry
import com.jerry155756294.powerampbridge.data.BridgeSettings
import com.jerry155756294.powerampbridge.data.BridgeSettingsRepository
import com.jerry155756294.powerampbridge.ui.BridgeTheme
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
  private val notificationPermission =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
  private val mediaAudioPermission =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
      mediaAudioPermission.launch(Manifest.permission.READ_MEDIA_AUDIO)
    } else {
      mediaAudioPermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    val app = application as BridgeApplication
    setContent {
      BridgeTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
          BridgeApp(
            state = app.appContainer.stateRepository.state,
            settingsRepository = app.appContainer.settingsRepository,
            onStart = { serviceRunning -> BridgeService.startOrRestart(this, serviceRunning) },
            onStop = { BridgeService.stop(this) }
          )
        }
      }
    }
    BridgeService.start(this)
  }
}

@Composable
private fun BridgeApp(
  state: StateFlow<BridgeUiState>,
  settingsRepository: BridgeSettingsRepository,
  onStart: (serviceRunning: Boolean) -> Unit,
  onStop: () -> Unit
) {
  val uiState by state.collectAsStateWithLifecycle()
  val settings by settingsRepository.settings.collectAsStateWithLifecycle(initialValue = BridgeSettings())
  var selectedDestination by remember { mutableStateOf(BridgeDestination.Connect) }

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
      BridgeDestination.Connect -> ConnectTab(
        uiState = uiState,
        settings = settings,
        onStart = onStart,
        onStop = onStop,
        repository = settingsRepository,
        modifier = Modifier.padding(padding)
      )
      BridgeDestination.Diagnostics -> DiagnosticsTab(
        uiState = uiState,
        modifier = Modifier.padding(padding)
      )
    }
  }
}

private enum class BridgeDestination(
  @StringRes val titleRes: Int,
  @StringRes val subtitleRes: Int,
  val icon: ImageVector
) {
  Connect(R.string.tab_connect, R.string.tab_connect_subtitle, Icons.Rounded.Tune),
  Diagnostics(R.string.tab_diagnostics, R.string.tab_diagnostics_subtitle, Icons.Rounded.BugReport)
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
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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
                contentDescription = stringResource(destination.titleRes)
              )
            },
            label = { Text(stringResource(destination.titleRes)) }
          )
        }
      }
    }
  }
}

@Composable
private fun ConnectTab(
  uiState: BridgeUiState,
  settings: BridgeSettings,
  onStart: (serviceRunning: Boolean) -> Unit,
  onStop: () -> Unit,
  repository: BridgeSettingsRepository,
  modifier: Modifier = Modifier
) {
  val running = uiState.listenerActive
  val address = uiState.localAddresses.firstOrNull()
  val scope = rememberCoroutineScope()
  var portText by remember(settings.port) { mutableStateOf(settings.port.toString()) }
  val serviceStatus = when {
    uiState.serviceStopping -> stringResource(R.string.service_restarting)
    running -> stringResource(R.string.service_ready)
    else -> stringResource(R.string.service_stopped)
  }
  Column(
    modifier = modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp, vertical = 20.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    ScreenHero(
      destination = BridgeDestination.Connect,
      accent = serviceStatus,
      summary = stringResource(if (uiState.activeClient == null) R.string.connection_none else R.string.connection_active)
    )

    SectionCard(stringResource(R.string.connection_address_title)) {
      if (address == null) {
        Text(stringResource(R.string.connection_no_address))
      } else {
        Text(
          text = "$address:${if (running) uiState.listenPort else settings.port}",
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.Bold
        )
        Text(
          text = stringResource(R.string.connection_address_hint),
          style = MaterialTheme.typography.bodySmall
        )
      }
      uiState.localAddresses.drop(1).forEach { extraAddress ->
        StatusLine(stringResource(R.string.connection_other_address), "$extraAddress:${settings.port}")
      }
    }

    ServiceActionRow(uiState.serviceRunning, uiState.serviceStopping, onStart, onStop)
    StatusHighlights(uiState)

    SectionCard(stringResource(R.string.poweramp_section_title)) {
      StatusLine(
        stringResource(R.string.poweramp_availability),
        stringResource(if (uiState.powerampAvailable) R.string.connected else R.string.not_detected)
      )
      StatusLine(
        stringResource(R.string.playback_status),
        playbackStateLabel(uiState.playback.state)
      )
    }

    SectionCard(stringResource(R.string.settings_network_title)) {
      OutlinedTextField(
        value = portText,
        onValueChange = {
          portText = it.filter(Char::isDigit)
          portText.toIntOrNull()?.let { port -> scope.launch { repository.updatePort(port) } }
        },
        label = { Text(stringResource(R.string.settings_port_label)) },
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
      Text(stringResource(R.string.settings_port_hint), style = MaterialTheme.typography.bodySmall)
    }

    SectionCard(stringResource(R.string.settings_service_title)) {
      SettingSwitch(stringResource(R.string.settings_start_on_boot), settings.startOnBoot) {
        scope.launch { repository.updateStartOnBoot(it) }
      }
      SettingSwitch(stringResource(R.string.settings_foreground_persistent), settings.foregroundPersistent) {
        scope.launch { repository.updateForegroundPersistent(it) }
      }
      Text(stringResource(R.string.settings_foreground_explanation), style = MaterialTheme.typography.bodySmall)
    }

    BatteryOptimizationCard()
  }
}

@Composable
private fun DiagnosticsTab(
  uiState: BridgeUiState,
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
      destination = BridgeDestination.Diagnostics,
      accent = stringResource(R.string.diagnostics_protocol_raw),
      summary = stringResource(R.string.diagnostics_summary)
    )
    SectionCard(stringResource(R.string.diagnostics_protocol_raw)) {
      EventList(uiState.recentProtocolEvents)
    }
    SectionCard(stringResource(R.string.diagnostics_commands)) {
      EventList(uiState.recentCommands)
    }
    SectionCard(stringResource(R.string.diagnostics_poweramp)) {
      EventList(uiState.recentPowerampEvents)
    }
  }
}

@Composable
private fun ScreenHero(destination: BridgeDestination, accent: String, summary: String) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.extraLarge,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
          )
          Text(
            stringResource(destination.titleRes),
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
        stringResource(destination.subtitleRes),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f)
      )
      Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)
      ) {
        Text(
          "$accent | $summary",
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
    HighlightChip(
      stringResource(R.string.listener),
      stringResource(
        when {
          uiState.serviceStopping -> R.string.listener_restarting
          uiState.listenerActive -> R.string.online
          else -> R.string.offline
        }
      )
    )
    HighlightChip(stringResource(R.string.client), uiState.activeClient ?: stringResource(R.string.none))
    HighlightChip(stringResource(R.string.playback), playbackStateLabel(uiState.playback.state))
    HighlightChip(
      stringResource(R.string.position_sync),
      stringResource(if (uiState.positionSyncActive) R.string.in_progress else R.string.idle)
    )
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
      Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
  }
}

@Composable
private fun ServiceActionRow(
  serviceRunning: Boolean,
  serviceStopping: Boolean,
  onStart: (serviceRunning: Boolean) -> Unit,
  onStop: () -> Unit
) {
  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Button(
      onClick = { onStart(serviceRunning) },
      enabled = !serviceStopping,
      modifier = Modifier.weight(1f),
      shape = MaterialTheme.shapes.large,
      colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
    ) {
      Text(stringResource(if (serviceRunning) R.string.restart_bridge else R.string.start_bridge))
    }
    TextButton(
      onClick = onStop,
      enabled = !serviceStopping,
      modifier = Modifier.weight(1f),
      shape = MaterialTheme.shapes.large
    ) {
      Text(stringResource(R.string.stop_bridge))
    }
  }
}

@Composable
private fun EventList(events: List<LogEntry>) {
  if (events.isEmpty()) {
    Text(stringResource(R.string.no_events))
    return
  }
  events.forEach { entry ->
    StatusLine(entry.timestamp, entry.message)
  }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.extraLarge,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(18.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
      content()
    }
  }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
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

@Composable
private fun BatteryOptimizationCard() {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  var isExempt by remember { mutableStateOf(context.isIgnoringBatteryOptimizations()) }

  DisposableEffect(context, lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        isExempt = context.isIgnoringBatteryOptimizations()
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  if (isExempt) return

  SectionCard(stringResource(R.string.settings_background_title)) {
    Text(
      stringResource(R.string.settings_background_restricted),
      style = MaterialTheme.typography.titleSmall,
      fontWeight = FontWeight.SemiBold
    )
    Text(
      stringResource(R.string.settings_background_restricted_summary),
      style = MaterialTheme.typography.bodySmall
    )
    Button(
      onClick = {
        context.requestBatteryOptimizationExemption()
        isExempt = context.isIgnoringBatteryOptimizations()
      },
      modifier = Modifier.fillMaxWidth(),
      shape = MaterialTheme.shapes.large
    ) {
      Text(
        stringResource(R.string.settings_allow_unrestricted)
      )
    }
  }
}

private fun Context.isIgnoringBatteryOptimizations(): Boolean {
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
  return getSystemService(PowerManager::class.java)
    ?.isIgnoringBatteryOptimizations(packageName)
    ?: false
}

private fun Context.requestBatteryOptimizationExemption() {
  val packageUri = Uri.parse("package:$packageName")
  val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isIgnoringBatteryOptimizations()) {
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(packageUri)
  } else {
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(packageUri)
  }
  runCatching { startActivity(intent) }
    .onFailure {
      startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(packageUri))
    }
}

@Composable
private fun playbackStateLabel(state: String): String = when (state) {
  "playing" -> stringResource(R.string.playing)
  "paused" -> stringResource(R.string.paused)
  "stopped" -> stringResource(R.string.stopped)
  else -> state
}
