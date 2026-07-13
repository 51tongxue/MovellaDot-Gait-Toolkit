package com.buct.xsens.dot.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.buct.xsens.dot.R
import com.buct.xsens.dot.data.ScannedDevice
import com.buct.xsens.dot.engine.CollectionEngine
import com.buct.xsens.dot.engine.DotBatteryStatus
import com.buct.xsens.dot.engine.DotFirmwareStatus
import com.buct.xsens.dot.engine.EraseTaskProgress
import com.buct.xsens.dot.engine.ExportTaskProgress
import com.buct.xsens.dot.engine.FlashRecordingPhase
import com.buct.xsens.dot.ui.components.*
import com.buct.xsens.dot.ui.theme.Accent
import com.buct.xsens.dot.ui.theme.Bg
import com.buct.xsens.dot.ui.theme.Border
import com.buct.xsens.dot.ui.theme.Card as AppCardColor
import com.buct.xsens.dot.ui.theme.ErrorRed
import com.buct.xsens.dot.ui.theme.Green
import com.buct.xsens.dot.ui.theme.Muted
import com.buct.xsens.dot.ui.theme.Orange
import com.buct.xsens.dot.ui.theme.Red
import com.buct.xsens.dot.ui.theme.Surface as AppSurfaceColor
import com.buct.xsens.dot.viewmodel.CollectionViewModel
import com.xsens.dot.android.sdk.models.DotDevice
import com.xsens.dot.android.sdk.models.DotRecordingFileInfo
import com.xsens.dot.android.sdk.models.DotRecordingState
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun MainScreen(
    viewModel: CollectionViewModel,
    showBrandHeader: Boolean = true,
    modifier: Modifier = Modifier
) {
    val scannedDevices      by viewModel.scannedDevices.collectAsState()
    val isScanning          by viewModel.isScanning.collectAsState()
    val connectedDevices    by viewModel.connectedDevices.collectAsState()
    val syncLog             by viewModel.syncLog.collectAsState()
    val isSyncing           by viewModel.isSyncing.collectAsState()
    val syncProgress        by viewModel.syncProgress.collectAsState()
    val isSynced            by viewModel.isSynced.collectAsState()
    val needsSync           by viewModel.needsSync.collectAsState()
    val batteryStatus       by viewModel.batteryStatus.collectAsState()
    val deviceRssi          by viewModel.deviceRssi.collectAsState()
    val deviceRssiUpdatedAt by viewModel.deviceRssiUpdatedAt.collectAsState()
    val deviceSyncStates    by viewModel.deviceSyncStates.collectAsState()
    val firmwareStatus      by viewModel.firmwareStatus.collectAsState()
    val selectedForConnect  by viewModel.selectedForConnect.collectAsState()
    val scanMessage         by viewModel.scanMessage.collectAsState()
    val syncOutputRate      by viewModel.recOutputRate.collectAsState()
    val collectionState     by viewModel.state.collectAsState()
    val offlineFileMap      by viewModel.recFileList.collectAsState()
    val exportProgress      by viewModel.recExportProgress.collectAsState()
    val exportDone          by viewModel.recExportDone.collectAsState()
    val exportTaskProgress  by viewModel.recExportTaskProgress.collectAsState()
    val pendingRecordingExportKeys by viewModel.recPendingRecordingExportKeys.collectAsState()
    val preparingRecordingExport by viewModel.recPreparingRecordingExport.collectAsState()
    val eraseTaskProgress   by viewModel.recEraseTaskProgress.collectAsState()
    val selectedFileKeys    by viewModel.selectedFileKeys.collectAsState()
    val inRecordingMode     by viewModel.inRecordingMode.collectAsState()
    val captureWorkflowPreparing by viewModel.captureWorkflowPreparing.collectAsState()
    val notifReady          by viewModel.recNotifReady.collectAsState()
    val recordingPhase      by viewModel.recRecordingPhase.collectAsState()
    val recordingStates     by viewModel.recRecordingStates.collectAsState()
    val flashInfo           by viewModel.recFlashInfo.collectAsState()
    val recordingInProgress = recordingPhase == FlashRecordingPhase.Recording
    val recordingLocked = recordingPhase != FlashRecordingPhase.Idle
    val exportInProgress = exportTaskProgress.hasPendingFiles
    val isConnected = connectedDevices.isNotEmpty()
    val connectedCount = connectedDevices.size
    val canPowerOffDevices =
        !isSyncing &&
            !recordingLocked &&
            collectionState !in setOf(
                CollectionEngine.CollectionState.Measuring,
                CollectionEngine.CollectionState.Recording
            )

    var detailsExpanded by remember { mutableStateOf(false) }
    val connectedNormAddresses = remember(connectedDevices) {
        connectedDevices.map { normalizeUiAddress(it) }.toSet()
    }
    val selectedNormAddresses = remember(scannedDevices, selectedForConnect) {
        selectedForConnect.mapNotNull { index ->
            scannedDevices.getOrNull(index)?.address?.let(::normalizeUiAddress)
        }.toSet()
    }
    val notificationNormAddresses = remember(notifReady) {
        notifReady.map { normalizeUiAddress(it) }.toSet()
    }
    val flashInfoByNorm = remember(flashInfo) {
        flashInfo.mapKeys { normalizeUiAddress(it.key) }
    }
    val recordingStatesByNorm = remember(recordingStates) {
        recordingStates.mapKeys { normalizeUiAddress(it.key) }
    }
    val recordingTargetNormAddresses = remember(
        connectedNormAddresses,
        notificationNormAddresses,
        flashInfoByNorm,
        recordingStatesByNorm,
        recordingPhase,
        inRecordingMode
    ) {
        when {
            recordingPhase != FlashRecordingPhase.Idle -> {
                val knownTargets = recordingStatesByNorm.keys + notificationNormAddresses + flashInfoByNorm.keys
                knownTargets.ifEmpty { connectedNormAddresses }
            }
            inRecordingMode -> {
                val preparedTargets = notificationNormAddresses + flashInfoByNorm.keys
                preparedTargets.ifEmpty { connectedNormAddresses }
            }
            else -> emptySet()
        }
    }
    val activeDeviceNormAddresses = remember(connectedNormAddresses, selectedNormAddresses, recordingTargetNormAddresses, isSyncing) {
        when {
            recordingTargetNormAddresses.isNotEmpty() -> recordingTargetNormAddresses
            connectedNormAddresses.isNotEmpty() -> connectedNormAddresses
            isSyncing && selectedNormAddresses.isNotEmpty() -> selectedNormAddresses
            else -> emptySet()
        }
    }
    val offlineTargetNormAddresses = remember(activeDeviceNormAddresses, recordingTargetNormAddresses, inRecordingMode, recordingPhase) {
        when {
            recordingTargetNormAddresses.isNotEmpty() -> recordingTargetNormAddresses
            inRecordingMode || recordingPhase != FlashRecordingPhase.Idle -> activeDeviceNormAddresses
            else -> emptySet()
        }
    }
    val activeDeviceCount = if (activeDeviceNormAddresses.isNotEmpty()) activeDeviceNormAddresses.size else connectedCount
    val hasActiveDeviceTarget = activeDeviceNormAddresses.isNotEmpty()
    val visibleDevices = remember(scannedDevices, activeDeviceNormAddresses, isConnected, isSyncing) {
        when {
            activeDeviceNormAddresses.isNotEmpty() ->
                scannedDevices.filter { normalizeUiAddress(it.address) in activeDeviceNormAddresses }
            isConnected || isSyncing -> emptyList()
            else -> scannedDevices
        }
    }
    val connectedDevicesAllSynced = activeDeviceCount >= 2 &&
        activeDeviceNormAddresses.isNotEmpty() &&
        activeDeviceNormAddresses.all { deviceSyncStates[it] == true }
    val effectiveIsSynced = isSynced || connectedDevicesAllSynced
    val nowElapsedMs = rememberElapsedTicker(active = inRecordingMode || recordingLocked || isSyncing)
    var signalGates by remember { mutableStateOf<Map<String, DeviceSignalGate>>(emptyMap()) }
    LaunchedEffect(recordingPhase, offlineTargetNormAddresses, deviceRssi, deviceRssiUpdatedAt, nowElapsedMs) {
        signalGates = updateSignalGates(
            previous = signalGates,
            targetAddresses = offlineTargetNormAddresses,
            rssiByAddress = deviceRssi,
            rssiUpdatedAt = deviceRssiUpdatedAt,
            nowElapsedMs = nowElapsedMs,
            recordingPhase = recordingPhase
        )
    }
    val deviceLinkStatuses = remember(
        offlineTargetNormAddresses,
        connectedNormAddresses,
        notificationNormAddresses,
        recordingStatesByNorm,
        recordingPhase,
        deviceRssi,
        signalGates
    ) {
        offlineTargetNormAddresses.associateWith { normAddr ->
            resolveDeviceLinkStatus(
                recordingPhase = recordingPhase,
                isConnected = normAddr in connectedNormAddresses,
                participatesInRecording = true,
                notificationReady = normAddr in notificationNormAddresses,
                recordingState = recordingStatesByNorm[normAddr],
                rssi = deviceRssi[normAddr],
                signalWeak = signalGates[normAddr]?.weak == true
            )
        }
    }
    var syncPanelPinned by remember { mutableStateOf(false) }
    LaunchedEffect(isSyncing, effectiveIsSynced, connectedCount) {
        when {
            isSyncing || effectiveIsSynced || connectedCount >= 2 -> syncPanelPinned = true
            connectedCount == 0 -> syncPanelPinned = false
        }
    }
    val syncPanelVisible = syncPanelPinned || isSyncing || effectiveIsSynced || connectedCount >= 2
    val syncStatusText = when {
        isSyncing -> "同步中"
        effectiveIsSynced -> "已同步"
        !isConnected -> "未连接"
        connectedCount < 2 -> "单设备"
        needsSync -> "未同步"
        else -> "就绪"
    }
    val syncStatusTone = when {
        isSyncing -> CaptureStatusTone.Warning
        effectiveIsSynced -> CaptureStatusTone.Signal
        activeDeviceCount < 2 -> CaptureStatusTone.Muted
        else -> CaptureStatusTone.Warning
    }
    val displayConnectedCount = if (hasActiveDeviceTarget) activeDeviceNormAddresses.count { it in connectedNormAddresses } else connectedCount
    val offlineTargetCount = offlineTargetNormAddresses.size
    val workbenchTargetCount = when {
        offlineTargetCount > 0 -> offlineTargetCount
        activeDeviceCount > 0 -> activeDeviceCount
        else -> 0
    }
    val connectedTargetCount = offlineTargetNormAddresses.count { it in connectedNormAddresses }
    val offlineHasAllNotifications = offlineTargetCount > 0 &&
        offlineTargetNormAddresses.all { it in notificationNormAddresses }
    val offlineHasAllFlashInfo = offlineTargetCount > 0 &&
        offlineTargetNormAddresses.all { it in flashInfoByNorm }
    val offlineHasAllIdleStates = offlineTargetCount > 0 &&
        offlineTargetNormAddresses.all { norm ->
            recordingStatesByNorm[norm] in setOf(DotRecordingState.idle, DotRecordingState.success)
        }
    val offlineFlashHasSpace = offlineHasAllFlashInfo && offlineTargetNormAddresses.all { norm ->
        val (used, total) = flashInfoByNorm[norm] ?: return@all false
        total > 0 && used.toFloat() / total.toFloat() < 0.9f
    }
    val offlineOperationReady =
        inRecordingMode &&
            connectedTargetCount >= offlineTargetCount &&
            offlineHasAllNotifications &&
            offlineHasAllFlashInfo &&
            offlineHasAllIdleStates
    val offlineAllReady = offlineOperationReady && offlineFlashHasSpace
    val offlineFileCount = offlineFileMap.values.sumOf { it.size }
    val offlineHasOutOfRange = deviceLinkStatuses.values.any { it.health == DeviceLinkHealth.WaitingReconnect }
    val hasWeakSignal = deviceLinkStatuses.values.any { it.health == DeviceLinkHealth.WeakSignal }
    val hasDeviceError = deviceLinkStatuses.values.any { it.health == DeviceLinkHealth.Error }
    val hasHardRecordingError = recordingStatesByNorm.values.any {
        it == DotRecordingState.fail || it == DotRecordingState.invalidCmd
    }
    val activeRecordingStateCount = recordingStatesByNorm.values.count {
        it == DotRecordingState.onRecording
    }
    val eraseInProgress = eraseTaskProgress.isErasing
    val hasFlashSpaceBlock = inRecordingMode &&
        recordingPhase == FlashRecordingPhase.Idle &&
        offlineOperationReady &&
        !offlineFlashHasSpace
    val canSafelyStopRecording = recordingInProgress &&
        !offlineHasOutOfRange &&
        !hasWeakSignal &&
        !hasHardRecordingError &&
        activeRecordingStateCount > 0
    val workflowState = when {
        eraseInProgress -> "擦除中"
        exportInProgress -> "导出中"
        captureWorkflowPreparing -> "准备中"
        isSyncing -> "准备中"
        recordingPhase == FlashRecordingPhase.Starting -> "启动中"
        recordingPhase == FlashRecordingPhase.Stopping -> "停止中"
        offlineHasOutOfRange -> "等待回连"
        hasWeakSignal -> "信号弱"
        hasDeviceError -> "异常"
        recordingInProgress -> "录制中"
        !isConnected -> "未连接"
        isScanning -> "未连接"
        hasFlashSpaceBlock -> "空间不足"
        offlineFileCount > 0 -> "已完成"
        offlineAllReady -> "可录制"
        inRecordingMode -> "准备中"
        isConnected -> "待准备"
        else -> "异常"
    }
    val workflowTone = when (workflowState) {
        "可录制", "录制中", "已完成" -> CaptureStatusTone.Signal
        "未连接", "待准备" -> CaptureStatusTone.Muted
        "异常", "空间不足" -> CaptureStatusTone.Danger
        else -> CaptureStatusTone.Warning
    }
    val topStatusText = when {
        workflowState in setOf("启动中", "停止中", "等待回连", "信号弱", "擦除中", "导出中", "空间不足", "异常") -> workflowState
        recordingInProgress -> "录制中"
        isSyncing -> "同步中"
        isScanning -> "扫描中"
        activeDeviceCount == 0 -> "待扫描"
        activeDeviceCount >= 2 && effectiveIsSynced -> "$activeDeviceCount 已连 · 已同步"
        activeDeviceCount >= 2 -> "$activeDeviceCount 已连 · 未同步"
        else -> "$activeDeviceCount 已连"
    }
    val topStatusTone = when {
        workflowState in setOf("异常", "空间不足") -> CaptureStatusTone.Danger
        workflowState in setOf("启动中", "停止中", "等待回连", "信号弱", "擦除中", "导出中") -> CaptureStatusTone.Warning
        recordingInProgress -> CaptureStatusTone.Signal
        isSyncing || isScanning -> CaptureStatusTone.Warning
        activeDeviceCount == 0 || (activeDeviceCount >= 2 && !effectiveIsSynced) -> CaptureStatusTone.Warning
        else -> CaptureStatusTone.Signal
    }
    val stoppedStateReadyForMain = recordingStates.values.any {
        it == DotRecordingState.idle || it == DotRecordingState.success
    }
    val fileActionReady =
        offlineOperationReady || (inRecordingMode && connectedCount > 0 && !recordingLocked && stoppedStateReadyForMain)
    // 未连接时也允许先设定目标采样率/模式；真正写入设备由 ViewModel 在已连接设备上执行。
    // 仅在同步进行中或同步完成后锁定，避免与 SDK 的同步状态冲突。
    val canEditSyncParams = !isSyncing && !effectiveIsSynced
    val mainActionText = when {
        eraseInProgress -> "擦除中"
        exportInProgress -> "导出中"
        captureWorkflowPreparing -> "准备中"
        isSyncing -> "同步中"
        recordingPhase == FlashRecordingPhase.Starting -> "启动中"
        recordingPhase == FlashRecordingPhase.Stopping -> "停止中"
        offlineHasOutOfRange -> "等待回连"
        recordingInProgress && hasWeakSignal -> "靠近后停止"
        recordingInProgress && hasHardRecordingError -> "设备异常"
        recordingInProgress && hasDeviceError -> "停止剩余设备"
        recordingInProgress -> "停止录制"
        !isConnected -> when {
            isScanning -> "扫描中"
            scannedDevices.isEmpty() -> "扫描设备"
            selectedForConnect.isEmpty() -> "扫描"
            else -> "连接设备"
        }
        !inRecordingMode -> "准备采集"
        offlineAllReady -> "开始录制"
        hasFlashSpaceBlock -> if (offlineFileCount > 0) "先导出或擦除" else "先读取文件"
        else -> "准备中"
    }
    val mainActionEnabled = when {
        eraseInProgress -> false
        exportInProgress -> false
        captureWorkflowPreparing -> false
        isSyncing -> false
        recordingPhase == FlashRecordingPhase.Starting || recordingPhase == FlashRecordingPhase.Stopping -> false
        offlineHasOutOfRange -> false
        recordingInProgress -> canSafelyStopRecording
        !isConnected -> !isScanning && (scannedDevices.isEmpty() || selectedForConnect.isEmpty() || selectedForConnect.isNotEmpty())
        !inRecordingMode -> true
        hasFlashSpaceBlock -> false
        else -> offlineAllReady
    }
    val mainActionHint = when {
        eraseInProgress -> "正在擦除设备 Flash，请保持设备连接"
        exportInProgress -> "正在导出所选文件"
        isSyncing -> "正在同步设备，请保持设备靠近并等待完成"
        offlineHasOutOfRange -> "有设备超出蓝牙范围，恢复连接后再停止录制"
        hasWeakSignal -> "信号偏弱，建议靠近设备后再停止"
        hasHardRecordingError -> "有设备返回录制错误，请保持连接后重试"
        recordingInProgress && hasDeviceError -> "部分设备已停止，可停止仍在录制的设备"
        hasFlashSpaceBlock -> if (offlineFileCount > 0) {
            "设备存储空间不足，请先导出已有文件或擦除 Flash"
        } else {
            "设备存储空间不足，请先读取文件列表后导出或擦除 Flash"
        }
        !isConnected -> "需先连接设备"
        inRecordingMode && recordingPhase == FlashRecordingPhase.Idle && !offlineAllReady -> {
            if (offlineOperationReady && !offlineFlashHasSpace) "Flash 空间不足，请先导出或擦除" else "离线模式初始化中"
        }
        else -> null
    }
    val sectionSpacing = 16.dp

    if ((preparingRecordingExport || pendingRecordingExportKeys.isNotEmpty()) && !exportInProgress) {
        RecordingExportDecisionDialog(
            fileCount = pendingRecordingExportKeys.size,
            isPreparing = preparingRecordingExport,
            onExport = { viewModel.exportLatestRecording() },
            onDismiss = { viewModel.dismissLatestRecordingExport() }
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {

        if (scanMessage != null) {
            CaptureNoticeBar(
                text = scanMessage!!,
                onDismiss = { viewModel.clearScanMessage() }
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        if (showBrandHeader) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.buct_logo),
                        contentDescription = "北京体育大学",
                        modifier = Modifier
                            .height(38.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.White)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                    Text(
                        text = "运动步态数据采集系统",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                CaptureStatusChip(text = topStatusText, tone = topStatusTone)
            }
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = Border)
            Spacer(modifier = Modifier.height(sectionSpacing))
        }

        CaptureWorkbenchPanel(
            workflowState = workflowState,
            workflowTone = workflowTone,
            mainActionText = mainActionText,
            mainActionEnabled = mainActionEnabled,
            mainActionActive = recordingInProgress,
            mainActionHint = mainActionHint?.takeUnless { it == "需先连接设备" },
            connectedCount = displayConnectedCount,
            targetCount = workbenchTargetCount,
            syncText = syncStatusText,
            syncStatusTone = syncStatusTone,
            showSyncControls = syncPanelVisible,
            isSynced = effectiveIsSynced,
            isSyncing = isSyncing,
            syncProgress = syncProgress,
            syncActionLocked = recordingLocked,
            batteryText = summarizeBattery(batteryStatus, connectedDevices),
            sampleRateText = if (connectedDevices.isEmpty()) "—" else "${syncOutputRate}Hz",
            selectedFileCount = selectedFileKeys.size,
            showFileControls = inRecordingMode,
            fileActionsEnabled = fileActionReady && !recordingLocked,
            canExportFiles = offlineFileCount > 0 && !recordingLocked,
            exportInProgress = exportInProgress,
            exportTaskProgress = exportTaskProgress,
            eraseTaskProgress = eraseTaskProgress,
            canEraseFlash = offlineOperationReady && !recordingLocked && !exportInProgress,
            secondaryActionText = when {
                isConnected -> "断开"
                scannedDevices.isNotEmpty() && !isScanning -> "扫描"
                else -> null
            },
            secondaryActionEnabled = when {
                isConnected -> !recordingLocked
                else -> scannedDevices.isNotEmpty() && !isScanning
            },
            onSecondaryAction = {
                if (isConnected) viewModel.disconnect() else viewModel.startScan()
            },
            onStopSync = { viewModel.stopSync() },
            onStartSync = { viewModel.startSync() },
            onReadFiles = { viewModel.requestFiles() },
            onExportFiles = { viewModel.exportFiles() },
            onStopExportFiles = { viewModel.stopExportFiles() },
            onEraseFlash = { viewModel.eraseFlash() },
            onMainAction = {
                when {
                    recordingInProgress -> if (canSafelyStopRecording) viewModel.stopFlashRecording()
                    !isConnected -> if (selectedForConnect.isNotEmpty()) viewModel.connectSelected() else viewModel.startScan()
                    !inRecordingMode -> viewModel.prepareCapture()
                    offlineAllReady -> viewModel.startFlashRecording()
                }
            }
        )
        Spacer(modifier = Modifier.height(12.dp))

        if (visibleDevices.isEmpty()) {
            CaptureEmptyRow(text = if (isScanning) "正在扫描设备" else "未发现设备 · 点击扫描设备")
        } else {
            CaptureDeviceList(
                devices = visibleDevices,
                selectedAddresses = selectedNormAddresses,
                connectedAddresses = connectedDevices.toSet(),
                batteryStatus = batteryStatus,
                deviceSyncStates = deviceSyncStates,
            firmwareStatus = firmwareStatus,
            recordingStates = recordingStates,
            recordingPhase = recordingPhase,
            recordingTargets = recordingTargetNormAddresses,
            linkStatuses = deviceLinkStatuses,
            rssiStatus = deviceRssi,
            enabled = !isConnected && !isSyncing,
            compact = true,
            isSyncing = isSyncing,
            powerOffEnabled = canPowerOffDevices,
            onPowerOff = { viewModel.powerOffDevice(it) },
            onToggle = { viewModel.toggleSelection(it) }
        )
        }

        if (inRecordingMode && offlineFileMap.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            CompactOfflineFilePicker(
                fileList = offlineFileMap,
                selectedFileKeys = selectedFileKeys,
                exportDone = exportDone,
                exportProgress = exportProgress,
                exportTaskProgress = exportTaskProgress,
                recordingLocked = recordingLocked,
                operationReady = fileActionReady,
                onToggleDeviceSelection = { viewModel.toggleDeviceSelection(it) },
                onToggleFileSelection = { addr, fileId -> viewModel.toggleFileSelection(addr, fileId) },
                onSelectAll = { viewModel.selectAllFiles() },
                onClear = { viewModel.clearFileSelection() },
                onExport = { viewModel.exportFiles() },
                onStopExport = { viewModel.stopExportFiles() }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        CaptureAdvancedPanel(
            expanded = detailsExpanded,
            connectedDevices = connectedDevices,
            recordingStates = recordingStates,
            recordingPhase = recordingPhase,
            linkStatuses = deviceLinkStatuses,
            rssiStatus = deviceRssi,
            flashInfo = flashInfo,
            notificationReady = notifReady,
            inRecordingMode = inRecordingMode,
            syncOutputRate = syncOutputRate,
            canEditSyncParams = canEditSyncParams,
            recordingLocked = recordingLocked,
            operationReady = offlineOperationReady,
            eraseTaskProgress = eraseTaskProgress,
            syncLog = syncLog,
            onToggleExpanded = { detailsExpanded = !detailsExpanded },
            onSetRate = { viewModel.setRecOutputRate(it) },
            onEraseFlash = { viewModel.eraseFlash() }
        )
        Spacer(modifier = Modifier.height(18.dp))


    }
}

@Composable
private fun OfflineRecordingPanel(
    viewModel: CollectionViewModel,
    compact: Boolean = false
) {
    val connectedDevices    by viewModel.connectedDevices.collectAsState()
    val recOutputRate       by viewModel.recOutputRate.collectAsState()
    val inRecordingMode    by viewModel.inRecordingMode.collectAsState()
    val notifReady         by viewModel.recNotifReady.collectAsState()
    val flashInfo          by viewModel.recFlashInfo.collectAsState()
    val recordingPhase     by viewModel.recRecordingPhase.collectAsState()
    val recordingStates    by viewModel.recRecordingStates.collectAsState()
    val fileList           by viewModel.recFileList.collectAsState()
    val exportProgress     by viewModel.recExportProgress.collectAsState()
    val exportDone         by viewModel.recExportDone.collectAsState()
    val exportTaskProgress by viewModel.recExportTaskProgress.collectAsState()
    val deviceRssi         by viewModel.deviceRssi.collectAsState()
    val recLog             by viewModel.recLog.collectAsState()
    val selectedExportIds  by viewModel.recSelectedExportIds.collectAsState()
    val allExportFields    = viewModel.recAllExportFields
    val selectedFileKeys   by viewModel.selectedFileKeys.collectAsState()
    val fileCount = fileList.values.sumOf { it.size }
    val connectedCount = connectedDevices.size
    val targetDeviceCount = listOf(connectedCount, notifReady.size, flashInfo.size, recordingStates.size).maxOrNull() ?: connectedCount
    val hasAllNotifications = targetDeviceCount > 0 && notifReady.size >= targetDeviceCount
    val hasAllFlashInfo = targetDeviceCount > 0 && flashInfo.size >= targetDeviceCount
    val flashHasSpace = hasAllFlashInfo && flashInfo.values.all { (used, total) ->
        total > 0 && used.toFloat() / total.toFloat() < 0.9f
    }
    val operationReady =
        inRecordingMode &&
            connectedCount >= targetDeviceCount &&
            hasAllNotifications &&
            hasAllFlashInfo
    val allReady = operationReady && flashHasSpace
    val recordingBusy = recordingPhase == FlashRecordingPhase.Starting || recordingPhase == FlashRecordingPhase.Stopping
    val recordingInProgress = recordingPhase == FlashRecordingPhase.Recording
    val recordingLocked = recordingPhase != FlashRecordingPhase.Idle
    val connectedNormAddresses = remember(connectedDevices) {
        connectedDevices.map { normalizeUiAddress(it) }.toSet()
    }
    val recordingTargetNormAddresses = remember(connectedNormAddresses, notifReady, flashInfo, recordingStates, recordingPhase) {
        when {
            recordingPhase != FlashRecordingPhase.Idle -> {
                val knownTargets = (recordingStates.keys + notifReady + flashInfo.keys)
                    .map { normalizeUiAddress(it) }
                    .toSet()
                knownTargets.ifEmpty { connectedNormAddresses }
            }
            else -> emptySet()
        }
    }
    val hasOutOfRange = recordingInProgress && recordingTargetNormAddresses.any { it !in connectedNormAddresses }
    val hasWeakSignal = recordingInProgress &&
        recordingTargetNormAddresses.mapNotNull { deviceRssi[it] }.any { it <= SIGNAL_WEAK_ENTER_DBM }
    val canSafelyStopRecording =
        recordingInProgress && !hasOutOfRange && !hasWeakSignal
    val stoppedStateReady = recordingStates.values.any {
        it == DotRecordingState.idle || it == DotRecordingState.success
    }
    val fileActionReady =
        operationReady || (inRecordingMode && connectedCount > 0 && !recordingLocked && stoppedStateReady)
    val totalUsedBytes = flashInfo.values.sumOf { it.first.toLong() }
    val compactStatusText = when {
        recordingPhase == FlashRecordingPhase.Starting -> "启动中"
        recordingPhase == FlashRecordingPhase.Stopping -> "停止中"
        hasOutOfRange -> "超距"
        recordingInProgress -> "录制中"
        allReady -> "已就绪"
        else -> "初始化"
    }
    val compactStatusTone = when {
        hasOutOfRange || recordingBusy -> CaptureStatusTone.Warning
        recordingInProgress -> CaptureStatusTone.Signal
        allReady -> CaptureStatusTone.Signal
        else -> CaptureStatusTone.Warning
    }

    if (compact) {
        if (!inRecordingMode) return

        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "离线状态",
                    style = MaterialTheme.typography.labelMedium,
                    color = Muted
                )
                CaptureStatusChip(
                    text = compactStatusText,
                    tone = compactStatusTone
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "设备 $connectedCount/$targetDeviceCount · 采样 ${recOutputRate}Hz · 缓冲 ${formatStorageSize(totalUsedBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = Muted
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (hasOutOfRange) {
                CaptureQuickPanel {
                    Text(
                        text = "有设备超出蓝牙范围。请先回到蓝牙范围，待设备恢复连接后再停止录制，否则文件可能不完整。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            if (!allReady && !recordingLocked) {
                CaptureQuickPanel {
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
                            text = "正在启用录制通知",
                            style = MaterialTheme.typography.bodySmall,
                            color = Muted,
                            modifier = Modifier.weight(1f)
                        )
	                        CaptureInlineButton(
	                            text = "重试",
	                            tone = CaptureButtonTone.Subtle,
	                            onClick = { viewModel.retryRecordingNotification() }
	                        )
	                    }
	                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            if (flashInfo.isNotEmpty() || recordingStates.isNotEmpty() || notifReady.isNotEmpty()) {
                OfflineDeviceStatusPanel(
                    flashInfo = flashInfo,
                    connectedDevices = connectedDevices,
	                    notificationReady = notifReady,
	                    recordingStates = recordingStates,
	                    recordingPhase = recordingPhase,
                        rssiStatus = deviceRssi
	                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            if (fileList.isNotEmpty()) {
                OfflineFileSelectorSummary(
                    deviceCount = fileList.size,
                    fileCount = fileCount,
                    selectedCount = selectedFileKeys.size
                )
                Spacer(modifier = Modifier.height(10.dp))
                CompactOfflineFilePicker(
                    fileList = fileList,
                    selectedFileKeys = selectedFileKeys,
                    exportDone = exportDone,
                    exportProgress = exportProgress,
                    exportTaskProgress = exportTaskProgress,
                    recordingLocked = recordingLocked,
                    operationReady = fileActionReady,
                    onToggleDeviceSelection = { viewModel.toggleDeviceSelection(it) },
                    onToggleFileSelection = { addr, fileId -> viewModel.toggleFileSelection(addr, fileId) },
                    onSelectAll = { viewModel.selectAllFiles() },
                    onClear = { viewModel.clearFileSelection() },
                    onExport = { viewModel.exportFiles() },
                    onStopExport = { viewModel.stopExportFiles() }
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CaptureInlineButton(
	                    text = "退出离线模式",
	                    tone = CaptureButtonTone.Subtle,
	                    modifier = Modifier.weight(1f),
	                    enabled = !recordingLocked,
	                    onClick = { viewModel.exitRecordingMode() }
	                )
                CaptureInlineButton(
                    text = "擦除 Flash",
	                    enabled = operationReady && !recordingLocked,
                    tone = CaptureButtonTone.Danger,
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.eraseFlash() }
                )
            }

            if (recLog.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "日志",
                    style = MaterialTheme.typography.labelMedium,
                    color = Muted
                )
                Spacer(modifier = Modifier.height(6.dp))
                CaptureCompactLog(logs = recLog.takeLast(4))
            }
        }
        return
    }

    Panel(title = "离线详情") {

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
	                    enabled = !recordingLocked,
	                    onClick = { viewModel.exitRecordingMode() }
	                )
            }
        }

        if (inRecordingMode) {
            Spacer(modifier = Modifier.height(8.dp))

            // ── 通知状态 / 重试 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                DashboardMetricTile(
	                    label = "录制状态",
	                    value = when {
	                        recordingPhase == FlashRecordingPhase.Starting -> "启动中"
	                        recordingPhase == FlashRecordingPhase.Stopping -> "停止中"
	                        hasOutOfRange -> "等待回连"
	                        recordingInProgress -> "录制中"
	                        allReady -> "已就绪"
	                        else -> "初始化"
	                    },
	                    supporting = when {
	                        recordingPhase == FlashRecordingPhase.Starting -> "正在启动设备录制"
	                        recordingPhase == FlashRecordingPhase.Stopping -> "正在结束设备录制"
	                        hasOutOfRange -> "等待设备回连"
	                        allReady -> "可开始录制"
	                        else -> "初始化中"
	                    },
	                    accent = when {
	                        hasOutOfRange || recordingBusy -> Orange
	                        recordingInProgress -> ErrorRed
	                        allReady -> Green
	                        else -> Orange
	                    },
                    modifier = Modifier.weight(1f)
                )
                DashboardMetricTile(
                    label = "文件缓存",
                    value = "${fileCount} 份",
                    supporting = if (selectedFileKeys.isNotEmpty()) "${selectedFileKeys.size} 份已选" else "可刷新导出",
                    accent = Accent,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))

            if (hasOutOfRange) {
                CaptureQuickPanel {
                    Text(
                        text = "有设备超出蓝牙范围。待设备回到范围内并恢复连接后，再停止录制和退出离线模式。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            if (!allReady && !recordingLocked) {
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
                        text = "正在启用录制通知",
                        style = MaterialTheme.typography.bodySmall,
                        color = com.buct.xsens.dot.ui.theme.Muted,
                        modifier = Modifier.weight(1f)
	                    )
	                    NeutralButton(text = "重试", onClick = { viewModel.retryRecordingNotification() })
	                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            // ── 每台设备状态 / Flash 容量 ──
            if (flashInfo.isNotEmpty() || recordingStates.isNotEmpty() || notifReady.isNotEmpty()) {
                OfflineDeviceStatusPanel(
                    flashInfo = flashInfo,
                    connectedDevices = connectedDevices,
	                    notificationReady = notifReady,
	                    recordingStates = recordingStates,
	                    recordingPhase = recordingPhase,
                        rssiStatus = deviceRssi
		                )
                Spacer(modifier = Modifier.height(8.dp))
            }

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
                if (recordingPhase == FlashRecordingPhase.Idle) {
                    PrimaryButton(
                        text = "开始录制",
                        enabled = allReady,
                        onClick = { viewModel.startFlashRecording() }
                    )
                } else if (recordingInProgress) {
                    DangerButton(
                        text = if (hasOutOfRange) "等待回连" else if (hasWeakSignal) "靠近后停止" else "停止录制",
                        enabled = canSafelyStopRecording,
                        onClick = { viewModel.stopFlashRecording() }
                    )
                    Badge(
                        text = if (hasOutOfRange) "● 等待回连" else if (hasWeakSignal) "● 信号弱" else "● 录制中",
                        type = if (hasOutOfRange || hasWeakSignal) BadgeType.Warn else BadgeType.Err
                    )
                } else {
                    NeutralButton(
                        text = if (recordingPhase == FlashRecordingPhase.Starting) "启动中" else "停止中",
                        enabled = false,
                        onClick = {}
                    )
                }
                NeutralButton(
                    text = "读取列表",
                    enabled = fileActionReady && !recordingLocked,
                    onClick = { viewModel.requestFiles() }
                )
                DangerButton(
                    text = "擦除 Flash",
                    enabled = operationReady && !recordingLocked,
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
                        CaptureSelectionBox(
                            state = devState,
                            onClick = { viewModel.toggleDeviceSelection(addr) }
                        )
                        Spacer(modifier = Modifier.width(10.dp))
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
                                .background(
                                    if (checked) Green.copy(alpha = 0.10f) else Color.Transparent,
                                    RoundedCornerShape(6.dp)
                                )
                                .clickable { viewModel.toggleFileSelection(addr, f.fileId) }
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            CaptureSelectionBox(
                                state = if (checked) ToggleableState.On else ToggleableState.Off
                            )
                            Spacer(modifier = Modifier.width(10.dp))
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
                        enabled = fileActionReady && fileCount > 0 && !recordingLocked,
                        onClick = { viewModel.exportFiles() }
                    )
                    if (exportTaskProgress.hasPendingFiles) {
                        DangerButton(text = "停止导出", onClick = { viewModel.stopExportFiles() })
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            // ── 操作日志 ──
            if (recLog.isNotEmpty()) {
                Text(
                    text = "日志",
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
                        .background(AppSurfaceColor, RoundedCornerShape(8.dp))
                        .border(1.dp, Border, RoundedCornerShape(8.dp))
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

private const val SIGNAL_WEAK_ENTER_DBM = -85
private const val SIGNAL_WEAK_EXIT_DBM = -78
private const val RSSI_STALE_MS = 3_000L
private const val RSSI_STALE_CONFIRM_SAMPLES = 4
private const val SIGNAL_WEAK_ENTER_SAMPLES = 2
private const val SIGNAL_WEAK_EXIT_SAMPLES = 3

private enum class CaptureStatusTone { Signal, Warning, Danger, Muted }

private enum class CaptureButtonTone { Subtle, Success, Danger }

private enum class DeviceLinkHealth {
    Connected,
    Ready,
    Starting,
    Recording,
    WeakSignal,
    WaitingReconnect,
    Stopping,
    Complete,
    Error,
    Disconnected,
    Initializing
}

private data class DeviceSignalGate(
    val weak: Boolean = false,
    val lowCount: Int = 0,
    val strongCount: Int = 0,
    val lastSampleAt: Long? = null
)

private data class DeviceLinkStatus(
    val label: String,
    val health: DeviceLinkHealth,
    val rssi: Int?
)

private data class CaptureSegmentOption<T>(
    val value: T,
    val label: String,
    val enabled: Boolean = true
)

@Composable
private fun rememberElapsedTicker(active: Boolean): Long {
    var now by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(active) {
        while (active) {
            now = SystemClock.elapsedRealtime()
            delay(500)
        }
        now = SystemClock.elapsedRealtime()
    }
    return now
}

private fun updateSignalGates(
    previous: Map<String, DeviceSignalGate>,
    targetAddresses: Set<String>,
    rssiByAddress: Map<String, Int>,
    rssiUpdatedAt: Map<String, Long>,
    nowElapsedMs: Long,
    recordingPhase: FlashRecordingPhase
): Map<String, DeviceSignalGate> {
    if (recordingPhase != FlashRecordingPhase.Recording) {
        return targetAddresses.associateWith { DeviceSignalGate() }
    }
    return targetAddresses.associateWith { normAddr ->
        val old = previous[normAddr] ?: DeviceSignalGate()
        val sampleAt = rssiUpdatedAt[normAddr]
        val rssi = rssiByAddress[normAddr]
        val isStale = sampleAt != null && nowElapsedMs - sampleAt > RSSI_STALE_MS
        if (isStale) {
            val staleCount = old.lowCount + 1
            old.copy(
                weak = old.weak || staleCount >= RSSI_STALE_CONFIRM_SAMPLES,
                lowCount = staleCount,
                strongCount = 0,
                lastSampleAt = sampleAt,
            )
        } else if (sampleAt != null && sampleAt != old.lastSampleAt && rssi != null) {
            val lowCount = if (rssi <= SIGNAL_WEAK_ENTER_DBM) old.lowCount + 1 else 0
            val strongCount = if (rssi >= SIGNAL_WEAK_EXIT_DBM) old.strongCount + 1 else 0
            val nextWeak = when {
                old.weak && strongCount >= SIGNAL_WEAK_EXIT_SAMPLES -> false
                !old.weak && lowCount >= SIGNAL_WEAK_ENTER_SAMPLES -> true
                else -> old.weak
            }
            DeviceSignalGate(
                weak = nextWeak,
                lowCount = lowCount,
                strongCount = strongCount,
                lastSampleAt = sampleAt
            )
        } else {
            old
        }
    }
}

private fun resolveDeviceLinkStatus(
    recordingPhase: FlashRecordingPhase,
    isConnected: Boolean,
    participatesInRecording: Boolean,
    notificationReady: Boolean,
    recordingState: DotRecordingState?,
    rssi: Int?,
    signalWeak: Boolean
): DeviceLinkStatus {
    val health = when {
        !participatesInRecording -> if (isConnected) DeviceLinkHealth.Connected else DeviceLinkHealth.Disconnected
        recordingPhase == FlashRecordingPhase.Starting -> DeviceLinkHealth.Starting
        recordingPhase == FlashRecordingPhase.Stopping -> DeviceLinkHealth.Stopping
        !isConnected -> DeviceLinkHealth.WaitingReconnect
        recordingState in setOf(DotRecordingState.fail, DotRecordingState.invalidCmd) -> DeviceLinkHealth.Error
        recordingPhase == FlashRecordingPhase.Recording && signalWeak -> DeviceLinkHealth.WeakSignal
        recordingPhase == FlashRecordingPhase.Recording &&
            recordingState in setOf(DotRecordingState.idle, DotRecordingState.success) -> DeviceLinkHealth.Error
        recordingPhase == FlashRecordingPhase.Recording -> DeviceLinkHealth.Recording
        notificationReady -> DeviceLinkHealth.Ready
        recordingState in setOf(DotRecordingState.idle, DotRecordingState.success) -> DeviceLinkHealth.Complete
        else -> DeviceLinkHealth.Initializing
    }
    return DeviceLinkStatus(
        label = when (health) {
            DeviceLinkHealth.Connected -> "已连接"
            DeviceLinkHealth.Ready -> "已就绪"
            DeviceLinkHealth.Starting -> "启动中"
            DeviceLinkHealth.Recording -> "录制中"
            DeviceLinkHealth.WeakSignal -> "信号弱"
            DeviceLinkHealth.WaitingReconnect -> "等待回连"
            DeviceLinkHealth.Stopping -> "停止中"
            DeviceLinkHealth.Complete -> "已完成"
            DeviceLinkHealth.Error -> "异常"
            DeviceLinkHealth.Disconnected -> "未连接"
            DeviceLinkHealth.Initializing -> "初始化"
        },
        health = health,
        rssi = rssi
    )
}

private fun linkStatusColor(health: DeviceLinkHealth): Color =
    when (health) {
        DeviceLinkHealth.Connected,
        DeviceLinkHealth.Ready,
        DeviceLinkHealth.Recording,
        DeviceLinkHealth.Complete -> Green
        DeviceLinkHealth.Starting,
        DeviceLinkHealth.WeakSignal,
        DeviceLinkHealth.WaitingReconnect,
        DeviceLinkHealth.Stopping,
        DeviceLinkHealth.Initializing -> Orange
        DeviceLinkHealth.Error -> Red
        DeviceLinkHealth.Disconnected -> Muted
    }

@Composable
private fun CaptureWorkbenchPanel(
    workflowState: String,
    workflowTone: CaptureStatusTone,
    mainActionText: String,
    mainActionEnabled: Boolean,
    mainActionActive: Boolean,
    mainActionHint: String?,
    connectedCount: Int,
    targetCount: Int,
    syncText: String,
    syncStatusTone: CaptureStatusTone,
    showSyncControls: Boolean,
    isSynced: Boolean,
    isSyncing: Boolean,
    syncProgress: Int,
    syncActionLocked: Boolean,
    batteryText: String,
    sampleRateText: String,
    selectedFileCount: Int,
    showFileControls: Boolean,
    fileActionsEnabled: Boolean,
    canExportFiles: Boolean,
    exportInProgress: Boolean,
    exportTaskProgress: ExportTaskProgress,
    eraseTaskProgress: EraseTaskProgress,
    canEraseFlash: Boolean,
    secondaryActionText: String?,
    secondaryActionEnabled: Boolean,
    onSecondaryAction: () -> Unit,
    onStopSync: () -> Unit,
    onStartSync: () -> Unit,
    onReadFiles: () -> Unit,
    onExportFiles: () -> Unit,
    onStopExportFiles: () -> Unit,
    onEraseFlash: () -> Unit,
    onMainAction: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppCardColor, RoundedCornerShape(10.dp))
            .border(1.dp, Border, RoundedCornerShape(10.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "工作台",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            CaptureStatusChip(text = workflowState, tone = workflowTone)
        }

        CaptureStatusSummary(
            deviceText = if (targetCount > 0) "$connectedCount/$targetCount" else "$connectedCount 台",
            syncText = syncText,
            batteryText = batteryText,
            sampleRateText = sampleRateText,
        )

        if (showSyncControls) {
            CaptureSyncControlPanel(
                syncStatusText = syncText,
                syncStatusTone = syncStatusTone,
                isSynced = isSynced,
                isSyncing = isSyncing,
                syncProgress = syncProgress,
                connectedCount = connectedCount,
                recordingLocked = syncActionLocked,
                onStopSync = onStopSync,
                onStartSync = onStartSync
            )
        }

        if (showFileControls) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CaptureInlineButton(
                    text = "读取文件",
                    enabled = fileActionsEnabled && !exportInProgress,
                    tone = CaptureButtonTone.Subtle,
                    modifier = Modifier.weight(1f),
                    onClick = onReadFiles
                )
                CaptureInlineButton(
                    text = if (exportInProgress) "停止导出" else if (selectedFileCount == 0) "导出全部" else "导出所选 ($selectedFileCount)",
                    enabled = if (exportInProgress) true else canExportFiles,
                    tone = if (exportInProgress) CaptureButtonTone.Danger else CaptureButtonTone.Success,
                    modifier = Modifier.weight(1f),
                    onClick = if (exportInProgress) onStopExportFiles else onExportFiles
                )
                CaptureInlineButton(
                    text = if (eraseTaskProgress.isErasing) "擦除中" else "擦除 Flash",
                    enabled = canEraseFlash && !eraseTaskProgress.isErasing,
                    tone = CaptureButtonTone.Danger,
                    modifier = Modifier.weight(1f),
                    onClick = onEraseFlash
                )
            }
        }

        if (exportTaskProgress.totalFiles > 0) {
            ExportProgressSummary(exportTaskProgress)
        }
        if (eraseTaskProgress.totalDevices > 0) {
            EraseProgressSummary(eraseTaskProgress)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CaptureMainActionButton(
                text = mainActionText,
                enabled = mainActionEnabled,
                active = mainActionActive,
                modifier = Modifier.weight(1f),
                onClick = onMainAction
            )
            if (secondaryActionText != null) {
                CaptureInlineButton(
                    text = secondaryActionText,
                    enabled = secondaryActionEnabled,
                    tone = CaptureButtonTone.Danger,
                    modifier = Modifier.width(86.dp).height(56.dp),
                    onClick = onSecondaryAction
                )
            }
        }
        if (mainActionHint != null) {
            Text(
                text = mainActionHint,
                style = MaterialTheme.typography.bodySmall,
                color = Muted
            )
        }
    }
}

@Composable
private fun CaptureStatusMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Muted,
            maxLines = 1
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CaptureStatusSummary(
    deviceText: String,
    syncText: String,
    batteryText: String,
    sampleRateText: String,
) {
    val items = listOf(
        "设备" to deviceText,
        "同步" to syncText,
        "电量" to batteryText,
        "采样" to sampleRateText,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppSurfaceColor, RoundedCornerShape(8.dp))
            .border(1.dp, Border.copy(alpha = 0.75f), RoundedCornerShape(8.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { index, item ->
            CaptureStatusMetric(
                label = item.first,
                value = item.second,
                modifier = Modifier.weight(1f),
            )
            if (index != items.lastIndex) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(28.dp)
                        .background(Border.copy(alpha = 0.7f))
                )
            }
        }
    }
}

@Composable
private fun CaptureSyncControlPanel(
    syncStatusText: String,
    syncStatusTone: CaptureStatusTone,
    isSynced: Boolean,
    isSyncing: Boolean,
    syncProgress: Int,
    connectedCount: Int,
    recordingLocked: Boolean,
    onStopSync: () -> Unit,
    onStartSync: () -> Unit
) {
    val boundedProgress = syncProgress.coerceIn(0, 100)
    val progress = boundedProgress / 100f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppSurfaceColor, RoundedCornerShape(10.dp))
            .border(1.dp, Border, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = "多设备同步",
                    style = MaterialTheme.typography.labelMedium,
                    color = Muted
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CaptureStatusChip(
                        text = if (isSyncing) "$syncStatusText $boundedProgress%" else syncStatusText,
                        tone = syncStatusTone
                    )
                    Text(
                        text = "${connectedCount} 台设备",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted
                    )
                }
            }
            if (isSynced || isSyncing) {
                CaptureInlineButton(
                    text = if (isSyncing) "停止同步" else "解除同步",
                    enabled = !recordingLocked,
                    tone = CaptureButtonTone.Danger,
                    onClick = onStopSync
                )
            } else {
                CaptureInlineButton(
                    text = "同步",
                    enabled = connectedCount >= 2 && !recordingLocked,
                    tone = CaptureButtonTone.Success,
                    onClick = onStartSync
                )
            }
        }
        if (isSyncing) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = Orange,
                trackColor = Border.copy(alpha = 0.45f)
            )
            Text(
                text = "正在同步设备：$boundedProgress%，请保持设备靠近并等待完成",
                style = MaterialTheme.typography.bodySmall,
                color = Muted
            )
        }
    }
}

