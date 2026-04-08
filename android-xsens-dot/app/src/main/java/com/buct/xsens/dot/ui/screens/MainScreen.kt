package com.buct.xsens.dot.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.buct.xsens.dot.R
import com.buct.xsens.dot.engine.CollectionEngine
import com.buct.xsens.dot.ui.components.*
import com.buct.xsens.dot.ui.components.AccWaveformChart
import com.buct.xsens.dot.ui.components.GyroWaveformChart
import com.buct.xsens.dot.viewmodel.CollectionViewModel
import com.xsens.dot.android.sdk.models.DotPayload

@Composable
fun MainScreen(
    viewModel: CollectionViewModel,
    modifier: Modifier = Modifier
) {
    val scannedDevices      by viewModel.scannedDevices.collectAsState()
    val isScanning          by viewModel.isScanning.collectAsState()
    val connectedDevices    by viewModel.connectedDevices.collectAsState()
    val state               by viewModel.state.collectAsState()
    val recvCount           by viewModel.recvCount.collectAsState()
    val waveData            by viewModel.waveData.collectAsState()
    val selectedWaveSensor  by viewModel.selectedWaveSensor.collectAsState()
    val syncLog             by viewModel.syncLog.collectAsState()
    val isSyncing           by viewModel.isSyncing.collectAsState()
    val isSynced            by viewModel.isSynced.collectAsState()
    val needsSync           by viewModel.needsSync.collectAsState()
    val selectedForConnect  by viewModel.selectedForConnect.collectAsState()
    val scanMessage         by viewModel.scanMessage.collectAsState()
    val initProgress        by viewModel.initProgress.collectAsState()
    val headingActiveButton by viewModel.headingActiveButton.collectAsState()
    val selectedPayload     by viewModel.selectedPayload.collectAsState()
    val syncOutputRate      by viewModel.recOutputRate.collectAsState()
    val syncFilterProfile   by viewModel.recFilterProfile.collectAsState()
    val collectionState     by viewModel.state.collectAsState()
    val isMeasuring = collectionState == com.buct.xsens.dot.engine.CollectionEngine.CollectionState.Measuring
    val isConnected = connectedDevices.isNotEmpty()

    var selectedMode by remember { mutableStateOf<String?>(null) }
    val realtimeBlockedBy120 = syncOutputRate == 120

    LaunchedEffect(isConnected) {
        if (!isConnected) selectedMode = null
    }
    LaunchedEffect(syncOutputRate) {
        if (syncOutputRate == 120 && selectedMode == "realtime") selectedMode = null
    }

    LaunchedEffect(connectedDevices) {
        if (connectedDevices.isNotEmpty() && selectedWaveSensor >= connectedDevices.size) {
            viewModel.setSelectedWaveSensor(0)
        }
    }

    val payloadOptions = remember {
        listOf(
            DotPayload.PAYLOAD_TYPE_CUSTOM_MODE_1,
            DotPayload.PAYLOAD_TYPE_CUSTOM_MODE_2,
            DotPayload.PAYLOAD_TYPE_CUSTOM_MODE_3,
            DotPayload.PAYLOAD_TYPE_CUSTOM_MODE_4,
            DotPayload.PAYLOAD_TYPE_CUSTOM_MODE_5,
        )
    }

    Column(modifier = modifier.padding(20.dp)) {

        // ── 顶部提示横幅 ──
        if (scanMessage != null) {
            androidx.compose.material3.Card(
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = androidx.compose.ui.graphics.Color(0xFFFF9800)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = scanMessage!!, color = Color.White)
                    TextButton(onClick = { viewModel.clearScanMessage() }) {
                        Text("知道了", color = Color.White)
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // ── Header ──
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Image(
                painter = painterResource(id = R.drawable.buct_logo),
                contentDescription = "北京体育大学",
                modifier = Modifier
                    .height(48.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
            Column {
                Text("北京体育大学体育工程学院", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Xsens DOT 采集系统",
                    style = MaterialTheme.typography.bodySmall,
                    color = com.buct.xsens.dot.ui.theme.Muted
                )
            }
        }
        Spacer(modifier = Modifier.height(20.dp))

        // ══════════════════════════════════════════════
        // 1. 扫描与连接
        // ══════════════════════════════════════════════
        Panel(title = "扫描与连接") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(
                    text = if (isScanning) "扫描中..." else "扫描设备",
                    onClick = { if (isScanning) viewModel.stopScan() else viewModel.startScan() },
                    enabled = !isConnected
                )
                NeutralButton(
                    text = "全选",
                    onClick = { viewModel.selectAll() },
                    enabled = scannedDevices.isNotEmpty() && !isConnected
                )
                NeutralButton(
                    text = "取消全选",
                    onClick = { viewModel.deselectAll() },
                    enabled = scannedDevices.isNotEmpty() && !isConnected
                )
                Badge(text = "已选 ${selectedForConnect.size} / ${scannedDevices.size}", type = BadgeType.Info)
            }
            Spacer(modifier = Modifier.height(12.dp))
            DeviceList(
                devices = scannedDevices,
                selected = selectedForConnect,
                onToggle = { viewModel.toggleSelection(it) },
                enabled = !isConnected
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SuccessButton(
                    text = "连接选中设备",
                    onClick = { viewModel.connectSelected() },
                    enabled = selectedForConnect.isNotEmpty() && !isConnected
                )
                DangerButton(
                    text = "断开连接",
                    onClick = { viewModel.disconnect() },
                    enabled = isConnected
                )
            }
            // 连接状态行
            Spacer(modifier = Modifier.height(8.dp))
            val (initReady, initTotal) = initProgress
            val statusText = when {
                state == CollectionEngine.CollectionState.Connecting && initTotal > 0 && initReady < initTotal ->
                    "初始化中 $initReady/$initTotal..."
                state == CollectionEngine.CollectionState.Connecting -> "连接中..."
                isConnected && recvCount > 0 -> "已连接 ✓  接收: $recvCount 条"
                isConnected -> "已连接，等待数据..."
                else -> "未连接"
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Badge(text = statusText, type = if (isConnected) BadgeType.Ok else BadgeType.Err)
                Badge(text = "${connectedDevices.size} 台传感器", type = BadgeType.Info)
                Badge(text = state.name, type = BadgeType.Info)
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        // ══════════════════════════════════════════════
        // 2. 第一步：同步
        // ══════════════════════════════════════════════
        Panel(title = "多传感器时钟同步") {
            if (isConnected && needsSync && !isSyncing) {
                androidx.compose.material3.Card(
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = androidx.compose.ui.graphics.Color(0xFF1565C0)
                    ),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Text(
                        text = "已连接 ${connectedDevices.size} 台传感器，请靠拢（< 20 cm）后点击「SDK 硬件同步」",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
            Text(
                text = "同步后各传感器时钟对齐，SampleTimeFine 一致，多机数据可直接按时间戳对齐。不同步也可采集，但跨设备时间戳无法保证对齐。",
                style = MaterialTheme.typography.bodySmall,
                color = com.buct.xsens.dot.ui.theme.Muted,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // ── 同步参数（采样率 + 滤波档）──
            // 已同步时参数被固件锁定，必须先停止同步才能修改
            val canEditSyncParams = isConnected && !isSyncing && !isSynced
            if (isSynced) {
                Text(
                    text = "已同步状态下参数被固件锁定，修改前请先「停止同步」",
                    style = MaterialTheme.typography.bodySmall,
                    color = androidx.compose.ui.graphics.Color(0xFFFFA726),
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                Text(
                    text = "当前采样率：${syncOutputRate} Hz（已锁定）",
                    style = MaterialTheme.typography.bodySmall,
                    color = com.buct.xsens.dot.ui.theme.Muted,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                if (syncOutputRate == 120) {
                    Text(
                        text = "实时模式已按官方禁用（120Hz 仅支持离线采集），请点「离线模式」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = androidx.compose.ui.graphics.Color(0xFFFFA726),
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("采样率:", style = MaterialTheme.typography.bodySmall,
                    color = com.buct.xsens.dot.ui.theme.Muted)
                listOf(60, 120).forEach { rate ->
                    val sel = syncOutputRate == rate
                    if (sel) {
                        PrimaryButton(text = "${rate}Hz",
                            enabled = canEditSyncParams,
                            onClick = { viewModel.setRecOutputRate(rate) })
                    } else {
                        NeutralButton(text = "${rate}Hz",
                            enabled = canEditSyncParams,
                            onClick = { viewModel.setRecOutputRate(rate) })
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text("滤波:", style = MaterialTheme.typography.bodySmall,
                    color = com.buct.xsens.dot.ui.theme.Muted)
                listOf(0 to "General", 1 to "Dynamic").forEach { (idx, label) ->
                    val sel = syncFilterProfile == idx
                    if (sel) {
                        PrimaryButton(text = label,
                            enabled = canEditSyncParams,
                            onClick = { viewModel.setRecFilterProfile(idx) })
                    } else {
                        NeutralButton(text = label,
                            enabled = canEditSyncParams,
                            onClick = { viewModel.setRecFilterProfile(idx) })
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PrimaryButton(
                    text = if (isSyncing) "同步中..." else "SDK 硬件同步",
                    onClick = { viewModel.startSync() },
                    enabled = isConnected && !isSyncing
                )
                if (isSyncing || isSynced) {
                    DangerButton(text = "停止同步", onClick = { viewModel.stopSync() })
                }
                Badge(
                    text = if (isSynced) "已同步 ✓" else "未同步",
                    type = if (isSynced) BadgeType.Ok else BadgeType.Warn
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            SyncLogView(logs = syncLog)
        }
        Spacer(modifier = Modifier.height(16.dp))

        // ══════════════════════════════════════════════
        // 3. 模式选择 + 对应面板
        // ══════════════════════════════════════════════
        if (isConnected) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (selectedMode == "realtime") {
                    DangerButton(
                        text = "退出实时模式",
                        enabled = true,
                        onClick = { selectedMode = null }
                    )
                } else {
                    PrimaryButton(
                        text = "实时模式",
                        enabled = !isSyncing && !realtimeBlockedBy120,
                        onClick = { selectedMode = "realtime" }
                    )
                }
                if (selectedMode == "offline") {
                    DangerButton(
                        text = "退出离线模式",
                        enabled = true,
                        onClick = { selectedMode = null }
                    )
                } else {
                    NeutralButton(
                        text = "离线模式",
                        enabled = !isSyncing,
                        onClick = { selectedMode = "offline" }
                    )
                }
            }
            if (realtimeBlockedBy120) {
                Text(
                    text = "当前为 120Hz：与官方一致仅支持离线采集；实时请先「停止同步」改为 60Hz 后再同步。",
                    style = MaterialTheme.typography.bodySmall,
                    color = com.buct.xsens.dot.ui.theme.Muted,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))

            when (selectedMode) {
                "realtime" -> Panel(title = "实时采集") {
                    // ── 采集控制 ──
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!isMeasuring) {
                            PrimaryButton(
                                text = "开始实时采集",
                                enabled = isConnected && !isSyncing && !realtimeBlockedBy120,
                                onClick = { viewModel.startMeasuring() }
                            )
                        } else {
                            DangerButton(
                                text = "停止采集",
                                enabled = true,
                                onClick = { viewModel.stopMeasuring() }
                            )
                        }
                        Badge(
                            text = if (isMeasuring) "● 采集中" else if (isSynced) "已同步" else "未采集",
                            type = if (isMeasuring) BadgeType.Err else if (isSynced) BadgeType.Ok else BadgeType.Warn
                        )
                        if (!isSynced && isConnected && !isMeasuring) {
                            Text(
                                text = "（无需同步也可采集，但多设备时间戳不对齐）",
                                style = MaterialTheme.typography.bodySmall,
                                color = com.buct.xsens.dot.ui.theme.Muted
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    // ── Payload 模式 ──
                    var payloadExpanded by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("模式:", style = MaterialTheme.typography.bodyMedium,
                            color = com.buct.xsens.dot.ui.theme.Muted)
                        Box {
                            TextButton(
                                onClick = { if (isConnected) payloadExpanded = true },
                                enabled = isConnected
                            ) {
                                Text(
                                    text = payloadOptions.getOrNull(selectedPayload)
                                        ?.let { DotPayload.getPayloadTitle(it) } ?: "—",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            DropdownMenu(
                                expanded = payloadExpanded,
                                onDismissRequest = { payloadExpanded = false }
                            ) {
                                payloadOptions.forEachIndexed { i, type ->
                                    DropdownMenuItem(
                                        text = { Text(DotPayload.getPayloadTitle(type)) },
                                        onClick = {
                                            viewModel.setSelectedPayload(i)
                                            viewModel.applyPayloadMode(type)
                                            payloadExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))

                    // ── 朝向重置 ──
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("朝向:", style = MaterialTheme.typography.bodyMedium,
                            color = com.buct.xsens.dot.ui.theme.Muted)
                        if (headingActiveButton == "reset") {
                            PrimaryButton(text = "Heading Reset", enabled = isConnected,
                                onClick = { viewModel.headingReset() })
                            NeutralButton(text = "Heading Revert", enabled = isConnected,
                                onClick = { viewModel.headingRevert() })
                        } else {
                            NeutralButton(text = "Heading Reset", enabled = isConnected,
                                onClick = { viewModel.headingReset() })
                            PrimaryButton(text = "Heading Revert", enabled = isConnected,
                                onClick = { viewModel.headingRevert() })
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))

                    // ── 实时波形 ──
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("波形:", style = MaterialTheme.typography.bodyMedium,
                            color = com.buct.xsens.dot.ui.theme.Muted)
                        var waveExpanded by remember { mutableStateOf(false) }
                        Box {
                            TextButton(onClick = { if (connectedDevices.isNotEmpty()) waveExpanded = true }) {
                                Text(
                                    text = connectedDevices.getOrNull(selectedWaveSensor)
                                        ?.replace(":", "")?.uppercase() ?: "—",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            DropdownMenu(
                                expanded = waveExpanded,
                                onDismissRequest = { waveExpanded = false }
                            ) {
                                connectedDevices.forEachIndexed { i, id ->
                                    DropdownMenuItem(
                                        text = { Text(id.replace(":", "").uppercase()) },
                                        onClick = {
                                            viewModel.setSelectedWaveSensor(i)
                                            waveExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                    val waveKey = connectedDevices.getOrNull(selectedWaveSensor)?.let {
                        it.replace(":", "").replace("-", "").uppercase()
                    } ?: ""
                    val waveSnapshot = waveData[waveKey]
                    if (isConnected && waveSnapshot == null) {
                        Text(
                            text = "未收到波形数据，请确认 Payload 含 acc+gyro",
                            style = MaterialTheme.typography.bodySmall,
                            color = com.buct.xsens.dot.ui.theme.Muted
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        key(selectedWaveSensor, waveKey) {
                            AccWaveformChart(snapshot = waveSnapshot, modifier = Modifier.weight(1f))
                            GyroWaveformChart(snapshot = waveSnapshot, modifier = Modifier.weight(1f))
                        }
                    }
                }
                "offline" -> OfflineRecordingPanel(viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun OfflineRecordingPanel(viewModel: CollectionViewModel) {
    val inRecordingMode    by viewModel.inRecordingMode.collectAsState()
    val notifReady         by viewModel.recNotifReady.collectAsState()
    val flashInfo          by viewModel.recFlashInfo.collectAsState()
    val recordingActive    by viewModel.recRecordingActive.collectAsState()
    val fileList           by viewModel.recFileList.collectAsState()
    val exportProgress     by viewModel.recExportProgress.collectAsState()
    val exportDone         by viewModel.recExportDone.collectAsState()
    val recLog             by viewModel.recLog.collectAsState()
    val selectedExportIds  by viewModel.recSelectedExportIds.collectAsState()
    val allExportFields    = viewModel.recAllExportFields
    val selectedFileKeys   by viewModel.selectedFileKeys.collectAsState()

    Panel(title = "离线采集（Flash 录制）") {

        // ── 进入 / 退出 ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!inRecordingMode) {
                PrimaryButton(
                    text = "进入离线采集模式",
                    onClick = { viewModel.enterRecordingMode() }
                )
            } else {
                DangerButton(
                    text = "退出离线采集模式",
                    onClick = { viewModel.exitRecordingMode() }
                )
            }
        }

        if (inRecordingMode) {
            Spacer(modifier = Modifier.height(8.dp))

            // ── 通知状态 / 重试 ──
            val allReady = notifReady.isNotEmpty()
            if (!allReady) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Text(
                        text = "正在启用录制通知，请稍候…",
                        style = MaterialTheme.typography.bodySmall,
                        color = com.buct.xsens.dot.ui.theme.Muted,
                        modifier = Modifier.weight(1f)
                    )
                    NeutralButton(text = "重试", onClick = { viewModel.retryRecordingNotification() })
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            // ── Flash 容量 ──
            if (flashInfo.isNotEmpty()) {
                flashInfo.forEach { (addr, pair) ->
                    val (used, total) = pair
                    val percent   = if (total > 0) used.toFloat() / total else 0f
                    val usedKB    = used  / 1024
                    val totalKB   = total / 1024
                    val usedStr   = if (usedKB  >= 1024) "${"%.1f".format(usedKB  / 1024f)}MB" else "${usedKB}KB"
                    val totalStr  = if (totalKB >= 1024) "${"%.1f".format(totalKB / 1024f)}MB" else "${totalKB}KB"
                    Text(
                        text = "Flash [$addr]: $usedStr / $totalStr",
                        style = MaterialTheme.typography.bodySmall,
                        color = com.buct.xsens.dot.ui.theme.Muted
                    )
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { percent.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            // ── 录制控制 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!recordingActive) {
                    PrimaryButton(
                        text = "开始录制",
                        enabled = allReady,
                        onClick = { viewModel.startFlashRecording() }
                    )
                } else {
                    DangerButton(text = "停止录制", onClick = { viewModel.stopFlashRecording() })
                    Badge(text = "● 录制中", type = BadgeType.Err)
                }
                NeutralButton(
                    text = "刷新文件",
                    enabled = allReady && !recordingActive,
                    onClick = { viewModel.requestFiles() }
                )
                DangerButton(
                    text = "擦除 Flash",
                    enabled = allReady && !recordingActive,
                    onClick = { viewModel.eraseFlash() }
                )
            }
            Spacer(modifier = Modifier.height(6.dp))

            // ── 文件列表：按设备 ID 分组，设备头带三态复选框 ──
            if (fileList.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "录制文件",
                        style = MaterialTheme.typography.labelMedium,
                        color = com.buct.xsens.dot.ui.theme.Muted,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { viewModel.selectAllFiles() }) {
                        Text("全选", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { viewModel.clearFileSelection() }) {
                        Text("清空", style = MaterialTheme.typography.bodySmall)
                    }
                }
                fileList.forEach { (addr, files) ->
                    // 设备级三态复选框状态
                    val deviceKeys   = files.map { "$addr-${it.fileId}" }.toSet()
                    val selectedInDev = deviceKeys.count { it in selectedFileKeys }
                    val devState = when (selectedInDev) {
                        0            -> ToggleableState.Off
                        deviceKeys.size -> ToggleableState.On
                        else         -> ToggleableState.Indeterminate
                    }
                    val devDone = exportDone.contains(addr)
                    val devProg = exportProgress[addr] ?: 0

                    // 设备组头行
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                    ) {
                        TriStateCheckbox(
                            state = devState,
                            onClick = { viewModel.toggleDeviceSelection(addr) }
                        )
                        Text(
                            text = addr,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        if (devDone) Text(
                            text = "✓ 已导出",
                            style = MaterialTheme.typography.bodySmall,
                            color = com.buct.xsens.dot.ui.theme.Green
                        ) else if (devProg > 0) Text(
                            text = "↑ $devProg pkt",
                            style = MaterialTheme.typography.bodySmall,
                            color = com.buct.xsens.dot.ui.theme.Muted
                        )
                    }

                    // 该设备下的文件列表（缩进）
                    files.forEach { f ->
                        val key     = "$addr-${f.fileId}"
                        val checked = key in selectedFileKeys
                        val sizekb  = f.dataSize / 1024
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp)
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { viewModel.toggleFileSelection(addr, f.fileId) },
                                modifier = Modifier.padding(end = 4.dp)
                            )
                            Text(
                                text = "${f.fileName}  ${sizekb}KB",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (devDone) com.buct.xsens.dot.ui.theme.Green
                                        else com.buct.xsens.dot.ui.theme.Muted,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))

                // ── 导出字段选择 ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "导出字段",
                        style = MaterialTheme.typography.labelMedium,
                        color = com.buct.xsens.dot.ui.theme.Muted,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        viewModel.setExportIds(allExportFields.map { it.id }.toSet())
                    }) { Text("全选", style = MaterialTheme.typography.bodySmall) }
                    TextButton(onClick = {
                        val all = allExportFields.map { it.id }.toSet()
                        viewModel.setExportIds(all - selectedExportIds)
                    }) { Text("反选", style = MaterialTheme.typography.bodySmall) }
                }
                allExportFields.chunked(2).forEach { rowFields ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        rowFields.forEach { field ->
                            val checked = selectedExportIds.contains(field.id)
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { on ->
                                        val newIds = if (on) selectedExportIds + field.id
                                                     else (selectedExportIds - field.id).let {
                                                         if (it.isEmpty()) selectedExportIds else it
                                                     }
                                        viewModel.setExportIds(newIds)
                                    }
                                )
                                Text(
                                    text = field.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = com.buct.xsens.dot.ui.theme.Muted
                                )
                            }
                        }
                        if (rowFields.size == 1) Spacer(modifier = Modifier.weight(1f))
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))

                // ── 导出按钮 ──
                val allDone = exportDone.size >= fileList.size
                val selCount = selectedFileKeys.size
                val exportBtnText = when {
                    allDone  -> if (selCount > 0) "重新导出 ($selCount)" else "重新导出"
                    selCount > 0 -> "导出选中 ($selCount)"
                    else     -> "导出全部"
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(
                        text = exportBtnText,
                        enabled = !recordingActive,
                        onClick = { viewModel.exportFiles() }
                    )
                    if (!allDone && exportProgress.values.any { it > 0 }) {
                        DangerButton(text = "停止导出", onClick = { viewModel.stopExportFiles() })
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            // ── 操作日志 ──
            if (recLog.isNotEmpty()) {
                Text(
                    text = "操作日志",
                    style = MaterialTheme.typography.labelMedium,
                    color = com.buct.xsens.dot.ui.theme.Muted,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                val scrollState = rememberScrollState()
                LaunchedEffect(recLog.size) { scrollState.animateScrollTo(scrollState.maxValue) }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .verticalScroll(scrollState)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(8.dp)
                ) {
                    recLog.forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
