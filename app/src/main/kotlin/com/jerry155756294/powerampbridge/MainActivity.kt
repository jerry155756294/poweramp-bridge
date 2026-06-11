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

  LaunchedEffect(settings.autoStart, uiState.serviceRunning) {
    if (settings.autoStart && !uiState.serviceRunning) {
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
        listOf(
          stringResourceSafe(R.string.tab_settings),
          stringResourceSafe(R.string.tab_status),
          stringResourceSafe(R.string.tab_debug)
        ).forEachIndexed { index, title ->
          Tab(
            selected = selectedTab == index,
            onClick = { selectedTab = index },
            text = { Text(title) }
          )
        }
      }

      when (selectedTab) {
        0 -> SettingsTab(uiState, settings, onStart, onStop, settingsRepository)
        1 -> StatusTab(uiState)
        else -> DebugTab(uiState)
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
    SectionCard(stringResourceSafe(R.string.settings_network_title)) {
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
        text = stringResourceSafe(R.string.settings_no_token_notice),
        style = MaterialTheme.typography.bodySmall
      )
    }

    SectionCard(stringResourceSafe(R.string.settings_ip_title)) {
      if (uiState.localAddresses.isEmpty()) {
        Text(stringResourceSafe(R.string.settings_ip_empty))
      } else {
        uiState.localAddresses.forEach { address ->
          StatusLine("IP", address)
        }
      }
      Text(
        text = stringResourceSafe(R.string.settings_ip_hint),
        style = MaterialTheme.typography.bodySmall
      )
    }

    SectionCard(stringResourceSafe(R.string.settings_service_title)) {
      SettingSwitch(stringResourceSafe(R.string.settings_auto_start), settings.autoStart) {
        scope.launch { repository.updateAutoStart(it) }
      }
      SettingSwitch(stringResourceSafe(R.string.settings_start_on_boot), settings.startOnBoot) {
        scope.launch { repository.updateStartOnBoot(it) }
      }
      SettingSwitch(stringResourceSafe(R.string.settings_foreground_persistent), settings.foregroundPersistent) {
        scope.launch { repository.updateForegroundPersistent(it) }
      }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Button(onClick = onStart) { Text(stringResourceSafe(R.string.start_bridge)) }
      Button(onClick = onStop) { Text(stringResourceSafe(R.string.stop_bridge)) }
    }
  }
}

@Composable
private fun StatusTab(uiState: BridgeUiState) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    SectionCard("Bridge") {
      StatusLine("服務", if (uiState.serviceRunning) "執行中" else "已停止")
      StatusLine("Listener", if (uiState.listenerActive) "TCP ${uiState.listenPort}" else "未監聽")
      StatusLine("本機位址", uiState.localAddresses.firstOrNull() ?: stringResourceSafe(R.string.settings_ip_empty))
      StatusLine("Client IP", uiState.activeClient ?: stringResourceSafe(R.string.client_none))
      StatusLine("Client ID", uiState.clientId ?: "未提供")
      StatusLine("協議版本", uiState.protocolVersion?.toString() ?: "未知")
      StatusLine("主 socket", socketStatus(uiState.broadcastSocketConnected, uiState.broadcastInitialized))
      StatusLine("請求 socket", if (uiState.requestSocketConnected) "已連線" else "未連線")
      StatusLine("最近 verifyconnection", uiState.lastProbeAt ?: "尚無紀錄")
      StatusLine("最近拒絕", uiState.lastRejectedReason ?: "尚無紀錄")
    }

    SectionCard("Poweramp") {
      StatusLine(
        "可用性",
        if (uiState.powerampAvailable) stringResourceSafe(R.string.poweramp_present)
        else stringResourceSafe(R.string.poweramp_missing)
      )
      StatusLine("播放狀態", uiState.playback.state)
      StatusLine("Repeat / Shuffle", "${uiState.playback.repeat} / ${uiState.playback.shuffle}")
      StatusLine("音量", "${uiState.playback.volume}%")
    }

    SectionCard("目前曲目") {
      StatusLine("Title", uiState.playback.track.title.ifBlank { "無" })
      StatusLine("Artist", uiState.playback.track.artist.ifBlank { "無" })
      StatusLine("Album", uiState.playback.track.album.ifBlank { "無" })
      StatusLine("Path", uiState.playback.track.path.ifBlank { "無" })
      StatusLine(
        "Position",
        "${uiState.playback.track.positionMs / 1000}s / ${uiState.playback.track.durationMs / 1000}s"
      )
    }
  }
}

@Composable
private fun DebugTab(uiState: BridgeUiState) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    SectionCard("最後錯誤") {
      Text(uiState.lastError ?: "目前沒有錯誤")
    }
    SectionCard("最近收到的命令") {
      if (uiState.recentCommands.isEmpty()) {
        Text("尚無紀錄")
      } else {
        uiState.recentCommands.forEach { entry ->
          StatusLine(entry.timestamp, entry.message)
        }
      }
    }
    SectionCard("最近 Poweramp 事件") {
      if (uiState.recentPowerampEvents.isEmpty()) {
        Text("尚無紀錄")
      } else {
        uiState.recentPowerampEvents.forEach { entry ->
          StatusLine(entry.timestamp, entry.message)
        }
      }
    }
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
  initialized -> "已完成 init"
  connected -> "已握手，等待 init"
  else -> "未連線"
}

@Composable
private fun stringResourceSafe(id: Int): String = androidx.compose.ui.res.stringResource(id)