@Composable
private fun CaptureAdvancedPanel(
    expanded: Boolean,
    connectedDevices: List<String>,
    recordingStates: Map<String, DotRecordingState>,
    recordingPhase: FlashRecordingPhase,
    linkStatuses: Map<String, DeviceLinkStatus>,
    rssiStatus: Map<String, Int>,
    flashInfo: Map<String, Pair<Int, Int>>,
    notificationReady: Set<String>,
    inRecordingMode: Boolean,
    syncOutputRate: Int,
    canEditSyncParams: Boolean,
    recordingLocked: Boolean,
    operationReady: Boolean,
    eraseTaskProgress: EraseTaskProgress,
    syncLog: List<String>,
    onToggleExpanded: () -> Unit,
    onSetRate: (Int) -> Unit,
    onEraseFlash: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppSurfaceColor, RoundedCornerShape(10.dp))
            .border(1.dp, Border, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpanded),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "高级设置",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = if (expanded) "收起" else "展开",
                style = MaterialTheme.typography.labelMedium,
                color = Muted
            )
        }

        if (!expanded) return@Column

        if (inRecordingMode && (flashInfo.isNotEmpty() || recordingStates.isNotEmpty() || notificationReady.isNotEmpty())) {
            Text(
                text = "离线存储 / SDK 状态",
                style = MaterialTheme.typography.labelMedium,
                color = Muted
            )
            OfflineDeviceStatusPanel(
                flashInfo = flashInfo,
                connectedDevices = connectedDevices,
                notificationReady = notificationReady,
                recordingStates = recordingStates,
                recordingPhase = recordingPhase,
                linkStatuses = linkStatuses,
                rssiStatus = rssiStatus
            )
        }

        CaptureSegmentedRow(label = "离线采样率") {
            CaptureSegmentedControl(
                value = syncOutputRate,
                options = listOf(
                    CaptureSegmentOption(value = 60, label = "60 Hz", enabled = canEditSyncParams),
                    CaptureSegmentOption(value = 120, label = "120 Hz", enabled = canEditSyncParams)
                ),
                onChange = onSetRate
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CaptureInlineButton(
                text = if (eraseTaskProgress.isErasing) "擦除中" else "擦除 Flash",
                enabled = operationReady && !recordingLocked && !eraseTaskProgress.isErasing,
                tone = CaptureButtonTone.Danger,
                modifier = Modifier.fillMaxWidth(),
                onClick = onEraseFlash
            )
        }
        if (eraseTaskProgress.totalDevices > 0) {
            EraseProgressSummary(eraseTaskProgress)
        }

        if (syncLog.isNotEmpty()) {
            CaptureCompactLog(logs = syncLog.takeLast(4))
        }
    }
}

