package com.buct.xsens.dot.viewmodel

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.buct.xsens.dot.data.CsvRecorder
import com.buct.xsens.dot.data.LongJumpDeviceRoles
import com.buct.xsens.dot.data.ScannedDevice
import com.buct.xsens.dot.data.SensorData
import com.buct.xsens.dot.engine.CollectionEngine
import com.buct.xsens.dot.engine.FlashRecordingPhase
import com.buct.xsens.dot.engine.RecordingEngine
import com.buct.xsens.dot.service.BleStreamingService
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class CollectionViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = CollectionEngine(application)
    private val recEngine = RecordingEngine(application)

    val scannedDevices: StateFlow<List<ScannedDevice>> = engine.scannedDevices
    val isScanning: StateFlow<Boolean> = engine.isScanning
    val connectedDevices: StateFlow<List<String>> = engine.connectedDevices
    val state: StateFlow<CollectionEngine.CollectionState> = engine.state
    val recvCount: StateFlow<Long> = engine.recvCount
    val sensorData: StateFlow<Map<String, SensorData>> = engine.sensorData
    val waveData: StateFlow<Map<String, com.buct.xsens.dot.data.WaveSnapshot>> = engine.waveData
    val syncLog: StateFlow<List<String>> = engine.syncLog
    val isSynced: StateFlow<Boolean> = engine.isSynced
    val isSyncing: StateFlow<Boolean> = engine.isSyncing
    val syncProgress: StateFlow<Int> = engine.syncProgress
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
    val recRecordingStates = recEngine.recordingStates
    val recFileList        = recEngine.fileList
    val recExportProgress  = recEngine.exportProgress
    val recExportDone      = recEngine.exportDone
    val recExportTaskProgress = recEngine.exportTaskProgress
    val recPendingRecordingExportKeys = recEngine.pendingRecordingExportKeys
    val recPreparingRecordingExport = recEngine.preparingRecordingExport
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

    fun toggleFileSelection(addr: String, fileId: Int) {
        val key = "$addr-$fileId"
        _selectedFileKeys.update { if (key in it) it - key else it + key }
    }

    /** 切换某台设备下所有文件的选中状态（全选/全不选） */
    fun toggleDeviceSelection(addr: String) {
        val deviceKeys = recEngine.fileList.value[addr]
            ?.map { f -> "$addr-${f.fileId}" }?.toSet() ?: return
        _selectedFileKeys.update { current ->
            if (deviceKeys.all { it in current }) current - deviceKeys   // 已全选 → 全取消
            else current + deviceKeys                                     // 否则 → 全选
        }
    }

    fun selectAllFiles() {
        _selectedFileKeys.value = recEngine.fileList.value
            .flatMap { (addr, files) -> files.map { f -> "$addr-${f.fileId}" } }
            .toSet()
    }

    fun clearFileSelection() { _selectedFileKeys.value = emptySet() }

    fun exportFiles() {
        val sel = _selectedFileKeys.value
        if (sel.isEmpty()) recEngine.exportAll() else recEngine.exportSelected(sel)
    }

    fun exportLatestRecording() {
        clearFileSelection()
        recEngine.exportLatestRecording()
    }

    fun dismissLatestRecordingExport() {
        clearFileSelection()
        recEngine.dismissLatestRecordingExport()
    }

    private val _inRecordingMode = MutableStateFlow(false)
    val inRecordingMode: StateFlow<Boolean> = _inRecordingMode.asStateFlow()

    private val _headingActiveButton = MutableStateFlow("reset")
    val headingActiveButton: StateFlow<String> = _headingActiveButton.asStateFlow()

    private val _scanMessage = MutableStateFlow<String?>(null)
    val scanMessage: StateFlow<String?> = _scanMessage.asStateFlow()

    private val _capturePreparePending = MutableStateFlow(false)
    private val _captureWorkflowPreparing = MutableStateFlow(false)
    val captureWorkflowPreparing: StateFlow<Boolean> = _captureWorkflowPreparing.asStateFlow()
    private var capturePreflightGeneration = 0

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
            scannedDevices.collect { devices ->
                val targetIndices = devices
                    .mapIndexedNotNull { index, device ->
                        if (LongJumpDeviceRoles.isTargetDevice(device.address)) index else null
                    }
                    .toSet()
                _selectedForConnect.value = targetIndices
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
                        if (_inRecordingMode.value &&
                            !isSynced.value &&
                            previous in setOf(FlashRecordingPhase.Starting, FlashRecordingPhase.Stopping)
                        ) {
                            engine.stopMeasuring()
                            recEngine.requestFlashInfo()
                        }
                    }
                    else -> Unit
                }
                previous = phase
            }
        }
        viewModelScope.launch {
            var exportActive = false
            recEngine.exportTaskProgress.collect { task ->
                val pending = task.hasPendingFiles
                if (pending != exportActive) {
                    exportActive = pending
                    engine.setExportInProgress(pending)
                }
            }
        }
        viewModelScope.launch {
            var wasSyncing = false
            combine(isSynced, isSyncing) { synced, syncing -> synced to syncing }
                .collect { (synced, syncing) ->
                    if (synced && !syncing && _capturePreparePending.value && !_inRecordingMode.value) {
                        _capturePreparePending.value = false
                        delay(1_000)
                        enterRecordingMode()
                    } else if (
                        wasSyncing &&
                        !syncing &&
                        !synced &&
                        _capturePreparePending.value
                    ) {
                        _capturePreparePending.value = false
                        _captureWorkflowPreparing.value = false
                        Toast.makeText(
                            getApplication(),
                            "设备同步未完成，请重新准备采集",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    wasSyncing = syncing
                }
        }
    }

    fun startScan() {
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
            _scanMessage.value = "请先在设置中授予蓝牙和定位权限，并开启定位服务"
            return
        }
        _selectedForConnect.value = emptySet()
        engine.startScan()
    }

    fun clearScanMessage() { _scanMessage.value = null }
    fun stopScan() = engine.stopScan()

    fun toggleSelection(index: Int) {
        _selectedForConnect.update { set ->
            if (set.contains(index)) set - index else set + index
        }
    }

    fun selectAll() {
        _selectedForConnect.value = scannedDevices.value
            .mapIndexedNotNull { index, device ->
                if (LongJumpDeviceRoles.isTargetDevice(device.address)) index else null
            }
            .toSet()
    }

    fun deselectAll() {
        _selectedForConnect.value = emptySet()
    }

    fun connectSelected() {
        val list = scannedDevices.value
        val selected = _selectedForConnect.value
            .mapNotNull { list.getOrNull(it) }
            .filter { LongJumpDeviceRoles.isTargetDevice(it.address) }
            .sortedBy { LongJumpDeviceRoles.roleSortIndex(it.address) }
        if (selected.isNotEmpty()) startBleService()
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

    fun powerOffDevice(address: String) {
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
        // 同步前先停止所有采集状态，避免设备处于录制/测量中导致同步失败
        if (recEngine.recordingPhase.value != FlashRecordingPhase.Idle) {
            Toast.makeText(getApplication(), "请先停止离线录制并等待设备回连", Toast.LENGTH_SHORT).show()
            return
        }
        if (_isRecording.value) {
            stopRecording()
        }
        if (engine.confirmExistingSyncIfAllConnected()) {
            return
        }
        val prepared = engine.prepareSyncParameters(_recOutputRate.value, _recFilterProfile.value)
        if (!prepared.success) {
            Toast.makeText(getApplication(), "请先停止采集或解除同步，再执行 SDK 同步", Toast.LENGTH_SHORT).show()
            return
        }
        viewModelScope.launch {
            if (prepared.waitMsBeforeSync > 0L) delay(prepared.waitMsBeforeSync)
            if (!engine.startSync()) {
                Toast.makeText(getApplication(), "设备未就绪，请保持设备靠近后重新同步", Toast.LENGTH_SHORT).show()
            }
        }
    }
    fun stopSync() {
        _capturePreparePending.value = false
        _captureWorkflowPreparing.value = false
        engine.stopSync()
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
        val devices = engine.getDevices()
        if (devices.isEmpty()) {
            failCapturePreparation(
                generation,
                "设备连接状态已变化，请重新连接后准备采集"
            )
            return
        }
        startBleService()
        _inRecordingMode.value = true
        recEngine.setup(devices)

        viewModelScope.launch {
            var attempts = 0
            while (
                attempts < 24 &&
                recEngine.recordingStates.value.size < devices.size
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

            val states = recEngine.recordingStates.value.values
            val allStatesKnown = states.size >= devices.size
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

            continueCapturePreparationAfterIdlePreflight(devices.size, generation)
        }
    }

    private fun continueCapturePreparationAfterIdlePreflight(
        connectedCount: Int,
        generation: Int
    ) {
        if (generation != capturePreflightGeneration) return
        if (connectedCount >= 2 && !isSynced.value) {
            if (engine.confirmExistingSyncIfAllConnected()) {
                _captureWorkflowPreparing.value = false
                return
            }
            val prepared = engine.prepareSyncParameters(_recOutputRate.value, _recFilterProfile.value)
            if (!prepared.success) {
                recEngine.clear()
                _inRecordingMode.value = false
                _captureWorkflowPreparing.value = false
                Toast.makeText(getApplication(), "设备参数未准备好，无法开始同步", Toast.LENGTH_SHORT).show()
                return
            }
            recEngine.clear()
            _inRecordingMode.value = false
            _capturePreparePending.value = true
            viewModelScope.launch {
                if (prepared.waitMsBeforeSync > 0L) delay(prepared.waitMsBeforeSync)
                if (
                    generation != capturePreflightGeneration ||
                    !_capturePreparePending.value
                ) {
                    return@launch
                }
                if (!engine.startSync()) {
                    failCapturePreparation(
                        generation,
                        "同步启动前设备连接已变化，请保持设备靠近后重新准备"
                    )
                    return@launch
                }

                // startSync() is synchronous up to publishing the SDK sync state. If neither
                // syncing nor synced becomes visible, release the preparation lock explicitly.
                delay(2_000)
                if (
                    generation == capturePreflightGeneration &&
                    _capturePreparePending.value &&
                    !isSyncing.value &&
                    !isSynced.value
                ) {
                    failCapturePreparation(
                        generation,
                        "设备同步未启动，请确认连接稳定后重新准备"
                    )
                }
            }
            return
        }

        if (!isSynced.value) {
            engine.prepareOfflineModeSettings(_recOutputRate.value, _recFilterProfile.value)
        }
        _captureWorkflowPreparing.value = false
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
        if (recEngine.recordingPhase.value != FlashRecordingPhase.Idle) return
        if (!recEngine.prepareStartRecording()) return
        if (!isSynced.value) {
            engine.startOfflineRecordingMeasurement(_recOutputRate.value, _recFilterProfile.value)
            viewModelScope.launch {
                delay(1_500)
                if (_inRecordingMode.value && recEngine.recordingPhase.value == FlashRecordingPhase.Starting) {
                    recEngine.startRecording()
                }
            }
        } else {
            if (!engine.startSyncedRecordingMeasurement()) {
                recEngine.forceStopRecording()
                Toast.makeText(getApplication(), "设备未全部连接，无法开始录制", Toast.LENGTH_SHORT).show()
                return
            }
            viewModelScope.launch {
                delay(800)
                if (_inRecordingMode.value && recEngine.recordingPhase.value == FlashRecordingPhase.Starting) {
                    recEngine.startRecording()
                }
            }
        }
    }
    fun stopFlashRecording() {
        if (recEngine.recordingPhase.value != FlashRecordingPhase.Recording) return
        recEngine.stopRecording()
    }
    fun forceStopFlashRecording() {
        if (recEngine.recordingPhase.value == FlashRecordingPhase.Idle) return
        recEngine.forceStopRecording()
    }
    fun requestFiles()        = recEngine.requestFileInfo()
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
        recEngine.clear()
        engine.close()
        stopBleService()
    }
}
