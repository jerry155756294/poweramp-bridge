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
import com.jerry155756294.powerampbridge.bridge.PowerampDataAccess
import com.jerry155756294.powerampbridge.bridge.PowerampDataAccessStatus
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
            onStart = { BridgeService.start(this) },
            onStop = { BridgeService.stop(this) },
            onRequestPowerampDataAccess = {
              PowerampDataAccess.request(this, app.appContainer.stateRepository)
            }
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
  onStop: () -> Unit,
  onRequestPowerampDataAccess: () -> Boolean
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
        onRequestPowerampDataAccess = onRequestPowerampDataAccess,
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
    title = "設定",
    subtitle = "調整啟動、網路與服務行為。",
    icon = Icons.Rounded.Settings
  ),
  Status(
    title = "即時狀態",
    subtitle = "查看 Bridge、主端連線與 Poweramp 狀態。",
    icon = Icons.Rounded.Tune
  ),
  Debug(
    title = "診斷",
    subtitle = "檢視最近的通訊協定、命令與 Poweramp 事件。",
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
  onRequestPowerampDataAccess: () -> Boolean,
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
      summary = if (uiState.listenerActive) "已準備好接受 LAN 或 Tailscale 控制。" else "請啟動服務以開始 Bridge。"
    )

    SectionCard("網路") {
      OutlinedTextField(
        value = portText,
        onValueChange = {
          portText = it.filter(Char::isDigit)
          portText.toIntOrNull()?.let { port ->
            scope.launch { repository.updatePort(port) }
          }
        },
        label = { Text("TCP 連接埠") },
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
        text = "請在 MBRC 主端使用此 LAN／Tailscale 位址與連接埠。",
        style = MaterialTheme.typography.bodySmall
      )
    }

    SectionCard("本機位址") {
      if (uiState.localAddresses.isEmpty()) {
        Text("尚未偵測到可用位址。")
      } else {
        uiState.localAddresses.forEach { address ->
          StatusLine("IP 位址", address)
        }
      }
    }

    SectionCard("服務") {
      SettingSwitch("開啟應用程式時自動啟動 Bridge", settings.autoStart) {
        scope.launch { repository.updateAutoStart(it) }
      }
      SettingSwitch("開機後自動啟動 Bridge", settings.startOnBoot) {
        scope.launch { repository.updateStartOnBoot(it) }
      }
      SettingSwitch("保持前景服務通知", settings.foregroundPersistent) {
        scope.launch { repository.updateForegroundPersistent(it) }
      }
      SettingSwitch("極簡通知診斷模式", settings.minimalForegroundNotification) {
        scope.launch { repository.updateMinimalForegroundNotification(it) }
      }
      SettingSwitch("進階診斷模式", settings.advancedDiagnosticsEnabled) {
        scope.launch { repository.updateAdvancedDiagnostics(it) }
      }
      Text(
        text = if (settings.advancedDiagnosticsEnabled) {
          "顯示原始通訊協定分類、握手狀態與底層診斷事件。"
        } else {
          "以較易讀的摘要顯示 Bridge 健康狀態。"
        },
        style = MaterialTheme.typography.bodySmall
      )
      if (settings.minimalForegroundNotification) {
        Text(
          text = "極簡通知診斷模式會讓 Bridge 通知盡量維持不變，以測試系統 UI 的暫停互動。",
          style = MaterialTheme.typography.bodySmall
        )
      }
    }

    PowerampDataAccessCard(
      status = uiState.powerampDataAccess,
      detail = uiState.powerampDataAccessDetail,
      hasRequestedBefore = settings.powerampDataAccessPermissionRequested,
      onRequest = {
        if (onRequestPowerampDataAccess()) {
          scope.launch { repository.markPowerampDataAccessPermissionRequested() }
        }
      }
    )

    ServiceActionRow(
      serviceRunning = uiState.serviceRunning,
      onStart = onStart,
      onStop = onStop
    )

    uiState.serviceStopSummary?.let { summary ->
      SectionCard("停止狀態") {
        StatusLine("狀態", summary)
        StatusLine("說明", uiState.serviceStopDetail ?: "沒有額外資訊。")
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
      summary = uiState.activeClient?.let { "已連線至 $it" } ?: "目前沒有已連線的主端。"
    )

    StatusHighlights(uiState)

    SectionCard("Bridge") {
      StatusLine("服務", uiState.serviceStatusLabel())
      StatusLine("監聽器", if (uiState.listenerActive) "TCP ${uiState.listenPort}" else "離線")
      StatusLine("主要 IP", uiState.localAddresses.firstOrNull() ?: "無法取得")
      StatusLine("主端 IP", uiState.activeClient ?: "無")
      StatusLine("主端 ID", uiState.clientId ?: "無")
      StatusLine("通訊協定", uiState.protocolVersion?.toString() ?: "未知")
      StatusLine("廣播 Socket", socketStatus(uiState.broadcastSocketConnected, uiState.broadcastInitialized))
      StatusLine("請求 Socket", uiState.activeRequestSocketCount.toString())
      StatusLine("位置同步", if (uiState.positionSyncActive) "進行中" else "閒置")
      StatusLine("最近 Verifyconnection", uiState.lastProbeAt ?: "尚無")
      StatusLine("停止摘要", uiState.serviceStopSummary ?: "無")
      StatusLine("停止詳情", uiState.serviceStopDetail ?: "無")
      StatusLine("最近拒絕", uiState.lastRejectedReason ?: "尚無")
      StatusLine(
        "最近中斷",
        if (advancedMode) uiState.lastDisconnectReason ?: "尚無" else summarizeDisconnect(uiState)
      )
      if (advancedMode) {
        StatusLine("中斷詳情", formatDisconnectSummary(uiState))
      }
    }

    SectionCard("控制延遲") {
      StatusLine("評級", latencyRating(uiState))
      StatusLine("最近命令", uiState.latencySummary.lastCommand ?: "尚無")
      StatusLine(
        if (advancedMode) "延遲詳情" else "延遲摘要",
        formatLatency(uiState, advancedMode)
      )
    }

    SectionCard("Poweramp") {
      StatusLine("可用性", if (uiState.powerampAvailable) "已連線" else "未偵測到")
      StatusLine("播放狀態", playbackStateLabel(uiState.playback.state))
      StatusLine("重複／隨機", "${repeatLabel(uiState.playback.repeat)}／${shuffleLabel(uiState.playback.shuffle)}")
      StatusLine("音量", "${uiState.playback.volume}%")
      StatusLine("封面狀態", uiState.coverState.summary())
      StatusLine("封面詳情", uiState.coverState.detail())
    }

    SectionCard("正在播放") {
      StatusLine("曲名", uiState.playback.track.title.ifBlank { "未知" })
      StatusLine("演出者", uiState.playback.track.artist.ifBlank { "未知" })
      StatusLine("專輯", uiState.playback.track.album.ifBlank { "未知" })
      StatusLine("路徑", uiState.playback.track.path.ifBlank { "未知" })
      StatusLine(
        "播放位置",
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
      accent = if (advancedMode) "進階模式" else "簡易模式",
      summary = uiState.lastError ?: "目前沒有 Bridge 錯誤。"
    )

    SectionCard("最近錯誤") {
      Text(uiState.lastError ?: "尚未記錄錯誤。")
    }

    SectionCard(if (advancedMode) "通訊協定事件" else "Bridge 事件") {
      EventList(uiState.recentProtocolEvents, advancedMode)
    }

    SectionCard("最近命令") {
      EventList(uiState.recentCommands, true)
    }

    SectionCard("Poweramp 事件") {
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
    HighlightChip("監聽器", if (uiState.listenerActive) "在線" else "離線")
    HighlightChip("主端", uiState.activeClient ?: "無")
    HighlightChip("播放", playbackStateLabel(uiState.playback.state))
    HighlightChip("位置同步", if (uiState.positionSyncActive) "進行中" else "閒置")
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
      Text(if (serviceRunning) "重新啟動 Bridge" else "啟動 Bridge")
    }
    TextButton(
      onClick = onStop,
      modifier = Modifier.weight(1f),
      shape = MaterialTheme.shapes.large
    ) {
      Text("停止 Bridge")
    }
  }
}