@Composable
private fun DashboardMetricTile(
    label: String,
    value: String,
    supporting: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .heightIn(min = 118.dp)
            .background(AppCardColor, RoundedCornerShape(12.dp))
            .border(1.dp, Border, RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            modifier = Modifier
                .background(accent.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp)
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = supporting,
            style = MaterialTheme.typography.bodySmall,
            color = Muted
        )
    }
}

private fun formatStorageSize(bytes: Long): String {
    val kb = bytes / 1024.0
    return when {
        kb >= 1024.0 -> String.format(Locale.US, "%.1f MB", kb / 1024.0)
        kb >= 1.0 -> String.format(Locale.US, "%.1f KB", kb)
        else -> "0 KB"
    }
}

@Composable
private fun OfflineDeviceStatusPanel(
    flashInfo: Map<String, Pair<Int, Int>>,
    connectedDevices: List<String>,
    notificationReady: Set<String>,
    recordingStates: Map<String, DotRecordingState>,
    recordingPhase: FlashRecordingPhase,
    linkStatuses: Map<String, DeviceLinkStatus> = emptyMap(),
    rssiStatus: Map<String, Int> = emptyMap()
) {
    val connectedByNorm = remember(connectedDevices) {
        connectedDevices.associateBy { normalizeUiAddress(it) }
    }
    val flashByNorm = remember(flashInfo) {
        flashInfo.mapKeys { normalizeUiAddress(it.key) }
    }
    val notificationNorm = remember(notificationReady) {
        notificationReady.map { normalizeUiAddress(it) }.toSet()
    }
    val recordingByNorm = remember(recordingStates) {
        recordingStates.mapKeys { normalizeUiAddress(it.key) }
    }
    val deviceKeys = remember(connectedByNorm, flashByNorm, notificationNorm, recordingByNorm) {
        (connectedByNorm.keys + flashByNorm.keys + notificationNorm + recordingByNorm.keys).sorted()
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppSurfaceColor, RoundedCornerShape(8.dp))
            .border(1.dp, Border, RoundedCornerShape(8.dp))
    ) {
        deviceKeys.forEachIndexed { index, normAddr ->
            if (index > 0) {
                HorizontalDivider(color = Border.copy(alpha = 0.8f))
            }
            val addr = connectedByNorm[normAddr] ?: normAddr
            val flash = flashByNorm[normAddr]
            val (used, total) = flash ?: (0 to 0)
            val percent = if (total > 0) (used.toFloat() / total).coerceIn(0f, 1f) else 0f
            val sdkState = recordingByNorm[normAddr]
            val isConnected = normAddr in connectedByNorm
            val rssi = rssiStatus[normAddr]
            val linkStatus = linkStatuses[normAddr] ?: resolveDeviceLinkStatus(
                recordingPhase = recordingPhase,
                isConnected = isConnected,
                participatesInRecording = true,
                notificationReady = normAddr in notificationNorm,
                recordingState = sdkState,
                rssi = rssi,
                signalWeak = false
            )
            val stateText = linkStatus.label
            val connectionText = if (isConnected) "已连接" else "未连接"
            val stateColor = linkStatusColor(linkStatus.health)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(
                    modifier = Modifier.width(140.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = addr.replace(":", ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = buildString {
                            append(stateText)
                            append(" · ")
                            append(connectionText)
                            if (rssi != null) append(" · ${rssi}dB")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = stateColor
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (flash != null) {
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { percent },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp),
                            color = if (percent > 0.9f) ErrorRed else Green,
                            trackColor = Border.copy(alpha = 0.55f)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .background(Border.copy(alpha = 0.35f), RoundedCornerShape(999.dp))
                        )
                    }
                    Text(
                        text = if (flash != null) {
                            "${formatStorageSize(used.toLong())} / ${formatStorageSize(total.toLong())}"
                        } else {
                            "Flash 信息读取中"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted
                    )
                }
                Text(
                    text = if (flash != null) "${(percent * 100).toInt()}%" else "—",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

private fun offlineDeviceStateLabel(
    recordingPhase: FlashRecordingPhase,
    isConnected: Boolean,
    notificationReady: Boolean,
    sdkState: DotRecordingState?,
    rssi: Int?
): String =
    when {
        recordingPhase == FlashRecordingPhase.Starting -> "启动中"
        recordingPhase == FlashRecordingPhase.Stopping -> "停止中"
        recordingPhase == FlashRecordingPhase.Recording && !isConnected -> "等待回连"
        recordingPhase == FlashRecordingPhase.Recording && rssi != null && rssi <= SIGNAL_WEAK_ENTER_DBM -> "信号弱"
        sdkState == DotRecordingState.onRecording -> "录制中"
        recordingPhase == FlashRecordingPhase.Recording && isConnected && (sdkState == null || sdkState == DotRecordingState.unknown) -> "录制中"
        recordingPhase == FlashRecordingPhase.Recording && sdkState in setOf(DotRecordingState.idle, DotRecordingState.success) -> "未录制"
        sdkState in setOf(DotRecordingState.fail, DotRecordingState.invalidCmd) -> "异常"
        notificationReady -> "已就绪"
        else -> "初始化"
    }

@Composable
private fun OfflineFileSelectorSummary(
    deviceCount: Int,
    fileCount: Int,
    selectedCount: Int
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "导出文件",
            style = MaterialTheme.typography.labelMedium,
            color = Muted
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = when {
                    selectedCount > 0 -> "$selectedCount / $fileCount 已选"
                    deviceCount > 0 -> "$deviceCount 台设备 · $fileCount 个文件"
                    else -> "暂无文件"
                },
                style = MaterialTheme.typography.bodySmall,
                color = Muted
            )
        }
    }
}

@Composable
private fun CompactOfflineFilePicker(
    fileList: Map<String, List<DotRecordingFileInfo>>,
    selectedFileKeys: Set<String>,
    exportDone: Set<String>,
    exportProgress: Map<String, Int>,
    exportTaskProgress: ExportTaskProgress,
    recordingLocked: Boolean,
    operationReady: Boolean,
    onToggleDeviceSelection: (String) -> Unit,
    onToggleFileSelection: (String, Int) -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onExport: () -> Unit,
    onStopExport: () -> Unit
) {
    var expandedDevice by remember(fileList.keys.toSet()) { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppSurfaceColor, RoundedCornerShape(8.dp))
            .border(1.dp, Border, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CaptureToolbarGroup {
	                CaptureToolbarButton(
	                    text = "全选",
	                    enabled = operationReady && !recordingLocked,
                    tone = CaptureButtonTone.Subtle,
                    onClick = onSelectAll
                )
	                CaptureToolbarButton(
	                    text = "清空",
	                    enabled = operationReady && !recordingLocked,
                    tone = CaptureButtonTone.Subtle,
                    onClick = onClear
                )
            }
            val exporting = exportTaskProgress.hasPendingFiles
            val exportButtonText = if (selectedFileKeys.isEmpty()) "导出全部" else "导出所选 (${selectedFileKeys.size})"
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!exporting) {
	                CaptureToolbarButton(
	                    text = exportButtonText,
                        enabled = operationReady && fileList.isNotEmpty() && !recordingLocked,
                        tone = CaptureButtonTone.Success,
                        onClick = onExport
                    )
                }
                if (exporting) {
                    CaptureToolbarButton(
                        text = "停止",
                        tone = CaptureButtonTone.Danger,
                        onClick = onStopExport
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            fileList.forEach { (addr: String, files: List<DotRecordingFileInfo>) ->
                val deviceKeys = files.map { "$addr-${it.fileId}" }.toSet()
                val selectedInDev = deviceKeys.count { it in selectedFileKeys }
                val devState = when (selectedInDev) {
                    0 -> ToggleableState.Off
                    deviceKeys.size -> ToggleableState.On
                    else -> ToggleableState.Indeterminate
                }
                val devDone = exportDone.contains(addr)
                val devProg = exportProgress[addr] ?: 0
                val expanded = expandedDevice == addr
                val sortedFiles = files.sortedByDescending { it.fileName }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Bg, RoundedCornerShape(8.dp))
                        .border(
                            1.dp,
                            if (selectedInDev > 0) Green.copy(alpha = 0.45f) else Border.copy(alpha = 0.7f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                expandedDevice = if (expanded) null else addr
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CaptureSelectionBox(
                            state = devState,
                            onClick = { onToggleDeviceSelection(addr) }
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = addr.replace(":", ""),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (selectedInDev > 0) {
                                    "${files.size} 个文件 · 已选 $selectedInDev"
                                } else {
                                    "${files.size} 个文件"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = Muted
                            )
                        }
                        when {
                            devDone -> Text(
                                text = "已导出",
                                style = MaterialTheme.typography.bodySmall,
                                color = Green
                            )
                            devProg > 0 -> Text(
                                text = "导出中",
                                style = MaterialTheme.typography.bodySmall,
                                color = Orange
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = if (expanded) "收起" else "展开",
                            style = MaterialTheme.typography.labelSmall,
                            color = Muted
                        )
                    }

                    if (expanded) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            sortedFiles.forEach { file: DotRecordingFileInfo ->
                                val key = "$addr-${file.fileId}"
                                val checked = key in selectedFileKeys
                                val sizeText = formatStorageSize(file.dataSize.toLong())
                                val isActive = key in exportTaskProgress.activeFileKeys
                                val isCompleted = key in exportTaskProgress.completedFileKeys
                                val isFailed = key in exportTaskProgress.failedFileKeys
                                val fileExportRatio = exportFileProgressRatio(exportTaskProgress, key)

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            when {
                                                isFailed -> Red.copy(alpha = 0.10f)
                                                isActive -> Orange.copy(alpha = 0.10f)
                                                checked || isCompleted -> Green.copy(alpha = 0.10f)
                                                else -> Color.Transparent
                                            },
                                            RoundedCornerShape(6.dp)
                                        )
                                        .clickable { onToggleFileSelection(addr, file.fileId) }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CaptureSelectionBox(
                                            state = if (checked) ToggleableState.On else ToggleableState.Off
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = formatRecordingFileLabel(file.fileName),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = when {
                                                isFailed -> "失败"
                                                isCompleted -> "完成"
                                                isActive -> formatExportPercent(fileExportRatio)
                                                else -> sizeText
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = when {
                                                isFailed -> Red
                                                isCompleted -> Green
                                                isActive -> Orange
                                                else -> Muted
                                            },
                                            maxLines = 1
                                        )
                                    }
                                    if (isActive) {
                                        LinearProgressIndicator(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(3.dp)
                                                .clip(RoundedCornerShape(999.dp)),
                                            color = Orange,
                                            trackColor = Border.copy(alpha = 0.45f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun exportFileProgressRatio(progress: ExportTaskProgress, key: String): Float {
    if (key in progress.completedFileKeys || key in progress.failedFileKeys) return 1f
    val targetFrames = progress.targetFramesByFile[key]?.takeIf { it > 0 }
    if (targetFrames != null) {
        val frames = progress.framesByFile[key] ?: 0
        return (frames.toDouble() / targetFrames.toDouble())
            .toFloat()
            .coerceIn(0f, 0.995f)
    }
    val targetBytes = progress.targetBytesByFile[key]?.takeIf { it > 0L }
        ?: return 0f
    val writtenBytes = progress.writtenBytesByFile[key] ?: 0L
    return (writtenBytes.coerceAtMost(targetBytes).toDouble() / targetBytes.toDouble())
        .toFloat()
        .coerceIn(0f, 0.995f)
}

private fun exportOverallProgressRatio(progress: ExportTaskProgress): Float {
    if (progress.totalFiles <= 0) return 0f
    if (progress.targetFramesByFile.isNotEmpty()) {
        val totalFrames = progress.targetFramesByFile.values.sum().coerceAtLeast(1)
        val doneFrames = progress.targetFramesByFile.entries.sumOf { (key, targetFrames) ->
            when {
                key in progress.completedFileKeys || key in progress.failedFileKeys -> targetFrames
                else -> (targetFrames * exportFileProgressRatio(progress, key)).toInt()
            }
        }
        return (doneFrames.toDouble() / totalFrames.toDouble()).toFloat().coerceIn(0f, 1f)
    }
    if (progress.targetBytesByFile.isEmpty()) {
        val finishedCount = progress.completedFileKeys.size + progress.failedFileKeys.size
        return (finishedCount.toFloat() / progress.totalFiles.toFloat()).coerceIn(0f, 1f)
    }
    val totalBytes = progress.targetBytesByFile.values.sum().coerceAtLeast(1L)
    val doneBytes = progress.targetBytesByFile.entries.sumOf { (key, targetBytes) ->
        when {
            key in progress.completedFileKeys || key in progress.failedFileKeys -> targetBytes
            else -> (targetBytes.toDouble() * exportFileProgressRatio(progress, key).toDouble()).toLong()
        }
    }
    return (doneBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
}

private fun formatExportPercent(ratio: Float): String {
    val percent = (ratio * 100f).coerceIn(0f, 100f)
    return if (percent > 0f && percent < 99.95f) {
        String.format(Locale.US, "%.1f%%", percent)
    } else {
        "${percent.toInt()}%"
    }
}

@Composable
private fun ExportProgressSummary(progress: ExportTaskProgress) {
    val failedCount = progress.failedFileKeys.size
    val finishedCount = progress.completedFileKeys.size + failedCount
    val progressRatio = exportOverallProgressRatio(progress)
    val percentText = formatExportPercent(progressRatio)
    val statusText = when {
        progress.hasPendingFiles -> "$percentText · 已完成 $finishedCount / ${progress.totalFiles} 个文件"
        failedCount > 0 -> "导出完成，失败 $failedCount 个"
        else -> "$percentText · ${progress.completedFileKeys.size} / ${progress.totalFiles} 个文件"
    }
    val statusColor = if (failedCount > 0) Red else if (progress.hasPendingFiles) Orange else Green

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Bg, RoundedCornerShape(8.dp))
            .border(1.dp, Border.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "导出进度",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall,
                color = statusColor
            )
        }
        LinearProgressIndicator(
            progress = { progressRatio },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(999.dp)),
            color = statusColor,
            trackColor = Border.copy(alpha = 0.45f)
        )
    }
}

@Composable
private fun EraseProgressSummary(progress: EraseTaskProgress) {
    val failedCount = progress.failedDevices.size
    val finishedCount = progress.completedDevices.size + failedCount
    val percent = if (progress.totalDevices > 0) {
        ((finishedCount * 100) / progress.totalDevices).coerceIn(0, 100)
    } else {
        0
    }
    val statusText = when {
        progress.isErasing -> "$finishedCount / ${progress.totalDevices} 台设备"
        failedCount > 0 -> "擦除完成，失败 $failedCount 台"
        else -> "${progress.completedDevices.size} / ${progress.totalDevices} 台设备"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Bg, RoundedCornerShape(8.dp))
            .border(1.dp, Border.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "擦除进度",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "$percent% · $statusText",
                style = MaterialTheme.typography.labelSmall,
                color = if (failedCount > 0) Red else if (progress.isErasing) Orange else Green
            )
        }
        LinearProgressIndicator(
            progress = { percent / 100f },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(999.dp)),
            color = if (failedCount > 0) Red else if (progress.isErasing) Orange else Green,
            trackColor = Border.copy(alpha = 0.45f)
        )
    }
}

private fun formatRecordingFileLabel(fileName: String): String {
    val normalized = fileName.substringBefore('.')
    return if (normalized.length == 15 && normalized[8] == '_') {
        val date = normalized.substring(0, 8)
        val time = normalized.substring(9, 15)
        "${date.substring(0, 4)}-${date.substring(4, 6)}-${date.substring(6, 8)} " +
            "${time.substring(0, 2)}:${time.substring(2, 4)}:${time.substring(4, 6)}"
    } else {
        fileName
    }
}

private fun summarizeBattery(
    batteryStatus: Map<String, DotBatteryStatus>,
    connectedDevices: List<String>
): String {
    val values = connectedDevices.mapNotNull { batteryStatus[normalizeUiAddress(it)]?.percentage }
    return when {
        values.isEmpty() -> "—"
        values.size == 1 -> "${values.first()}%"
        else -> "${values.minOrNull()}-${values.maxOrNull()}%"
    }
}

private fun normalizeUiAddress(addr: String): String =
    addr.replace(":", "").replace("-", "").uppercase()

@Composable
private fun CaptureMainActionButton(
    text: String,
    enabled: Boolean,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val containerColor = if (active) ErrorRed else Green
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = Color.White,
            disabledContainerColor = AppSurfaceColor,
            disabledContentColor = Muted
        ),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CaptureActionGlyph(
                active = active,
                tint = if (enabled) Color.White else Muted
            )
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun CaptureActionGlyph(
    active: Boolean,
    tint: Color
) {
    Box(
        modifier = Modifier.size(18.dp),
        contentAlignment = Alignment.Center
    ) {
        if (active) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(tint, RoundedCornerShape(2.dp))
            )
        } else {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .border(1.5.dp, tint, RoundedCornerShape(999.dp)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(tint, RoundedCornerShape(999.dp))
                )
            }
        }
    }
}

@Composable
private fun CaptureSelectionBox(
    state: ToggleableState,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val background = when (state) {
        ToggleableState.On -> Green
        ToggleableState.Indeterminate -> Green.copy(alpha = 0.14f)
        ToggleableState.Off -> Color.Transparent
    }
    val borderColor = when (state) {
        ToggleableState.Off -> Border
        else -> Green
    }
    Box(
        modifier = modifier
            .size(20.dp)
            .background(background, RoundedCornerShape(5.dp))
            .border(1.dp, borderColor, RoundedCornerShape(5.dp))
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick)
                else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        when (state) {
            ToggleableState.On -> Text(
                text = "✓",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = Bg
            )
            ToggleableState.Indeterminate -> Box(
                modifier = Modifier
                    .width(8.dp)
                    .height(2.dp)
                    .background(Green, RoundedCornerShape(999.dp))
            )
            ToggleableState.Off -> Unit
        }
    }
}

