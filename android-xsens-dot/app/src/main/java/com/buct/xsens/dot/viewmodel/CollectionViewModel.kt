package com.buct.xsens.dot.viewmodel

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.buct.xsens.dot.data.CaptureAthleteOption
import com.buct.xsens.dot.data.CaptureParticipantBinding
import com.buct.xsens.dot.data.CsvRecorder
import com.buct.xsens.dot.data.DeviceRoleConfig
import com.buct.xsens.dot.data.DeviceRolePreferences
import com.buct.xsens.dot.data.LongJumpDeviceRoles
import com.buct.xsens.dot.data.ScannedDevice
import com.buct.xsens.dot.data.SensorData
import com.buct.xsens.dot.engine.CollectionEngine
import com.buct.xsens.dot.engine.FileInfoReadPhase
import com.buct.xsens.dot.engine.FlashRecordingPhase
import com.buct.xsens.dot.engine.RecordingEngine
import com.buct.xsens.dot.engine.areFileInfoReadTargetsTerminal
import com.buct.xsens.dot.engine.canImplicitlyExportFileInfo
import com.buct.xsens.dot.engine.participantHasActiveRecordingOperation
import com.buct.xsens.dot.engine.shouldSetupRecordingManagerAfterConnection
import com.buct.xsens.dot.service.BleStreamingService
import com.xsens.dot.android.sdk.models.DotDevice
import com.xsens.dot.android.sdk.models.DotRecordingState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class CollectionViewModel(application: Application) : AndroidViewModel(application) {

    private val deviceRolePreferences = DeviceRolePreferences(application)
    private val _deviceRoleConfig = MutableStateFlow(deviceRolePreferences.load())
    val deviceRoleConfig: StateFlow<DeviceRoleConfig> = _deviceRoleConfig.asStateFlow()
    private val _availableAthletes = MutableStateFlow<List<CaptureAthleteOption>>(emptyList())
    val availableAthletes: StateFlow<List<CaptureAthleteOption>> = _availableAthletes.asStateFlow()

    private val engine = CollectionEngine(application)
    private val recEngine = RecordingEngine(application)

    val scannedDevices: StateFlow<List<ScannedDevice>> = engine.scannedDevices
    val isScanning: StateFlow<Boolean> = engine.isScanning
    val connectedDevices: StateFlow<List<String>> = engine.connectedDevices
    val manuallyDisconnectedAddresses: StateFlow<Set<String>> =
        engine.manuallyDisconnectedAddresses
    val connectionTargetAddresses: StateFlow<Set<String>> =
        engine.connectionTargetAddresses
    val state: StateFlow<CollectionEngine.CollectionState> = engine.state
    val recvCount: StateFlow<Long> = engine.recvCount
    val sensorData: StateFlow<Map<String, SensorData>> = engine.sensorData
    val waveData: StateFlow<Map<String, com.buct.xsens.dot.data.WaveSnapshot>> = engine.waveData
    val syncLog: StateFlow<List<String>> = engine.syncLog
    val isSynced: StateFlow<Boolean> = engine.isSynced
    val isSyncing: StateFlow<Boolean> = engine.isSyncing
    val syncProgress: StateFlow<Int> = engine.syncProgress
    val syncTargetAddresses: StateFlow<Set<String>> = engine.syncTargetAddresses
    val needsSync: StateFlow<Boolean> = engine.needsSync
    val batteryStatus = engine.batteryStatus
    val deviceRssi = engine.deviceRssi
    val deviceRssiUpdatedAt = engine.deviceRssiUpdatedAt
    val deviceSyncStates = engine.deviceSyncStates
    val firmwareStatus = engine.firmwareStatus
    val initProgress: StateFlow<Pair<Int, Int>> = engine.initProgress
    // 滤波器模式：0=General（默认），1=Dynamic（与官方 setSensorProfile 对齐）
    val filterProfile: StateFlow<Int> = engine.filterProfile

    private val _selectedForConnect = MutableStateFlow<Set<Int>>(emptySet())
    val selectedForConnect: StateFlow<Set<Int>> = _selectedForConnect.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingPath = MutableStateFlow<String?>(null)
    val recordingPath: StateFlow<String?> = _recordingPath.asStateFlow()

    private val _selectedWaveSensor = MutableStateFlow(0)
    val selectedWaveSensor: StateFlow<Int> = _selectedWaveSensor.asStateFlow()
    fun setSelectedWaveSensor(index: Int) { _selectedWaveSensor.value = index }

    private val _selectedPayload = MutableStateFlow(0)
    val selectedPayload: StateFlow<Int> = _selectedPayload.asStateFlow()
    fun setSelectedPayload(index: Int) { _selectedPayload.value = index }

    // ── 离线采集状态（透传 RecordingEngine StateFlow）──
    val recFlashInfo       = recEngine.flashInfo
    val recNotifReady      = recEngine.notificationReady
    val recRecordingActive = recEngine.recordingActive
    val recRecordingPhase  = recEngine.recordingPhase
    val recDeviceRecordingPhases = recEngine.deviceRecordingPhases
    val recDelayedStopConfirmations = recEngine.delayedStopConfirmations
    val recRecordingStates = recEngine.recordingStates
    val recFileList        = recEngine.fileList
    val recFileInfoReadStatuses = recEngine.fileInfoReadStatuses
    val recFileInfoReadActiveTargets = recEngine.fileInfoReadActiveTargets
    val recExportProgress  = recEngine.exportProgress
    val recExportDone      = recEngine.exportDone
    val recExportTaskProgress = recEngine.exportTaskProgress
    val recRecordingExportDecisions = recEngine.recordingExportDecisions
    val recEraseTaskProgress = recEngine.eraseTaskProgress
    val recLog             = recEngine.recordingLog
    val recSelectedExportIds = recEngine.selectedExportIds
    val recAllExportFields   = RecordingEngine.SUPPORTED_EXPORT_FIELDS

    fun setExportIds(ids: Set<Byte>) = recEngine.setSelectedExportIds(ids)

    // ── 离线录制模式设置（独立于实时采集设置）──
    private val _recOutputRate    = MutableStateFlow(120)   // 默认 120Hz
    private val _recFilterProfile = MutableStateFlow(1)     // 默认 Dynamic
    val recOutputRate:    StateFlow<Int> = _recOutputRate.asStateFlow()
    val recFilterProfile: StateFlow<Int> = _recFilterProfile.asStateFlow()
    fun setRecOutputRate(rate: Int) {
        if (engine.getDevices().isNotEmpty() && !engine.canWriteDeviceParameters()) {
            Toast.makeText(getApplication(), "请先停止采集或解除同步，再修改采样率", Toast.LENGTH_SHORT).show()
            return
        }
        _recOutputRate.value = rate
        engine.syncOutputRate = rate
        // 立即写入所有已连接设备（连接后且未同步时才允许修改，UI 已做限制）
        engine.setAllDevicesOutputRate(rate)
    }
    fun setRecFilterProfile(mode: Int) {
        if (engine.getDevices().isNotEmpty() && !engine.canWriteDeviceParameters()) {
            Toast.makeText(getApplication(), "请先停止采集或解除同步，再修改滤波档", Toast.LENGTH_SHORT).show()
            return
        }
        _recFilterProfile.value = mode
        // 立即写入所有已连接设备（通过 real-time filterProfile 通道统一写入）
        engine.setFilterProfileMode(mode)
    }

    // ── 文件选择（key = "$normalizedAddr-$fileId"）──
    private val _selectedFileKeys = MutableStateFlow<Set<String>>(emptySet())
    val selectedFileKeys: StateFlow<Set<String>> = _selectedFileKeys.asStateFlow()
    private val _visibleHistoryParticipantSlots = MutableStateFlow<Set<String>>(emptySet())
    val visibleHistoryParticipantSlots: StateFlow<Set<String>> =
        _visibleHistoryParticipantSlots.asStateFlow()
    private val _historyLoadingParticipantSlots = MutableStateFlow<Set<String>>(emptySet())
    val historyLoadingParticipantSlots: StateFlow<Set<String>> =
        _historyLoadingParticipantSlots.asStateFlow()
    private val _historyQueuedParticipantSlots = MutableStateFlow<Set<String>>(emptySet())
    val historyQueuedParticipantSlots: StateFlow<Set<String>> =
        _historyQueuedParticipantSlots.asStateFlow()
    private val _historyRequestErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val historyRequestErrors: StateFlow<Map<String, String>> =
        _historyRequestErrors.asStateFlow()
    private data class ParticipantHistoryRequest(
        val slotId: String,
        val targets: Set<String>,
        val configFingerprint: String,
        val generation: Int,
    )
    private val historyRequestChannel =
        Channel<ParticipantHistoryRequest>(Channel.UNLIMITED)
    private val historyRequestGenerations = mutableMapOf<String, Int>()

    private fun fileDeviceReady(address: String): Boolean {
        val norm = LongJumpDeviceRoles.normalizeDeviceId(address)
        val connected = engine.connectedDevices.value
            .map(LongJumpDeviceRoles::normalizeDeviceId)
            .toSet()
        return isHistoryFileSelectionAllowed(
            address = norm,
            connectedAddresses = connected,
            readStatus = recEngine.fileInfoReadStatuses.value[norm],
            recordingPhase = recEngine.deviceRecordingPhases.value[norm],
        )
    }

    private fun eligibleFileKeys(): Set<String> =
        recEngine.fileList.value
            .filterKeys(::fileDeviceReady)
            .flatMap { (addr, files) -> files.map { file -> "$addr-${file.fileId}" } }
            .toSet()

    fun toggleFileSelection(addr: String, fileId: Int) {
        if (!fileDeviceReady(addr)) return
        val key = "$addr-$fileId"
        _selectedFileKeys.update { if (key in it) it - key else it + key }
    }

    /** 切换某台设备下所有文件的选中状态（全选/全不选） */
    fun toggleDeviceSelection(addr: String) {
        if (!fileDeviceReady(addr)) return
        val deviceKeys = recEngine.fileList.value[addr]
            ?.map { f -> "$addr-${f.fileId}" }?.toSet() ?: return
        _selectedFileKeys.update { current ->
            if (deviceKeys.all { it in current }) current - deviceKeys   // 已全选 → 全取消
            else current + deviceKeys                                     // 否则 → 全选
        }
    }

    fun selectAllFiles() {
        _selectedFileKeys.value = eligibleFileKeys()
    }

    fun clearFileSelection() { _selectedFileKeys.value = emptySet() }

    private fun participantFileKeys(slotId: String): Set<String> {
        val participant = _deviceRoleConfig.value.participants
            .firstOrNull { it.slotId == slotId }
            ?: return emptySet()
        val targets = participantTargets(participant)
        return recEngine.fileList.value
            .filterKeys { it in targets && fileDeviceReady(it) }
            .flatMap { (addr, files) -> files.map { file -> "$addr-${file.fileId}" } }
            .toSet()
    }

    fun selectParticipantFiles(slotId: String) {
        val keys = participantFileKeys(slotId)
        _selectedFileKeys.update { current -> current + keys }
    }

    fun clearParticipantFileSelection(slotId: String) {
        val participant = _deviceRoleConfig.value.participants
            .firstOrNull { it.slotId == slotId }
            ?: return
        val targets = participantTargets(participant)
        _selectedFileKeys.update { current ->
            current.filterNot { key ->
                targets.any { target -> key.startsWith("$target-") }
            }.toSet()
        }
    }

    fun exportParticipantFiles(slotId: String) {
        if (
            _historyQueuedParticipantSlots.value.isNotEmpty() ||
            _historyLoadingParticipantSlots.value.isNotEmpty() ||
            recEngine.fileInfoReadActiveTargets.value.isNotEmpty()
        ) {
            Toast.makeText(
                getApplication(),
                "请等待历史文件读取队列完成，再开始导出",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        val participant = _deviceRoleConfig.value.participants
            .firstOrNull { it.slotId == slotId }
            ?: return
        val participantTargets = participantTargets(participant)
        val eligible = participantFileKeys(slotId)
        val selected = _selectedFileKeys.value.intersect(eligible)
        val statuses = recEngine.fileInfoReadStatuses.value
        val incomplete =
            _historyRequestErrors.value[slotId] != null ||
                !canImplicitlyExportFileInfo(statuses, participantTargets)
        if (incomplete && selected.isEmpty()) {
            Toast.makeText(
                getApplication(),
                "文件列表不完整，请先明确选择需要导出的文件",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        val targets = selected.ifEmpty { eligible }
        if (targets.isEmpty()) {
            Toast.makeText(
                getApplication(),
                "该运动员没有可导出的文件",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        recEngine.exportSelected(targets)
    }

    fun exportFiles() {
        val eligible = eligibleFileKeys()
        val selected = _selectedFileKeys.value.intersect(eligible)
        val targets = selected.ifEmpty { eligible }
        if (targets.isEmpty()) {
            Toast.makeText(
                getApplication(),
                "没有可导出的已连接设备文件",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        _selectedFileKeys.value = selected
        recEngine.exportSelected(targets)
    }

    fun retryFailedExports() = recEngine.retryFailedExports()

    fun exportRecordingDecision(decisionId: String) {
        clearFileSelection()
        recEngine.exportRecordingDecision(decisionId)
    }

    fun dismissRecordingExportDecision(decisionId: String) {
        recEngine.dismissRecordingExportDecision(decisionId)
    }

    private val _inRecordingMode = MutableStateFlow(false)
    val inRecordingMode: StateFlow<Boolean> = _inRecordingMode.asStateFlow()

    private val _headingActiveButton = MutableStateFlow("reset")
    val headingActiveButton: StateFlow<String> = _headingActiveButton.asStateFlow()

    private val _scanMessage = MutableStateFlow<String?>(null)
    val scanMessage: StateFlow<String?> = _scanMessage.asStateFlow()
    private val _isManualScanning = MutableStateFlow(false)
    val isManualScanning: StateFlow<Boolean> = _isManualScanning.asStateFlow()

    private val _capturePreparePending = MutableStateFlow(false)
    private val _captureWorkflowPreparing = MutableStateFlow(false)
    val captureWorkflowPreparing: StateFlow<Boolean> = _captureWorkflowPreparing.asStateFlow()
    private val _participantPreparingSlots = MutableStateFlow<Set<String>>(emptySet())
    val participantPreparingSlots: StateFlow<Set<String>> =
        _participantPreparingSlots.asStateFlow()
    private val _participantConnectingSlots = MutableStateFlow<Set<String>>(emptySet())
    val participantConnectingSlots: StateFlow<Set<String>> =
        _participantConnectingSlots.asStateFlow()
    private var capturePreflightGeneration = 0
    private var participantSyncJob: Job? = null
    private var participantSyncSlotId: String? = null
    private val participantStopJobs = ConcurrentHashMap<String, Job>()

    private val recordChannel = Channel<Pair<Int, SensorData>>(Channel.UNLIMITED)
    private val recorders = ConcurrentHashMap<Int, CsvRecorder>()
    private var writeCount = 0

    val isConnected: Boolean
        get() = engine.connectedDevices.value.isNotEmpty()

    private fun startBleService() {
        ContextCompat.startForegroundService(
            getApplication(),
            Intent(getApplication(), BleStreamingService::class.java)
        )
    }

    private fun stopBleService() {
        getApplication<Application>().stopService(
            Intent(getApplication(), BleStreamingService::class.java)
        )
    }

    init {
        viewModelScope.launch {
            for (request in historyRequestChannel) {
                processParticipantHistoryRequest(request)
            }
        }
        viewModelScope.launch {
            isScanning.collect { scanning ->
                if (!scanning) {
                    _isManualScanning.value = false
                }
            }
        }
        viewModelScope.launch {
            combine(scannedDevices, deviceRoleConfig) { devices, config ->
                val targetIds = config.targetDeviceIds
                devices
                    .mapIndexedNotNull { index, device ->
                        val deviceId = LongJumpDeviceRoles.normalizeDeviceId(device.address)
                        if (deviceId in targetIds) index else null
                    }
                    .toSet()
            }.collect { targetIndices ->
                _selectedForConnect.value = targetIndices
            }
        }
        viewModelScope.launch {
            var setupAddresses = emptySet<String>()
            connectedDevices.collect { addresses ->
                val connected = addresses
                    .map(LongJumpDeviceRoles::normalizeDeviceId)
                    .toSet()
                val newlyConnected = connected - setupAddresses
                setupAddresses = connected
                if (newlyConnected.isEmpty()) return@collect
                val isSyncReconnect = isSyncing.value ||
                    newlyConnected.any { it in syncTargetAddresses.value }
                var attempts = 0
                while (
                    attempts < 40 &&
                    newlyConnected.any { !engine.isDeviceInitialized(it) }
                ) {
                    delay(250)
                    attempts++
                }
                val devices = engine.getDevices().filter { device ->
                    val normalized = device.address
                        ?.let(LongJumpDeviceRoles::normalizeDeviceId)
                        ?: return@filter false
                    normalized in newlyConnected &&
                        engine.isDeviceInitialized(normalized) &&
                        device.connectionState == DotDevice.CONN_STATE_CONNECTED
                }
                if (
                    devices.isNotEmpty() &&
                    shouldSetupRecordingManagerAfterConnection(
                        isSyncing = isSyncReconnect || isSyncing.value,
                        newlyConnectedAddresses = newlyConnected,
                        syncTargetAddresses = syncTargetAddresses.value,
                    )
                ) {
                    recEngine.ensureSetup(devices)
                }
            }
        }
        viewModelScope.launch {
            recEngine.recordingActive.collect { active ->
                if (active) {
                    _inRecordingMode.value = true
                }
            }
        }

        // IO 协程消费录制 Channel，写盘操作在 IO 线程
        viewModelScope.launch(Dispatchers.IO) {
            for ((id, data) in recordChannel) {
                recorders[id]?.write(data)
                writeCount++
                if (writeCount % 120 == 0) recorders.values.forEach { it.flush() }
            }
        }
        // 数据回调在 BLE 工作线程调用（与官方示例一致），trySend 对 Channel.UNLIMITED 线程安全
        engine.setOnDataCallback { id, data ->
            if (_isRecording.value) recordChannel.trySend(id to data)
        }
        engine.isFlashRecordingDevice = { normalizedAddress ->
            recEngine.deviceRecordingPhases.value[normalizedAddress] in setOf(
                FlashRecordingPhase.Starting,
                FlashRecordingPhase.Recording,
                FlashRecordingPhase.Stopping,
            )
        }
        // 设备断线重连回调：若当前在离线采集模式，恢复录制通知
        // onDotInitDone 重连分支已用 syncOutputRate 恢复采样率，无需再次调用 applyOfflineModeSettings
        engine.onDeviceReconnected = { addr ->
            if (_inRecordingMode.value) {
                recEngine.reenableNotification(addr)
            }
        }
        viewModelScope.launch {
            var previous = recEngine.recordingPhase.value
            recEngine.recordingPhase.collect { phase ->
                when (phase) {
                    FlashRecordingPhase.Recording -> {
                        engine.setRecordingState(true)
                    }
                    FlashRecordingPhase.Idle -> {
                        engine.setRecordingState(false)
                        if (
                            _inRecordingMode.value &&
                            previous in setOf(FlashRecordingPhase.Starting, FlashRecordingPhase.Stopping)
                        ) {
                            engine.stopMeasuring()
                            if (!isSynced.value) {
                                recEngine.requestFlashInfo()
                            }
                        }
                    }
                    else -> Unit
                }
                previous = phase
            }
        }
        viewModelScope.launch {
            var exportTargets = emptySet<String>()
            recEngine.exportTaskProgress.collect { task ->
                val nextTargets =
                    if (task.hasPendingFiles) task.targetAddresses else emptySet()
                if (nextTargets != exportTargets) {
                    exportTargets = nextTargets
                    engine.setExportTargets(nextTargets)
                }
            }
        }
    }

    fun startScan() {
        beginScan(manual = true)
    }

    private fun beginScan(manual: Boolean): Boolean {
        _scanMessage.value = null
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(getApplication(), it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            _isManualScanning.value = false
            _scanMessage.value = "请先在设置中授予蓝牙和定位权限，并开启定位服务"
            return false
        }
        if (manual) {
            _selectedForConnect.value = emptySet()
        }
        engine.startScan()
        _isManualScanning.value = manual && isScanning.value
        return isScanning.value
    }

    fun clearScanMessage() { _scanMessage.value = null }
    fun stopScan() {
        _isManualScanning.value = false
        engine.stopScan()
    }

    fun setAvailableAthletes(athletes: List<CaptureAthleteOption>) {
        val normalized = athletes
            .filter { it.athleteId.isNotBlank() }
            .distinctBy(CaptureAthleteOption::athleteId)
        _availableAthletes.value = normalized
        val athletesById = normalized.associateBy(CaptureAthleteOption::athleteId)
        val current = _deviceRoleConfig.value
        val updated = current.participants.map { participant ->
            val selected = athletesById[participant.athleteId]
            if (participant.athleteId.isBlank() || selected == null) {
                participant.copy(athleteId = "", athleteName = "")
            } else {
                participant.copy(
                    athleteId = selected.athleteId,
                    athleteName = selected.athleteName,
                )
            }
        }
        if (updated != current.participants) {
            saveDeviceRoleConfig(DeviceRoleConfig(updated))
        }
    }

    fun addParticipant() {
        if (configurationOperationLocked()) return
        val current = _deviceRoleConfig.value
        if (current.participants.size >= 3) return
        val usedAthletes = current.participants.map(CaptureParticipantBinding::athleteId).toSet()
        val nextAthlete = _availableAthletes.value.firstOrNull { it.athleteId !in usedAthletes }
        val nextIndex = (1..3).first { candidate ->
            current.participants.none { it.slotId == "participant-$candidate" }
        }
        val slotId = "participant-$nextIndex"
        val (leftDeviceId, rightDeviceId) =
            LongJumpDeviceRoles.defaultDeviceIdsForSlot(slotId)
        val added = CaptureParticipantBinding(
            slotId = slotId,
            athleteId = nextAthlete?.athleteId.orEmpty(),
            athleteName = nextAthlete?.athleteName.orEmpty(),
            leftDeviceId = leftDeviceId,
            rightDeviceId = rightDeviceId,
        )
        saveDeviceRoleConfig(
            current.copy(participants = current.participants + added)
        )
        if (nextAthlete == null) {
            Toast.makeText(
                getApplication(),
                "已添加第 $nextIndex 组，请在管理分组中选择运动员",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    fun removeParticipant(slotId: String) {
        if (participantConfigurationLocked(slotId)) return
        val current = _deviceRoleConfig.value
        if (current.participants.size <= 1) return
        closeParticipantHistory(slotId)
        saveDeviceRoleConfig(
            current.copy(
                participants = current.participants.filterNot { it.slotId == slotId }
            )
        )
    }

    fun selectParticipantAthlete(slotId: String, athleteId: String) {
        if (participantConfigurationLocked(slotId)) return
        val athlete = _availableAthletes.value.firstOrNull { it.athleteId == athleteId } ?: return
        val current = _deviceRoleConfig.value
        val duplicate = current.participants.firstOrNull {
            it.slotId != slotId && it.athleteId == athleteId
        }
        if (duplicate != null) {
            Toast.makeText(getApplication(), "该运动员已加入本次采集", Toast.LENGTH_SHORT).show()
            return
        }
        closeParticipantHistory(slotId)
        saveDeviceRoleConfig(
            current.copy(
                participants = current.participants.map { participant ->
                    if (participant.slotId == slotId) {
                        participant.copy(
                            athleteId = athlete.athleteId,
                            athleteName = athlete.athleteName,
                        )
                    } else {
                        participant
                    }
                }
            )
        )
    }

    fun assignParticipantDevice(slotId: String, sideCode: String, address: String) {
        if (participantConfigurationLocked(slotId)) return
        val selectedId = LongJumpDeviceRoles.normalizeDeviceId(address)
        val current = _deviceRoleConfig.value
        val target = current.participants.firstOrNull { it.slotId == slotId } ?: return
        val targetCurrentId = if (sideCode == "L") target.leftDeviceId else target.rightDeviceId
        if (selectedId == targetCurrentId) return

        val existingAssignment = LongJumpDeviceRoles.assignmentForDevice(selectedId)
        if (existingAssignment != null && existingAssignment.participant.slotId != slotId) {
            Toast.makeText(
                getApplication(),
                "${existingAssignment.displayLabel}已使用该设备",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }

        val updatedTarget = when {
            sideCode == "L" && selectedId == target.rightDeviceId -> target.copy(
                leftDeviceId = target.rightDeviceId,
                rightDeviceId = target.leftDeviceId,
            )
            sideCode == "R" && selectedId == target.leftDeviceId -> target.copy(
                leftDeviceId = target.rightDeviceId,
                rightDeviceId = target.leftDeviceId,
            )
            sideCode == "L" -> target.copy(leftDeviceId = selectedId)
            else -> target.copy(rightDeviceId = selectedId)
        }
        closeParticipantHistory(slotId)
        saveDeviceRoleConfig(
            current.copy(
                participants = current.participants.map {
                    if (it.slotId == slotId) updatedTarget else it
                }
            )
        )
    }

    fun assignLeftDevice(address: String) {
        assignParticipantDevice(_deviceRoleConfig.value.participants.first().slotId, "L", address)
    }

    fun assignRightDevice(address: String) {
        assignParticipantDevice(_deviceRoleConfig.value.participants.first().slotId, "R", address)
    }

    private fun configurationOperationLocked(): Boolean {
        val collectionBusy = engine.state.value in setOf(
            CollectionEngine.CollectionState.Measuring,
            CollectionEngine.CollectionState.Recording,
        )
        if (
            isScanning.value ||
            isSyncing.value ||
            collectionBusy ||
            _captureWorkflowPreparing.value ||
            _historyQueuedParticipantSlots.value.isNotEmpty() ||
            _historyLoadingParticipantSlots.value.isNotEmpty() ||
            recEngine.fileInfoReadActiveTargets.value.isNotEmpty() ||
            _participantConnectingSlots.value.isNotEmpty() ||
            _participantPreparingSlots.value.isNotEmpty() ||
            recEngine.recordingPhase.value != FlashRecordingPhase.Idle ||
            recEngine.recordingExportDecisions.value.isNotEmpty() ||
            recEngine.exportTaskProgress.value.hasPendingFiles ||
            recEngine.eraseTaskProgress.value.isErasing
        ) {
            Toast.makeText(getApplication(), "当前操作完成后再修改采集分组", Toast.LENGTH_SHORT).show()
            return true
        }
        return false
    }

    private fun participantConfigurationLocked(slotId: String): Boolean {
        if (configurationOperationLocked()) return true
        val participant = _deviceRoleConfig.value.participants
            .firstOrNull { it.slotId == slotId }
            ?: return true
        val connectedIds = connectedDevices.value
            .map(LongJumpDeviceRoles::normalizeDeviceId)
            .toSet()
        if (participant.normalizedDeviceIds.any { it in connectedIds }) {
            Toast.makeText(
                getApplication(),
                "请先断开该运动员设备，再修改分组配置",
                Toast.LENGTH_SHORT,
            ).show()
            return true
        }
        return false
    }

    private fun saveDeviceRoleConfig(config: DeviceRoleConfig) {
        _deviceRoleConfig.value = deviceRolePreferences.save(config)
    }

    fun connectSelected() {
        val config = _deviceRoleConfig.value
        val invalidParticipants = config.participants.filterNot(CaptureParticipantBinding::isComplete)
        if (invalidParticipants.isNotEmpty()) {
            val labels = invalidParticipants.joinToString("、") {
                it.athleteName.ifBlank { "未选择运动员" }
            }
            Toast.makeText(
                getApplication(),
                "$labels 的左右脚配置不完整",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        val devicesById = scannedDevices.value.associateBy {
            LongJumpDeviceRoles.normalizeDeviceId(it.address)
        }
        val missing = buildList<String> {
            config.participants.forEach { participant ->
                if (devicesById[participant.leftDeviceId] == null) {
                    add("${participant.athleteName}左脚 ${participant.leftDeviceId}")
                }
                if (devicesById[participant.rightDeviceId] == null) {
                    add("${participant.athleteName}右脚 ${participant.rightDeviceId}")
                }
            }
        }
        if (missing.isNotEmpty()) {
            Toast.makeText(
                getApplication(),
                "未扫描到${missing.joinToString("、")}，请靠近设备后重新扫描",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        val selected = config.participants.flatMap { participant ->
            listOfNotNull(
                devicesById[participant.leftDeviceId],
                devicesById[participant.rightDeviceId],
            )
        }
        startBleService()
        engine.connectDevices(selected)
    }

    fun disconnect() {
        _capturePreparePending.value = false
        _captureWorkflowPreparing.value = false
        if (recEngine.recordingPhase.value != FlashRecordingPhase.Idle) {
            Toast.makeText(getApplication(), "请先停止离线录制，再断开设备", Toast.LENGTH_SHORT).show()
            return
        }
        stopRecording()
        if (_inRecordingMode.value) exitRecordingMode()
        engine.disconnectAll()
        stopBleService()
    }

    fun disconnectParticipant(slotId: String) {
        val participant = _deviceRoleConfig.value.participants
            .firstOrNull { it.slotId == slotId }
            ?: return
        val targets = participantTargets(participant)
        if (participantConnectionOperationLocked(targets, slotId)) return
        val connected = connectedDevices.value.map(LongJumpDeviceRoles::normalizeDeviceId).toSet()
        if (targets.none { it in connected }) return
        _participantConnectingSlots.value -= slotId
        _participantPreparingSlots.value -= slotId
        if (engine.disconnectDevices(targets) && engine.connectedDevices.value.isEmpty()) {
            stopBleService()
        }
    }

    fun connectParticipant(slotId: String) {
        val participant = _deviceRoleConfig.value.participants
            .firstOrNull { it.slotId == slotId }
            ?: return
        val targets = participantTargets(participant)
        if (participantConnectionOperationLocked(targets, slotId)) return
        if (targets.size != 2) {
            Toast.makeText(getApplication(), "该运动员左右脚配置不完整", Toast.LENGTH_SHORT).show()
            return
        }
        _participantConnectingSlots.value += slotId
        if (connectParticipantFromScan(targets)) {
            monitorParticipantConnection(slotId, targets)
            return
        }

        viewModelScope.launch {
            val deadline = SystemClock.elapsedRealtime() + 12_000L
            while (SystemClock.elapsedRealtime() < deadline) {
                if (connectParticipantFromScan(targets)) {
                    monitorParticipantConnection(slotId, targets)
                    return@launch
                }
                if (!isScanning.value && !beginScan(manual = false)) {
                    break
                }
                delay(100)
            }
            if (!connectParticipantFromScan(targets)) {
                _participantConnectingSlots.value -= slotId
                Toast.makeText(
                    getApplication(),
                    "${participant.athleteName}设备未扫描完整，请保持左右脚设备靠近后重试",
                    Toast.LENGTH_LONG,
                ).show()
            } else {
                monitorParticipantConnection(slotId, targets)
            }
        }
    }

    private fun monitorParticipantConnection(
        slotId: String,
        targets: Set<String>,
    ) {
        viewModelScope.launch {
            val deadline = SystemClock.elapsedRealtime() + 20_000L
            while (SystemClock.elapsedRealtime() < deadline) {
                val connected = connectedDevices.value
                    .map(LongJumpDeviceRoles::normalizeDeviceId)
                    .toSet()
                if (targets.all { it in connected }) break
                delay(200)
            }
            _participantConnectingSlots.value -= slotId
        }
    }

    private fun connectParticipantFromScan(targets: Set<String>): Boolean {
        val connected = connectedDevices.value.map(LongJumpDeviceRoles::normalizeDeviceId).toSet()
        val missingTargets = targets - connected
        if (missingTargets.isEmpty()) return true
        val scannedById = scannedDevices.value.associateBy {
            LongJumpDeviceRoles.normalizeDeviceId(it.address)
        }
        val selected = missingTargets.mapNotNull(scannedById::get)
        if (selected.size != missingTargets.size) return false
        startBleService()
        return engine.connectAdditionalDevices(selected)
    }

    private fun participantConnectionOperationLocked(
        targets: Set<String>,
        slotId: String,
    ): Boolean {
        val locked =
            isSyncing.value ||
                _isRecording.value ||
                participantHistoryOperationBusy(slotId) ||
                slotId in _participantConnectingSlots.value ||
                slotId in _participantPreparingSlots.value ||
                participantHasActiveRecordingOperation(
                    recEngine.deviceRecordingPhases.value,
                    targets,
                ) ||
                recEngine.exportTaskProgress.value.hasPendingFiles ||
                recEngine.eraseTaskProgress.value.isErasing
        if (locked) {
            Toast.makeText(
                getApplication(),
                if (participantHistoryOperationBusy(slotId)) {
                    "请等待该运动员的历史文件读取完成"
                } else {
                    "请先结束该运动员的准备或录制操作"
                },
                Toast.LENGTH_SHORT,
            ).show()
        }
        return locked
    }

    private fun participantHistoryOperationBusy(slotId: String): Boolean =
        isParticipantHistoryOperationBusy(
            queued = slotId in _historyQueuedParticipantSlots.value,
            loading = slotId in _historyLoadingParticipantSlots.value,
            activeReadTargets = recEngine.fileInfoReadActiveTargets.value,
            participantTargets = _deviceRoleConfig.value.participants
                .firstOrNull { it.slotId == slotId }
                ?.let(::participantTargets)
                .orEmpty(),
        )

    fun powerOffDevice(address: String) {
        val normalizedAddress = LongJumpDeviceRoles.normalizeDeviceId(address)
        val participant = _deviceRoleConfig.value.participants.firstOrNull {
            normalizedAddress in participantTargets(it)
        }
        if (
            participant != null &&
            participantHistoryOperationBusy(participant.slotId)
        ) {
            Toast.makeText(
                getApplication(),
                "请等待历史文件读取完成，再关闭设备",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        if (recEngine.recordingPhase.value != FlashRecordingPhase.Idle || _isRecording.value) {
            Toast.makeText(getApplication(), "请先停止采集，再关闭设备", Toast.LENGTH_SHORT).show()
            return
        }
        if (isSyncing.value) {
            Toast.makeText(getApplication(), "同步过程中不能关闭设备", Toast.LENGTH_SHORT).show()
            return
        }
        val sent = engine.powerOffDevice(address)
        Toast.makeText(
            getApplication(),
            if (sent) "已发送设备关机指令" else "设备关机指令发送失败",
            Toast.LENGTH_SHORT
        ).show()
    }

    fun startMeasuring() {
        engine.startDirectMeasurement()
    }

    fun stopMeasuring() {
        if (recEngine.recordingPhase.value != FlashRecordingPhase.Idle) {
            Toast.makeText(getApplication(), "请先停止离线录制", Toast.LENGTH_SHORT).show()
            return
        }
        stopRecording()
        engine.stopMeasuring()
    }

    fun startSync() {
        val participant = _deviceRoleConfig.value.participants.firstOrNull { binding ->
            participantTargets(binding).any { deviceSyncStates.value[it] != true }
        } ?: return
        startParticipantSync(participant.slotId)
    }

    fun startParticipantSync(slotId: String) {
        val participant = _deviceRoleConfig.value.participants
            .firstOrNull { it.slotId == slotId }
            ?: return
        val targets = participantTargets(participant)
        if (targets.size != 2) {
            Toast.makeText(getApplication(), "该运动员左右脚配置不完整", Toast.LENGTH_SHORT).show()
            return
        }
        if (participantHistoryOperationBusy(slotId)) {
            Toast.makeText(
                getApplication(),
                "请等待${participant.athleteName}的历史文件读取完成",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        if (
            participantHasActiveRecordingOperation(
                recEngine.deviceRecordingPhases.value,
                targets,
            )
        ) {
            Toast.makeText(
                getApplication(),
                "请先停止${participant.athleteName}的录制",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        if (targets.all { deviceSyncStates.value[it] == true }) return
        if (_captureWorkflowPreparing.value) {
            Toast.makeText(getApplication(), "采集准备正在同步设备，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        if (participantSyncJob?.isActive == true) {
            val activeName = _deviceRoleConfig.value.participants
                .firstOrNull { it.slotId == participantSyncSlotId }
                ?.athleteName
                .orEmpty()
            Toast.makeText(
                getApplication(),
                if (participantSyncSlotId == slotId) "${participant.athleteName}正在同步"
                else "${activeName.ifBlank { "其他运动员" }}正在同步，请稍候",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        participantSyncSlotId = slotId
        participantSyncJob = viewModelScope.launch {
            try {
                val recordingQuietWaitMs = recEngine.releaseDevicesForSync(targets)
                if (recordingQuietWaitMs < 0L) {
                    Toast.makeText(
                        getApplication(),
                        "${participant.athleteName}仍有录制操作，暂不能同步",
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@launch
                }
                if (recordingQuietWaitMs > 0L) delay(recordingQuietWaitMs)
                if (!waitForSyncTargetsReady(targets)) {
                    Toast.makeText(
                        getApplication(),
                        "${participant.athleteName}${engine.syncReadinessDescription(targets)}",
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@launch
                }
                val prepared = engine.prepareSyncParametersForDevices(
                    targets,
                    _recOutputRate.value,
                    _recFilterProfile.value,
                )
                if (!prepared.success) {
                    Toast.makeText(
                        getApplication(),
                        "${participant.athleteName}设备参数未准备好",
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@launch
                }
                if (prepared.waitMsBeforeSync > 0L) delay(prepared.waitMsBeforeSync)
                if (!waitForSyncTargetsReady(targets)) {
                    Toast.makeText(
                        getApplication(),
                        "${participant.athleteName}${engine.syncReadinessDescription(targets)}",
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@launch
                }
                if (engine.startSync(targets)) return@launch
                Toast.makeText(
                    getApplication(),
                    "${participant.athleteName}${engine.syncReadinessDescription(targets)}",
                    Toast.LENGTH_SHORT,
                ).show()
            } finally {
                participantSyncJob = null
                participantSyncSlotId = null
            }
        }
    }

    fun stopSync() {
        stopSyncTargets(null)
    }

    fun stopParticipantSync(slotId: String) {
        val participant = _deviceRoleConfig.value.participants
            .firstOrNull { it.slotId == slotId }
            ?: return
        if (participantHistoryOperationBusy(slotId)) {
            Toast.makeText(
                getApplication(),
                "请等待历史文件读取完成，再解除同步",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        stopSyncTargets(participantTargets(participant))
    }

    private fun stopSyncTargets(targets: Set<String>?) {
        val recordingTargets = targets ?: recEngine.deviceRecordingPhases.value.keys
        if (
            participantHasActiveRecordingOperation(
                recEngine.deviceRecordingPhases.value,
                recordingTargets,
            )
        ) {
            Toast.makeText(getApplication(), "请先停止录制，再解除同步", Toast.LENGTH_SHORT).show()
            return
        }
        if (recEngine.exportTaskProgress.value.hasPendingFiles) {
            Toast.makeText(getApplication(), "请等待文件导出完成，再解除同步", Toast.LENGTH_SHORT).show()
            return
        }
        if (recEngine.eraseTaskProgress.value.isErasing) {
            Toast.makeText(getApplication(), "请等待 Flash 擦除完成，再解除同步", Toast.LENGTH_SHORT).show()
            return
        }
        _capturePreparePending.value = false
        _captureWorkflowPreparing.value = false
        capturePreflightGeneration++

        val stopAction = {
            if (targets == null) {
                engine.stopSync()
            } else {
                engine.stopSync(targets)
            }
        }
        if (_inRecordingMode.value && targets == null) {
            recEngine.clear()
            _inRecordingMode.value = false
            clearFileSelection()
            viewModelScope.launch {
                // 清理录制通知会占用同一条 GATT 队列，排空后再发送解除同步。
                delay(1_200)
                stopAction()
            }
        } else {
            stopAction()
        }
    }
    fun startDirectMeasurement() = engine.startDirectMeasurement()

    fun headingReset() {
        _headingActiveButton.value = "reset"
        engine.headingReset()
    }
    fun headingRevert() {
        _headingActiveButton.value = "revert"
        engine.headingRevert()
    }

    fun applyPayloadMode(mode: Int) = engine.applyPayloadMode(mode)
    fun setFilterProfileMode(mode: Int) = engine.setFilterProfileMode(mode)

    // ── 离线采集控制 ──

    /**
     * 进入离线采集模式：只停止 CSV 实时录制并配置离线参数。
     * 真正的测量启动放到“开始录制”时执行，避免刚进入页面设备就闪灯像已采集。
     */
    fun prepareCapture() {
        val connectedCount = engine.connectedDevices.value.size
        if (connectedCount == 0) {
            Toast.makeText(getApplication(), "请先连接设备", Toast.LENGTH_SHORT).show()
            return
        }
        if (recEngine.recordingPhase.value != FlashRecordingPhase.Idle) return
        if (_isRecording.value) stopRecording()
        _captureWorkflowPreparing.value = true
        beginRecordingStatePreflight()
    }

    private fun beginRecordingStatePreflight() {
        val generation = ++capturePreflightGeneration
        val configuredTargets = _deviceRoleConfig.value.targetDeviceIds
            .map(LongJumpDeviceRoles::normalizeDeviceId)
            .toSet()
        val connectedTargets = engine.connectedDevices.value
            .map(LongJumpDeviceRoles::normalizeDeviceId)
            .toSet()
        val devices = engine.getDevices().filter { device ->
            val address = device.address
                ?.let(LongJumpDeviceRoles::normalizeDeviceId)
                ?: return@filter false
            address in configuredTargets &&
                address in connectedTargets &&
                device.connectionState == com.xsens.dot.android.sdk.models.DotDevice.CONN_STATE_CONNECTED
        }
        val expectedTargets = devices.mapNotNull { device ->
            device.address?.let(LongJumpDeviceRoles::normalizeDeviceId)
        }.toSet()
        if (
            devices.isEmpty() ||
            expectedTargets.size != configuredTargets.size ||
            !expectedTargets.containsAll(configuredTargets)
        ) {
            failCapturePreparation(
                generation,
                "本次运动员设备未全部连接，请保持设备靠近后重新准备"
            )
            return
        }
        startBleService()
        _inRecordingMode.value = true
        recEngine.setup(devices)

        viewModelScope.launch {
            var attempts = 0
            val maxAttempts = 24 + (expectedTargets.size - 2).coerceAtLeast(0) * 8
            while (
                attempts < maxAttempts &&
                !expectedTargets.all { it in recEngine.recordingStates.value }
            ) {
                if (generation != capturePreflightGeneration || !_inRecordingMode.value) return@launch
                delay(250)
                attempts++
            }
            if (generation != capturePreflightGeneration || !_inRecordingMode.value) return@launch

            if (recEngine.recordingPhase.value == FlashRecordingPhase.Recording) {
                _captureWorkflowPreparing.value = false
                return@launch
            }

            val allStatesKnown = expectedTargets.all { target ->
                target in recEngine.recordingStates.value
            }
            if (!allStatesKnown) {
                recEngine.clear()
                _inRecordingMode.value = false
                _captureWorkflowPreparing.value = false
                Toast.makeText(
                    getApplication(),
                    "未能确认全部设备录制状态，请保持设备靠近后重新准备",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            continueCapturePreparationAfterIdlePreflight(expectedTargets.size, generation)
        }
    }

    private fun continueCapturePreparationAfterIdlePreflight(
        connectedCount: Int,
        generation: Int
    ) {
        if (generation != capturePreflightGeneration) return
        if (connectedCount >= 2 && !allParticipantGroupsSynced()) {
            if (engine.confirmExistingSyncIfAllConnected()) {
                _capturePreparePending.value = false
                _captureWorkflowPreparing.value = false
                return
            }
            recEngine.clear()
            _inRecordingMode.value = false
            _capturePreparePending.value = true
            viewModelScope.launch {
                // Recording cleanup updates notification state through GATT. Let those writes
                // finish before synchronization starts on the same device connections.
                delay(2_000)
                synchronizeParticipantGroups(generation)
            }
            return
        }

        if (!isSynced.value) {
            engine.prepareOfflineModeSettings(_recOutputRate.value, _recFilterProfile.value)
        }
        _captureWorkflowPreparing.value = false
    }

    private suspend fun synchronizeParticipantGroups(generation: Int) {
        val participants = _deviceRoleConfig.value.participants
        for (participant in participants) {
            if (
                generation != capturePreflightGeneration ||
                !_capturePreparePending.value
            ) {
                return
            }
            val targets = participantTargets(participant)
            if (targets.size != 2) {
                failCapturePreparation(generation, "${participant.athleteName}左右脚配置不完整")
                return
            }
            if (targets.all { deviceSyncStates.value[it] == true }) continue
            val recordingQuietWaitMs = recEngine.releaseDevicesForSync(targets)
            if (recordingQuietWaitMs < 0L) {
                failCapturePreparation(
                    generation,
                    "${participant.athleteName}仍有录制操作，无法同步",
                )
                return
            }
            if (recordingQuietWaitMs > 0L) delay(recordingQuietWaitMs)
            if (!waitForSyncTargetsReady(targets)) {
                failCapturePreparation(
                    generation,
                    "${participant.athleteName}${engine.syncReadinessDescription(targets)}",
                )
                return
            }

            val prepared = engine.prepareSyncParametersForDevices(
                targets,
                _recOutputRate.value,
                _recFilterProfile.value,
            )
            if (!prepared.success) {
                failCapturePreparation(generation, "${participant.athleteName}设备参数未准备好")
                return
            }
            if (prepared.waitMsBeforeSync > 0L) delay(prepared.waitMsBeforeSync)
            if (!waitForSyncTargetsReady(targets)) {
                failCapturePreparation(
                    generation,
                    "${participant.athleteName}${engine.syncReadinessDescription(targets)}",
                )
                return
            }
            if (
                generation != capturePreflightGeneration ||
                !_capturePreparePending.value
            ) {
                return
            }
            if (!engine.startSync(targets)) {
                failCapturePreparation(generation, "${participant.athleteName}同步未启动")
                return
            }

            var waitTicks = 0
            while (
                waitTicks < 280 &&
                generation == capturePreflightGeneration &&
                _capturePreparePending.value &&
                (
                    isSyncing.value ||
                        syncTargetAddresses.value.isNotEmpty() ||
                        !targets.all { deviceSyncStates.value[it] == true }
                    )
            ) {
                delay(250)
                waitTicks++
                if (
                    !isSyncing.value &&
                    syncTargetAddresses.value.isEmpty() &&
                    !targets.all { deviceSyncStates.value[it] == true }
                ) {
                    break
                }
            }
            if (!targets.all { deviceSyncStates.value[it] == true }) {
                failCapturePreparation(generation, "${participant.athleteName}左右脚同步未完成")
                return
            }
            delay(800)
        }

        if (
            generation != capturePreflightGeneration ||
            !_capturePreparePending.value
        ) {
            return
        }
        _capturePreparePending.value = false
        delay(800)
        enterRecordingMode()
    }

    private fun participantTargets(participant: CaptureParticipantBinding): Set<String> =
        _deviceRoleConfig.value.targetsForParticipant(participant.slotId)

    private suspend fun waitForSyncTargetsReady(
        targets: Set<String>,
        timeoutMs: Long = 8_000L,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (engine.areDevicesReadyForSync(targets)) return true
            delay(100L)
        }
        return engine.areDevicesReadyForSync(targets)
    }

    private fun allParticipantGroupsSynced(): Boolean =
        _deviceRoleConfig.value.participants.all { participant ->
            val targets = participantTargets(participant)
            targets.size == 2 && targets.all { deviceSyncStates.value[it] == true }
        }

    private fun failCapturePreparation(generation: Int, message: String) {
        if (generation != capturePreflightGeneration) return
        _capturePreparePending.value = false
        _captureWorkflowPreparing.value = false
        if (_inRecordingMode.value && recEngine.recordingPhase.value == FlashRecordingPhase.Idle) {
            recEngine.clear()
            _inRecordingMode.value = false
        }
        Toast.makeText(getApplication(), message, Toast.LENGTH_LONG).show()
    }

    fun enterRecordingMode() {
        if (_inRecordingMode.value) {
            return
        }
        val devices = engine.getDevices()
        if (devices.isEmpty()) {
            Toast.makeText(getApplication(), "请先连接设备", Toast.LENGTH_SHORT).show()
            return
        }
        startBleService()
        stopRecording()
        _inRecordingMode.value = true
        if (!isSynced.value) {
            engine.prepareOfflineModeSettings(_recOutputRate.value, _recFilterProfile.value)
        }
        viewModelScope.launch {
            delay(800)  // 等待参数写入和 GATT 队列空闲
            recEngine.setup(devices)
            // 同步成功后偶发录制通知/Flash 信息首轮未回齐，补查询两轮，避免一直停在初始化中。
            repeat(2) {
                delay(2_500)
                if (!_inRecordingMode.value || recEngine.recordingPhase.value != FlashRecordingPhase.Idle) return@launch
                val notificationReadyCount = recEngine.notificationReady.value.size
                val flashInfoCount = recEngine.flashInfo.value.size
                val recordingStateCount = recEngine.recordingStates.value.size
                if (
                    notificationReadyCount >= devices.size &&
                    flashInfoCount >= devices.size &&
                    recordingStateCount >= devices.size
                ) {
                    _captureWorkflowPreparing.value = false
                    return@launch
                }
                recEngine.refreshSetupState()
            }
            _captureWorkflowPreparing.value = false
        }
    }

    /** 手动重试录制通知（首次启用失败时使用） */
    fun retryRecordingNotification() {
        if (engine.getDevices().isNotEmpty()) {
            recEngine.refreshSetupState()
        }
    }

    /** 退出离线采集模式，清理 RecordingEngine，恢复实时采集的 60Hz + 原有滤波档 */
    fun exitRecordingMode() {
        capturePreflightGeneration++
        _capturePreparePending.value = false
        _captureWorkflowPreparing.value = false
        if (recEngine.recordingPhase.value != FlashRecordingPhase.Idle) {
            Toast.makeText(getApplication(), "请先停止离线录制", Toast.LENGTH_SHORT).show()
            return
        }
        recEngine.clear()
        _inRecordingMode.value = false
        clearFileSelection()
        // 恢复实时采集参数（60Hz + 用户上次选择的实时滤波档），不自动开始测量。
        engine.prepareOfflineModeSettings(60, engine.filterProfile.value)
    }

    fun requestFlashInfo()    = recEngine.requestFlashInfo()
    fun eraseFlash()          = recEngine.eraseAll()
    fun startFlashRecording() {
        val participant = _deviceRoleConfig.value.participants.firstOrNull { binding ->
            participantTargets(binding).all { target ->
                recEngine.deviceRecordingPhases.value[target] == FlashRecordingPhase.Idle
            }
        } ?: return
        startParticipantFlashRecording(participant.slotId)
    }

    fun startParticipantFlashRecording(slotId: String) {
        val participant = _deviceRoleConfig.value.participants
            .firstOrNull { it.slotId == slotId }
            ?: return
        val targets = participantTargets(participant)
        if (targets.size != 2) {
            Toast.makeText(getApplication(), "该运动员左右脚配置不完整", Toast.LENGTH_SHORT).show()
            return
        }
        if (participantHistoryOperationBusy(slotId)) {
            Toast.makeText(
                getApplication(),
                "请等待历史文件读取完成，再开始采集",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        if (!targets.all { target -> target in engine.connectedDevices.value.map(LongJumpDeviceRoles::normalizeDeviceId) }) {
            Toast.makeText(getApplication(), "${participant.athleteName}设备未全部连接", Toast.LENGTH_SHORT).show()
            return
        }
        if (!targets.all { deviceSyncStates.value[it] == true }) {
            Toast.makeText(getApplication(), "请先同步${participant.athleteName}的左右脚", Toast.LENGTH_SHORT).show()
            return
        }
        if (slotId in _participantPreparingSlots.value) return
        if (slotId in _visibleHistoryParticipantSlots.value) {
            closeParticipantHistory(slotId)
        }

        if (!participantRecordingSetupReady(targets)) {
            prepareAndStartParticipantRecording(participant, targets)
            return
        }
        startPreparedParticipantRecording(participant, targets)
    }

    private fun prepareAndStartParticipantRecording(
        participant: CaptureParticipantBinding,
        targets: Set<String>,
    ) {
        val devices = engine.getDevices().filter { device ->
            val address = device.address
                ?.let(LongJumpDeviceRoles::normalizeDeviceId)
                ?: return@filter false
            address in targets &&
                device.connectionState ==
                    com.xsens.dot.android.sdk.models.DotDevice.CONN_STATE_CONNECTED
        }
        if (devices.size != targets.size) {
            Toast.makeText(
                getApplication(),
                "${participant.athleteName}设备未全部连接",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }

        startBleService()
        _inRecordingMode.value = true
        _participantPreparingSlots.value += participant.slotId
        recEngine.ensureSetup(devices)

        viewModelScope.launch {
            repeat(48) { attempt ->
                if (participant.slotId !in _participantPreparingSlots.value) return@launch
                if (participantRecordingSetupReady(targets)) {
                    _participantPreparingSlots.value -= participant.slotId
                    startPreparedParticipantRecording(participant, targets)
                    return@launch
                }
                if (attempt > 0 && attempt % 8 == 0) {
                    recEngine.refreshSetupState(targets)
                }
                delay(250)
            }
            _participantPreparingSlots.value -= participant.slotId
            Toast.makeText(
                getApplication(),
                "${participant.athleteName}录制初始化超时，请保持设备靠近后重试",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun participantRecordingSetupReady(targets: Set<String>): Boolean =
        targets.all { it in recEngine.notificationReady.value } &&
            targets.all { target ->
                val (used, total) = recEngine.flashInfo.value[target] ?: return@all false
                total > 0 && used.toFloat() / total.toFloat() < 0.9f
            } &&
            targets.all { target ->
                recEngine.recordingStates.value[target] in setOf(
                    DotRecordingState.idle,
                    DotRecordingState.success,
                )
            }

    private fun startPreparedParticipantRecording(
        participant: CaptureParticipantBinding,
        targets: Set<String>,
    ) {
        startBleService()
        _inRecordingMode.value = true
        if (!engine.prepareDevicesForFlashRecording(targets)) {
            Toast.makeText(
                getApplication(),
                "${participant.athleteName}设备连接或同步状态已变化",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        if (!recEngine.prepareStartRecording(targets)) return
        viewModelScope.launch {
            delay(1_500)
            if (targets.all {
                    recEngine.deviceRecordingPhases.value[it] == FlashRecordingPhase.Starting
                }
            ) {
                recEngine.startRecording(targets)
            }
        }
    }

    fun startAllFlashRecording() {
        if (recEngine.recordingPhase.value != FlashRecordingPhase.Idle) return
        val targets = engine.connectedDevices.value
            .map(LongJumpDeviceRoles::normalizeDeviceId)
            .toSet()
        if (!engine.prepareDevicesForFlashRecording(targets)) {
            Toast.makeText(getApplication(), "设备未全部连接或同步，无法开始录制", Toast.LENGTH_SHORT).show()
            return
        }
        if (!recEngine.prepareStartRecording()) return
        viewModelScope.launch {
            delay(1_500)
            if (_inRecordingMode.value && recEngine.recordingPhase.value == FlashRecordingPhase.Starting) {
                recEngine.startRecording()
            }
        }
    }
    fun stopFlashRecording() {
        if (recEngine.recordingPhase.value != FlashRecordingPhase.Recording) return
        recEngine.stopRecording()
    }

    fun stopParticipantFlashRecording(slotId: String) {
        val participant = _deviceRoleConfig.value.participants
            .firstOrNull { it.slotId == slotId }
            ?: return
        val targets = participantTargets(participant)
        if (targets.none {
                recEngine.deviceRecordingPhases.value[it] in setOf(
                    FlashRecordingPhase.Recording,
                    FlashRecordingPhase.Stopping,
                )
            }
        ) return
        recEngine.stopRecording(targets)
        participantStopJobs.remove(slotId)?.cancel()
        lateinit var stopJob: Job
        stopJob = viewModelScope.launch {
            try {
                while (
                    targets.any {
                        recEngine.deviceRecordingPhases.value[it] in setOf(
                            FlashRecordingPhase.Recording,
                            FlashRecordingPhase.Stopping,
                        )
                    }
                ) {
                    delay(250)
                }
                engine.stopMeasuring(targets)
            } finally {
                participantStopJobs.remove(slotId, stopJob)
            }
        }
        participantStopJobs[slotId] = stopJob
    }

    fun forceStopFlashRecording() {
        if (recEngine.recordingPhase.value == FlashRecordingPhase.Idle) return
        recEngine.forceStopRecording()
    }
    fun requestFiles() = recEngine.requestFileInfo()

    fun requestParticipantFiles(slotId: String) {
        if (slotId in _visibleHistoryParticipantSlots.value) {
            closeParticipantHistory(slotId)
            return
        }
        val targets = validateParticipantHistoryRequest(slotId) ?: return
        clearParticipantFileSelection(slotId)
        _visibleHistoryParticipantSlots.value += slotId
        enqueueParticipantHistoryRequest(slotId, targets)
    }

    fun retryParticipantFiles(slotId: String) {
        if (slotId !in _visibleHistoryParticipantSlots.value) return
        if (participantHistoryOperationBusy(slotId)) return
        val targets = validateParticipantHistoryRequest(slotId) ?: return
        clearParticipantFileSelection(slotId)
        enqueueParticipantHistoryRequest(slotId, targets)
    }

    private fun validateParticipantHistoryRequest(slotId: String): Set<String>? {
        val participant = _deviceRoleConfig.value.participants
            .firstOrNull { it.slotId == slotId }
            ?: return null
        val targets = participantTargets(participant)
        if (isSyncing.value) {
            Toast.makeText(
                getApplication(),
                "请等待同步完成，再读取历史文件",
                Toast.LENGTH_SHORT,
            ).show()
            return null
        }
        if (recEngine.exportTaskProgress.value.hasPendingFiles) {
            Toast.makeText(
                getApplication(),
                "请等待当前导出完成，再读取历史文件",
                Toast.LENGTH_SHORT,
            ).show()
            return null
        }
        val connected = engine.connectedDevices.value
            .map(LongJumpDeviceRoles::normalizeDeviceId)
            .toSet()
        if (targets.size != 2 || !connected.containsAll(targets)) {
            Toast.makeText(
                getApplication(),
                "${participant.athleteName}设备未全部连接",
                Toast.LENGTH_SHORT,
            ).show()
            return null
        }
        val activeTargets = targets.filter { target ->
            recEngine.deviceRecordingPhases.value[target] in setOf(
                FlashRecordingPhase.Starting,
                FlashRecordingPhase.Recording,
                FlashRecordingPhase.Stopping,
            )
        }
        if (activeTargets.isNotEmpty()) {
            Toast.makeText(
                getApplication(),
                "请先结束该运动员的录制",
                Toast.LENGTH_SHORT,
            ).show()
            return null
        }
        return targets
    }

    private fun closeParticipantHistory(slotId: String) {
        _visibleHistoryParticipantSlots.value -= slotId
        _historyRequestErrors.value -= slotId
        clearParticipantFileSelection(slotId)
        if (slotId in _historyLoadingParticipantSlots.value) {
            return
        }
        historyRequestGenerations[slotId] =
            (historyRequestGenerations[slotId] ?: 0) + 1
        _historyQueuedParticipantSlots.value -= slotId
    }

    fun closeAllParticipantHistories() {
        _visibleHistoryParticipantSlots.value.toList().forEach(::closeParticipantHistory)
    }

    private fun enqueueParticipantHistoryRequest(
        slotId: String,
        targets: Set<String>,
    ) {
        val participant = _deviceRoleConfig.value.participants
            .firstOrNull { it.slotId == slotId }
            ?: return
        val generation = (historyRequestGenerations[slotId] ?: 0) + 1
        historyRequestGenerations[slotId] = generation
        _historyRequestErrors.value -= slotId
        _historyLoadingParticipantSlots.value -= slotId
        _historyQueuedParticipantSlots.value += slotId
        historyRequestChannel.trySend(
            ParticipantHistoryRequest(
                slotId = slotId,
                targets = targets,
                configFingerprint = participantHistoryConfigFingerprint(participant),
                generation = generation,
            )
        )
        if (targets.isEmpty()) {
            _historyQueuedParticipantSlots.value -= slotId
        }
    }

    private suspend fun processParticipantHistoryRequest(
        request: ParticipantHistoryRequest,
    ) {
        val slotId = request.slotId
        fun requestCurrent(): Boolean =
            historyRequestGenerations[slotId] == request.generation

        if (!requestCurrent()) return

        val participant = _deviceRoleConfig.value.participants
            .firstOrNull { it.slotId == slotId }
        val targets = request.targets
        if (
            participant == null ||
            targets.size != 2 ||
            participantHistoryConfigFingerprint(participant) != request.configFingerprint ||
            participantTargets(participant) != targets
        ) {
            _historyQueuedParticipantSlots.value -= slotId
            _historyRequestErrors.value +=
                (slotId to "分组配置已变化，请重新读取")
            return
        }

        val acceptDeadline = SystemClock.elapsedRealtime() + 10 * 60_000L
        var accepted = false
        while (requestCurrent() && SystemClock.elapsedRealtime() < acceptDeadline) {
            val connected = engine.connectedDevices.value
                .map(LongJumpDeviceRoles::normalizeDeviceId)
                .toSet()
            if (!connected.containsAll(targets)) {
                _historyQueuedParticipantSlots.value -= slotId
                _historyRequestErrors.value +=
                    (slotId to "设备已断开，回连后可重新读取")
                return
            }
            if (recEngine.requestFileInfo(targets)) {
                accepted = true
                break
            }
            delay(300)
        }

        if (!requestCurrent()) return
        _historyQueuedParticipantSlots.value -= slotId
        if (!accepted) {
            _historyRequestErrors.value +=
                (slotId to "等待设备操作完成超时，请重试")
            return
        }

        _historyLoadingParticipantSlots.value += slotId
        val completionDeadline = SystemClock.elapsedRealtime() + 15_000L
        var disconnectReported = false
        while (requestCurrent() && SystemClock.elapsedRealtime() < completionDeadline) {
            val statuses = recEngine.fileInfoReadStatuses.value
            val completed = areFileInfoReadTargetsTerminal(statuses, targets)
            if (completed) break
            val connected = engine.connectedDevices.value
                .map(LongJumpDeviceRoles::normalizeDeviceId)
                .toSet()
            if (!connected.containsAll(targets) && !disconnectReported) {
                disconnectReported = true
                _historyRequestErrors.value +=
                    (slotId to "读取过程中设备断开，回连后可重试")
            }
            delay(100)
        }
        val unfinished = targets.filter {
            recEngine.fileInfoReadStatuses.value[it]?.phase == FileInfoReadPhase.Reading
        }.toSet()
        if (unfinished.isNotEmpty()) {
            recEngine.expireFileInfoRead(
                targets = unfinished,
                message = "文件列表读取未完成",
            )
        }
        if (historyRequestGenerations[slotId] == request.generation) {
            _historyLoadingParticipantSlots.value -= slotId
            val statuses = recEngine.fileInfoReadStatuses.value
            if (targets.any { statuses[it]?.phase == FileInfoReadPhase.Failed }) {
                _historyRequestErrors.value +=
                    (slotId to "部分设备读取未完成，请重试")
            }
        }
    }

    private fun participantHistoryConfigFingerprint(
        participant: CaptureParticipantBinding,
    ): String = listOf(
        participant.slotId,
        participant.athleteId,
        participant.leftDeviceId,
        participant.rightDeviceId,
    ).joinToString("|")

    fun stopExportFiles()     = recEngine.stopExporting()

    fun startRecording() {
        if (_isRecording.value) return
        val addresses = engine.connectedDevices.value
        if (addresses.isEmpty()) {
            Toast.makeText(getApplication(), "请先连接设备", Toast.LENGTH_SHORT).show()
            return
        }
        val app = getApplication<Application>()
        try {
            addresses.forEachIndexed { index, address ->
                val recorder = CsvRecorder(index, address)
                val path = recorder.start()
                if (_recordingPath.value == null) {
                    _recordingPath.value = java.io.File(path).parent
                }
                recorders[index] = recorder
            }
        } catch (e: Exception) {
            recorders.values.forEach { it.stop() }
            recorders.clear()
            _isRecording.value = false
            engine.setRecordingState(false)
            _recordingPath.value = null
            Toast.makeText(app, "实时数据写盘失败：${e.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
            return
        }
        _isRecording.value = true
        startBleService()
        engine.setRecordingState(true)
        Toast.makeText(app, "已开始录制，共 ${addresses.size} 个传感器", Toast.LENGTH_SHORT).show()
    }

    fun stopRecording() {
        val count = recorders.size
        recorders.values.forEach { it.stop() }
        recorders.clear()
        _isRecording.value = false
        engine.setRecordingState(false)
        _recordingPath.value = null
        if (count > 0) {
            Toast.makeText(getApplication(), "已停止录制，数据已保存", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCleared() {
        super.onCleared()
        participantSyncJob?.cancel()
        participantStopJobs.values.forEach { it.cancel() }
        participantStopJobs.clear()
        engine.isFlashRecordingDevice = null
        recEngine.clear()
        engine.close()
        stopBleService()
    }
}