@Composable
private fun EventList(events: List<LogEntry>, advancedMode: Boolean) {
  if (events.isEmpty()) {
    Text("尚無事件。")
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

@Composable
private fun PowerampDataAccessCard(
  status: PowerampDataAccessStatus,
  detail: String?,
  hasRequestedBefore: Boolean,
  onRequest: () -> Unit
) {
  val (statusLabel, summary) = when (status) {
    PowerampDataAccessStatus.AVAILABLE -> "可讀取" to "Bridge 已可讀取 Poweramp 的清單與廣播資料。"
    PowerampDataAccessStatus.REQUESTED -> "等待驗證" to "已送出存取要求；請讓 Poweramp 保持執行，然後重新整理廣播清單。"
    PowerampDataAccessStatus.FAILED -> "讀取失敗" to "Poweramp 尚未提供清單存取權，請開啟 Poweramp 後重新要求。"
    PowerampDataAccessStatus.NOT_REQUESTED -> {
      if (hasRequestedBefore) {
        "等待驗證" to "先前已送出存取要求；重新整理廣播清單即可驗證結果。"
      } else {
        "尚未要求" to "必須取得存取權，Bridge 才能讀取 Poweramp 的廣播清單。"
      }
    }
  }

  SectionCard("Poweramp 資料存取") {
    StatusLine("狀態", statusLabel)
    Text(detail ?: summary, style = MaterialTheme.typography.bodySmall)
    Button(
      onClick = onRequest,
      modifier = Modifier.fillMaxWidth(),
      shape = MaterialTheme.shapes.large
    ) {
      Text(if (status == PowerampDataAccessStatus.FAILED) "重新要求資料存取權" else "要求 Poweramp 資料存取權")
    }
  }
}

private fun socketStatus(connected: Boolean, initialized: Boolean): String = when {
  initialized -> "已就緒"
  connected -> "已連線，等待初始化"
  else -> "未連線"
}

private fun formatDisconnectSummary(uiState: BridgeUiState): String = buildList {
  uiState.lastDisconnectCategory?.let { add("分類=$it") }
  uiState.lastDisconnectSocketRole?.let { add("角色=$it") }
  uiState.lastDisconnectHandshakeState?.let { add("握手=$it") }
  uiState.lastDisconnectLastCommand?.let { add("最後收到=$it") }
  uiState.lastDisconnectLastReply?.let { add("最後回覆=$it") }
}.ifEmpty {
  listOf("尚無中斷詳情。")
}.joinToString(" | ")

private fun summarizeDisconnect(uiState: BridgeUiState): String {
  val category = uiState.lastDisconnectCategory ?: return "尚無中斷詳情。"
  return humanizeDisconnectCategory(category)
}

private fun formatLatency(uiState: BridgeUiState, advancedMode: Boolean): String {
  val latency = uiState.latencySummary
  val average = latency.averageMs ?: return "尚無延遲樣本。"
  val max = latency.maxMs ?: average
  val last = latency.lastObservedMs ?: latency.lastDispatchMs ?: average
  return if (advancedMode) {
    val observed = latency.lastObservedMs?.let { "${it}ms" } ?: "未確認"
    val effect = latency.lastEffectStatus ?: "未知"
    "最後=${last}ms｜送出=${latency.lastDispatchMs ?: 0}ms｜觀測=$observed｜效果=$effect｜平均=${average}ms｜最大=${max}ms｜樣本=${latency.sampleCount}"
  } else {
    "平均 ${average}ms｜最大 ${max}ms｜最後 ${last}ms"
  }
}

private fun latencyRating(uiState: BridgeUiState): String {
  val average = uiState.latencySummary.averageMs ?: return "未知"
  return when {
    average <= 150L -> "低"
    average <= 400L -> "中"
    else -> "高"
  }
}

private fun humanizeDisconnectCategory(category: String): String = when (category) {
  "probe_socket_completed" -> "Verifyconnection 測試連線正常完成。"
  "request_socket_completed" -> "請求 Socket 已正常完成。"
  "request_socket_peer_closed_after_command" -> "請求 Socket 在完成命令後關閉。"
  "broadcast_replaced_by_same_client" -> "同一主端已取代原本的廣播 Socket。"
  "broadcast_peer_closed_before_player" -> "主端尚未自我識別前連線已關閉。"
  "broadcast_peer_closed_during_handshake" -> "連線在握手期間關閉。"
  "broadcast_peer_closed_after_init" -> "主廣播 Socket 在初始化後關閉。"
  "single_client_only" -> "已拒絕另一個主端，因為目前只允許一個主端。"
  "protocol_violation_before_player" -> "主端在 player 握手步驟前送出資料。"
  "protocol_violation_before_protocol" -> "主端略過了 protocol 握手步驟。"
  "protocol_violation_before_init" -> "主端在 init 完成前送出命令。"
  else -> if (category.startsWith("socket_read_error:")) {
    "Socket 因讀取錯誤而關閉。"
  } else {
    category
  }
}

private fun humanizeProtocolEvent(message: String): String = when {
  message.startsWith("listener_started:") -> "監聽器已啟動。"
  message.startsWith("listener_stopped") -> "監聽器已停止。"
  message.startsWith("socket_accepted:") -> "主端已連線。"
  message.startsWith("socket_rejected:") -> "主端連線已被拒絕。"
  message.startsWith("broadcast_ready:") -> "廣播通道已就緒。"
  message.startsWith("socket_closed:") -> {
    val category = message.substringAfterLast(':', "")
    if (category.isBlank()) "Socket 已關閉。" else humanizeDisconnectCategory(category)
  }
  message.startsWith("socket_in:") -> "已收到通訊協定訊息。"
  else -> message
}

private fun playbackStateLabel(state: String): String = when (state) {
  "playing" -> "播放中"
  "paused" -> "已暫停"
  "stopped" -> "已停止"
  else -> state
}

private fun repeatLabel(repeat: String): String = when (repeat) {
  "all" -> "全部重複"
  "one" -> "單曲重複"
  "none" -> "不重複"
  else -> repeat
}

private fun shuffleLabel(shuffle: String): String = when (shuffle) {
  "shuffle" -> "開啟"
  "off" -> "關閉"
  else -> shuffle
}