@Composable
private fun CaptureToolbarGroup(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier
            .background(AppSurfaceColor, RoundedCornerShape(10.dp))
            .border(1.dp, Border, RoundedCornerShape(10.dp))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@Composable
private fun CaptureToolbarButton(
    text: String,
    onClick: () -> Unit,
    tone: CaptureButtonTone,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val shape = RoundedCornerShape(8.dp)
    val colors = when (tone) {
        CaptureButtonTone.Subtle -> ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = Muted
        )
        CaptureButtonTone.Success -> ButtonDefaults.buttonColors(
            containerColor = Green,
            contentColor = Color.White,
            disabledContainerColor = Green.copy(alpha = 0.42f),
            disabledContentColor = Color.White.copy(alpha = 0.72f)
        )
        CaptureButtonTone.Danger -> ButtonDefaults.buttonColors(
            containerColor = Red,
            contentColor = Color.White,
            disabledContainerColor = Red.copy(alpha = 0.42f),
            disabledContentColor = Color.White.copy(alpha = 0.72f)
        )
    }
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        modifier = modifier.height(36.dp),
        colors = colors,
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp,
            disabledElevation = 0.dp
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun CaptureNoticeBar(
    text: String,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Orange.copy(alpha = 0.16f), RoundedCornerShape(8.dp))
            .border(1.dp, Orange.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(12.dp))
        TextButton(onClick = onDismiss) { Text("知道了") }
    }
}

