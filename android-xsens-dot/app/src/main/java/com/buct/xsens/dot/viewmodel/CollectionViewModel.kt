package com.buct.xsens.dot.viewmodel

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.buct.xsens.dot.data.CsvRecorder
import com.buct.xsens.dot.data.ScannedDevice
import com.buct.xsens.dot.data.SensorData
import com.buct.xsens.dot.engine.CollectionEngine
import com.buct.xsens.dot.engine.RecordingEngine
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val needsSync: StateFlow<Boolean> = engine.needsSync
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
    val recFileList        = recEngine.fileList
    val recExportProgress  = recEngine.exportProgress
    val recExportDone      = recEngine.exportDone
    val recLog             = recEngine.recordingLog
    val recSelectedExportIds = recEngine.selectedExportIds
    val recAllExportFields   = RecordingEngine.ALL_EXPORT_FIELDS

    fun setExportIds(ids: Set<Byte>) = recEngine.setSelectedExportIds(ids)

    // ── 离线录制模式设置（独立于实时采集设置）──
    private val _recOutputRate    = MutableStateFlow(120)   // 默认 120Hz
    private val _recFilterProfile = MutableStateFlow(1)     // 默认 Dynamic
    val recOutputRate:    StateFlow<Int> = _recOutputRate.asStateFlow()
    val recFilterProfile: StateFlow<Int> = _recFilterProfile.asStateFlow()
    fun setRecOutputRate(rate: Int) {
        _recOutputRate.value = rate
        engine.syncOutputRate = rate
        // 立即写入所有已连接设备（连接后且未同步时才允许修改，UI 已做限制）
        engine.setAllDevicesOutputRate(rate)
    }
    fun setRecFilterProfile(mode: Int) {
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

    private val _inRecordingMode = MutableStateFlow(false)
    val inRecordingMode: StateFlow<Boolean> = _inRecordingMode.asStateFlow()

    private val _headingActiveButton = MutableStateFlow("reset")
    val headingActiveButton: StateFlow<String> = _headingActiveButton.asStateFlow()

    private val _scanMessage = MutableStateFlow<String?>(null)
    val scanMessage: StateFlow<String?> = _scanMessage.asStateFlow()

    private val recordChannel = Channel<Pair<Int, SensorData>>(Channel.UNLIMITED)
    private val recorders = ConcurrentHashMap<Int, CsvRecorder>()
    private var writeCount = 0

    val isConnected: Boolean
        get() = engine.connectedDevices.value.isNotEmpty()

    init {
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
        _selectedForConnect.value = scannedDevices.value.indices.toSet()
    }

    fun deselectAll() {
        _selectedForConnect.value = emptySet()
    }

    fun connectSelected() {
        val list = scannedDevices.value
        val selected = _selectedForConnect.value.mapNotNull { list.getOrNull(it) }
        engine.connectDevices(selected)
    }

    fun disconnect() {
        stopRecording()
        if (_inRecordingMode.value) exitRecordingMode()
        engine.disconnectAll()
    }

    fun startMeasuring() {
        engine.startDirectMeasurement()
    }

    fun stopMeasuring() = engine.stopMeasuring()

    fun startSync() {
        // 同步前先停止所有采集状态，避免设备处于录制/测量中导致同步失败
        if (recEngine.recordingActive.value) {
            recEngine.stopRecording()
        }
        if (_isRecording.value) {
            _isRecording.value = false
        }
        // 采样率和滤波器由用户点击选择按钮时立即写入设备，此处只需记录目标值给重连恢复用
        engine.syncOutputRate = _recOutputRate.value
        engine.startSync()
    }
    fun stopSync() = engine.stopSync()
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
     * 进入离线采集模式：保持设备测量状态（Flash 录制需要 sensor fusion 持续运行），
     * 只停止 CSV 实时录制，然后初始化 RecordingEngine。
     */
    fun enterRecordingMode() {
        if (_inRecordingMode.value) return
        val devices = engine.getDevices()
        if (devices.isEmpty()) {
            Toast.makeText(getApplication(), "请先连接设备", Toast.LENGTH_SHORT).show()
            return
        }
        // 仅停止 CSV 录制，不停止 BLE 测量 —— 设备必须持续测量才能写 Flash
        stopRecording()
        _inRecordingMode.value = true
        // 采样率和滤波器已在同步前写入（syncOutputRate = recOutputRate），无需再次配置
        // 同步后固件锁定这两个参数，applyOfflineModeSettings 不再有效
        viewModelScope.launch {
            delay(400)  // 等待 GATT 队列空闲
            recEngine.setup(devices)
        }
    }

    /** 手动重试录制通知（首次启用失败时使用） */
    fun retryRecordingNotification() {
        val devices = engine.getDevices()
        if (devices.isNotEmpty()) {
            viewModelScope.launch {
                recEngine.setup(devices)
            }
        }
    }

    /** 退出离线采集模式，清理 RecordingEngine，恢复实时采集的 60Hz + 原有滤波档 */
    fun exitRecordingMode() {
        recEngine.clear()
        _inRecordingMode.value = false
        clearFileSelection()
        // 恢复实时采集参数（60Hz + 用户上次选择的实时滤波档）
        engine.applyOfflineModeSettings(60, engine.filterProfile.value)
    }

    fun requestFlashInfo()    = recEngine.requestFlashInfo()
    fun eraseFlash()          = recEngine.eraseAll()
    fun startFlashRecording() = recEngine.startRecording()
    fun stopFlashRecording() {
        recEngine.stopRecording()
        // 停止录制后延迟 1s 刷新 Flash 用量，确认数据已写入
        viewModelScope.launch {
            delay(1000)
            recEngine.requestFlashInfo()
        }
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
        val dir = app.getExternalFilesDir(null) ?: app.filesDir
        val saveDir = java.io.File(dir, "data_logging")
        saveDir.mkdirs()
        _recordingPath.value = saveDir.absolutePath
        addresses.forEachIndexed { index, address ->
            val recorder = CsvRecorder(app, index, address)
            recorder.start()
            recorders[index] = recorder
        }
        _isRecording.value = true
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
}