@Composable
private fun CaptureSection(
    title: String,
    action: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (action != null) {
                action()
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun RecordingExportDecisionDialog(
    fileCount: Int,
    isPreparing: Boolean,
    onExport: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "录制已完成",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = if (isPreparing) {
                        "正在确认本次录制文件，请保持设备连接。"
                    } else {
                        "已找到本次录制的 ${fileCount} 个文件。是否立即导出？暂不导出后，可在文件列表中选择其他数据。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted
                )
                if (isPreparing) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = Green,
                        trackColor = Border.copy(alpha = 0.45f)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onExport,
                enabled = !isPreparing && fileCount > 0,
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Green,
                    contentColor = Bg
                )
            ) {
                Text(text = if (isPreparing) "准备中" else "导出本次")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "暂不导出", color = Muted)
            }
        },
        shape = RoundedCornerShape(8.dp),
        containerColor = AppCardColor
    )
}

@Composable
private fun CaptureStatusChip(
    text: String,
    tone: CaptureStatusTone
) {
    val dotColor = when (tone) {
        CaptureStatusTone.Signal -> Green
        CaptureStatusTone.Warning -> Orange
        CaptureStatusTone.Danger -> Red
        CaptureStatusTone.Muted -> Muted
    }
    Row(
        modifier = Modifier
            .background(AppSurfaceColor, RoundedCornerShape(999.dp))
            .border(1.dp, Border, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(dotColor, RoundedCornerShape(999.dp))
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun CaptureInlineButton(
    text: String,
    onClick: () -> Unit,
    tone: CaptureButtonTone,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val shape = RoundedCornerShape(8.dp)
    when (tone) {
        CaptureButtonTone.Subtle -> {
            androidx.compose.material3.OutlinedButton(
                onClick = onClick,
                enabled = enabled,
                shape = shape,
                modifier = modifier.heightIn(min = 40.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = AppSurfaceColor,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    disabledContainerColor = AppSurfaceColor.copy(alpha = 0.42f),
                    disabledContentColor = Muted
                ),
                border = ButtonDefaults.outlinedButtonBorder.copy(
                    brush = androidx.compose.ui.graphics.SolidColor(Border)
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        CaptureButtonTone.Success,
        CaptureButtonTone.Danger -> {
            val background = if (tone == CaptureButtonTone.Success) Green else Red
            Button(
                onClick = onClick,
                enabled = enabled,
                shape = shape,
                modifier = modifier.heightIn(min = 40.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = background,
                    contentColor = Color.White,
                    disabledContainerColor = background.copy(alpha = 0.42f),
                    disabledContentColor = Color.White.copy(alpha = 0.72f)
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun CaptureEmptyRow(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(Muted.copy(alpha = 0.7f), RoundedCornerShape(999.dp))
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = Muted
        )
    }
}

@Composable
private fun CaptureDeviceList(
    devices: List<ScannedDevice>,
    selectedAddresses: Set<String>,
    connectedAddresses: Set<String>,
    batteryStatus: Map<String, DotBatteryStatus>,
    deviceSyncStates: Map<String, Boolean>,
    firmwareStatus: Map<String, DotFirmwareStatus>,
    recordingStates: Map<String, DotRecordingState> = emptyMap(),
    recordingPhase: FlashRecordingPhase = FlashRecordingPhase.Idle,
    recordingTargets: Set<String> = emptySet(),
    linkStatuses: Map<String, DeviceLinkStatus> = emptyMap(),
    rssiStatus: Map<String, Int> = emptyMap(),
    enabled: Boolean,
    compact: Boolean = false,
    isSyncing: Boolean = false,
    powerOffEnabled: Boolean,
    onPowerOff: (String) -> Unit,
    onToggle: (Int) -> Unit
) {
    val connectedNorm = remember(connectedAddresses) {
        connectedAddresses.map { normalizeUiAddress(it) }.toSet()
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppSurfaceColor, RoundedCornerShape(8.dp))
            .border(1.dp, Border, RoundedCornerShape(8.dp))
    ) {
        devices.forEachIndexed { index, device ->
            if (index > 0) {
                HorizontalDivider(color = Border.copy(alpha = 0.8f))
            }
            CaptureDeviceRow(
                device = device,
                selected = normalizeUiAddress(device.address) in selectedAddresses,
                connected = normalizeUiAddress(device.address) in connectedNorm,
                syncingTarget = isSyncing && normalizeUiAddress(device.address) in selectedAddresses,
                participatesInRecording = normalizeUiAddress(device.address) in recordingTargets,
                connectedDeviceCount = connectedAddresses.size,
                battery = batteryStatus[normalizeUiAddress(device.address)],
                synced = deviceSyncStates[normalizeUiAddress(device.address)],
                firmware = firmwareStatus[normalizeUiAddress(device.address)],
                recordingState = recordingStates[normalizeUiAddress(device.address)],
                recordingPhase = recordingPhase,
                linkStatus = linkStatuses[normalizeUiAddress(device.address)],
                liveRssi = rssiStatus[normalizeUiAddress(device.address)],
                enabled = enabled,
                compact = compact,
                powerOffEnabled = powerOffEnabled,
                onPowerOff = { onPowerOff(device.address) },
                onClick = { onToggle(index) }
            )
        }
    }
}

@Composable
private fun CaptureDeviceRow(
    device: ScannedDevice,
    selected: Boolean,
    connected: Boolean,
    syncingTarget: Boolean,
    participatesInRecording: Boolean,
    connectedDeviceCount: Int,
    battery: DotBatteryStatus?,
    synced: Boolean?,
    firmware: DotFirmwareStatus?,
    recordingState: DotRecordingState?,
    recordingPhase: FlashRecordingPhase,
    linkStatus: DeviceLinkStatus?,
    liveRssi: Int?,
    enabled: Boolean,
    compact: Boolean,
    powerOffEnabled: Boolean,
    onPowerOff: () -> Unit,
    onClick: () -> Unit
) {
    val status = linkStatus ?: resolveDeviceLinkStatus(
        recordingPhase = recordingPhase,
        isConnected = connected,
        participatesInRecording = participatesInRecording,
        notificationReady = false,
        recordingState = recordingState,
        rssi = liveRssi,
        signalWeak = false
    )
    val statusText = status.label
    val statusColor = linkStatusColor(status.health)
    val leadingChecked = connected || syncingTarget || selected
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .background(
                    if (leadingChecked) Green else Color.Transparent,
                    RoundedCornerShape(4.dp)
                )
                .border(1.dp, if (leadingChecked) Green else Border, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (leadingChecked) {
                Text(
                    text = "✓",
                    style = MaterialTheme.typography.labelSmall,
                    color = Bg
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = device.realMac,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                device.sideLabel?.let { side ->
                    Text(
                        text = side,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (side == "左脚") Orange else Green,
                        modifier = Modifier
                            .background(
                                if (side == "左脚") Orange.copy(alpha = 0.12f) else Green.copy(alpha = 0.12f),
                                RoundedCornerShape(999.dp)
                            )
                            .border(
                                1.dp,
                                if (side == "左脚") Orange.copy(alpha = 0.32f) else Green.copy(alpha = 0.32f),
                                RoundedCornerShape(999.dp)
                            )
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
            }
            Text(
                text = buildDeviceStatusLine(
                    rssi = liveRssi,
                    connected = connected,
                    connectedDeviceCount = connectedDeviceCount,
                    battery = battery,
                    synced = synced,
                    firmware = firmware,
                    compact = compact,
                    statusText = statusText
                ),
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (connected) {
            CaptureDeviceActionChip(
                text = "关机",
                enabled = powerOffEnabled,
                color = Red,
                onClick = onPowerOff
            )
        }
        CaptureDeviceMiniChip(text = statusText, color = statusColor)
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    if (connected) statusColor else Muted.copy(alpha = 0.45f),
                    RoundedCornerShape(999.dp)
                )
        )
    }
}

private fun buildDeviceStatusLine(
    rssi: Int?,
    connected: Boolean,
    connectedDeviceCount: Int,
    battery: DotBatteryStatus?,
    synced: Boolean?,
    firmware: DotFirmwareStatus?,
    compact: Boolean = false,
    statusText: String = if (connected) "已连" else "扫描"
): String {
    val parts = mutableListOf<String>()
    if (!connected) {
        parts += "未连接"
        if (!compact && rssi != null) parts += "RSSI ${rssi} dB"
        return parts.joinToString(" · ")
    }
    parts += statusText
    parts += battery?.let {
        val prefix = if (it.status == DotDevice.BATT_STATE_CHARGING) "充电" else "电量"
        "$prefix ${it.percentage}%"
    } ?: "电量 —"
    if (rssi != null) parts += "${rssi}dB"
    parts += when {
        connectedDeviceCount < 2 -> "单设备"
        synced == true -> "已同步"
        synced == false -> "未同步"
        else -> "同步 —"
    }
    if (!compact) {
        parts += firmware?.let {
            "固件 ${it.version}${if (it.compatible) "" else " 不兼容"}"
        } ?: "固件 —"
    }
    return parts.joinToString(" · ")
}

private fun connectedDeviceRowStatus(
    recordingPhase: FlashRecordingPhase,
    connected: Boolean,
    participatesInRecording: Boolean,
    recordingState: DotRecordingState?,
    rssi: Int?
): String =
    when {
        !participatesInRecording -> if (connected) "已连接" else "未连接"
        !connected && participatesInRecording -> "等待回连"
        !connected -> "未连接"
        recordingPhase == FlashRecordingPhase.Starting -> "启动中"
        recordingPhase == FlashRecordingPhase.Stopping -> "停止中"
        recordingPhase == FlashRecordingPhase.Recording && rssi != null && rssi <= SIGNAL_WEAK_ENTER_DBM -> "信号弱"
        recordingState == DotRecordingState.onRecording -> "录制中"
        recordingPhase == FlashRecordingPhase.Recording && (recordingState == null || recordingState == DotRecordingState.unknown) -> "录制中"
        recordingState in setOf(DotRecordingState.fail, DotRecordingState.invalidCmd) -> "异常"
        else -> "已连接"
    }

private fun deviceStatusColor(statusText: String): Color =
    when (statusText) {
        "录制中", "已连接" -> Green
        "等待回连", "启动中", "停止中", "信号弱" -> Orange
        "异常" -> Red
        else -> Muted
    }

@Composable
private fun CaptureDeviceMiniChip(
    text: String,
    color: Color
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(999.dp))
            .border(1.dp, color.copy(alpha = 0.32f), RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Composable
private fun CaptureDeviceActionChip(
    text: String,
    enabled: Boolean,
    color: Color,
    onClick: () -> Unit
) {
    val chipColor = if (enabled) color else Muted
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = chipColor,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .background(chipColor.copy(alpha = if (enabled) 0.12f else 0.06f), RoundedCornerShape(999.dp))
            .border(1.dp, chipColor.copy(alpha = if (enabled) 0.35f else 0.16f), RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Composable
private fun CaptureCompactLog(logs: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppSurfaceColor, RoundedCornerShape(8.dp))
            .border(1.dp, Border, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        logs.forEach { log ->
            Text(
                text = log,
                style = MaterialTheme.typography.bodySmall,
                color = Muted
            )
        }
    }
}

@Composable
private fun CaptureSegmentedRow(
    label: String,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(0.dp), content = content)
    }
}

@Composable
private fun <T> CaptureSegmentedControl(
    value: T,
    options: List<CaptureSegmentOption<T>>,
    onChange: (T) -> Unit
) {
    Row(
        modifier = Modifier
            .background(AppSurfaceColor, RoundedCornerShape(8.dp))
            .border(1.dp, Border, RoundedCornerShape(8.dp))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        options.forEach { option ->
            val active = option.value == value
            Button(
                onClick = { onChange(option.value) },
                enabled = option.enabled,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.height(34.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (active) Green else AppSurfaceColor,
                    contentColor = if (active) Bg else MaterialTheme.colorScheme.onSurface,
                    disabledContainerColor = if (active) Green.copy(alpha = 0.42f) else AppSurfaceColor.copy(alpha = 0.72f),
                    disabledContentColor = Muted
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp, disabledElevation = 0.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = option.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun CaptureQuickPanel(
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppSurfaceColor, RoundedCornerShape(8.dp))
            .border(1.dp, Border, RoundedCornerShape(8.dp))
            .padding(12.dp),
        content = content
    )
}

@Composable
private fun SyncSubpanel(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppSurfaceColor, RoundedCornerShape(12.dp))
            .border(1.dp, Border, RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Muted
            )
            Spacer(modifier = Modifier.height(10.dp))
        }
        content()
    }
}
