package com.buct.xsens.dot.engine

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.buct.xsens.dot.data.LongJumpDeviceRoles
import com.buct.xsens.dot.data.SensorData
import com.buct.xsens.dot.data.ScannedDevice
import com.buct.xsens.dot.data.WaveData
import com.buct.xsens.dot.data.WaveSnapshot
import com.xsens.dot.android.sdk.events.DotData
import com.xsens.dot.android.sdk.interfaces.DotCiCallback
import com.xsens.dot.android.sdk.interfaces.DotDeviceCallback
import com.xsens.dot.android.sdk.interfaces.DotMeasurementCallback
import com.xsens.dot.android.sdk.interfaces.DotScannerCallback
import com.xsens.dot.android.sdk.models.DotDevice
import com.xsens.dot.android.sdk.models.DotPayload
import com.xsens.dot.android.sdk.models.DotSyncManager
import com.xsens.dot.android.sdk.interfaces.DotSyncCallback
import com.xsens.dot.android.sdk.models.FilterProfileInfo
import com.xsens.dot.android.sdk.utils.DotScanner
import android.bluetooth.le.ScanSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

data class DotBatteryStatus(
    val percentage: Int,
    val status: Int
)

data class DotFirmwareStatus(
    val version: String,
    val compatible: Boolean
)

data class SyncParameterPrepareResult(
    val success: Boolean,
    val wroteParameters: Boolean,
    val waitMsBeforeSync: Long
)

/**
 * Movella DOT 数据采集引擎 — 对标官方 App (DeviceManager + PlotsFragment)
 *
 * 与官方一致之处：
 *  - onDotDataChanged：在 BLE 回调线程直接处理（官方也不切线程，直接转 measurementDataCallback）
 *  - DotSyncManager.startSyncing(devices, 1025)：requestCode 对齐
 *  - SCAN_MODE_BALANCED、DotSdk 三个初始化调用、setOutputRate(60)：均对齐
 *  - onDotInitDone 中 _isSyncing 保护：避免与 DotSyncManager.readAck() GATT 冲突
 *  - setFilterProfile(General/Dynamic)：与官方 setSensorProfile 对齐
 *
 * 有意差异（针对当前场景的改进）：
 *  - onDotInitDone 含 setOutputRate 逻辑（官方在 ViewModel 层做，单类设计下移至此）
 *  - 同步成功后不自动采集：由用户手动选择实时采集或离线录制
 */
class CollectionEngine(private val context: Context) : DotDeviceCallback, DotMeasurementCallback, DotCiCallback, DotScannerCallback {

    companion object {
        private const val DIAG_TAG = "DOT_LINK_DIAG"
        private const val SYNC_TIMING_TAG = "DOT_SYNC_TIMING"
        private const val SYNCING_REQUEST_CODE = 1025
        /** BLE 实时波形流 ODR（与官方一致）；120Hz 仅同步+离线 Flash */
        private const val STREAM_OUTPUT_RATE_HZ = 60
        private const val RECONNECT_DEBOUNCE_MS = 3_000L
        // Android BLE GATT active reads must be serialized. Polling one of two DOTs every
        // 500 ms keeps each device near a 1 s refresh cadence without dropping callbacks.
        private const val RSSI_MONITOR_INTERVAL_MS = 500L
        private const val SYNC_RESULT_SETTLE_MS = 10_000L
        private const val SCAN_TIMEOUT_MS = 5_000L
        private const val BLUETOOTH_RESTART_RECONNECT_DELAY_MS = 1_500L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var sdkScanner: DotScanner? = null
    private var scanTimeoutRunnable: Runnable? = null
    @Volatile private var scanSessionId = 0
    @Volatile private var exportTransferActive = false
    @Volatile private var userRequestedDisconnect = false
    private var bluetoothReceiverRegistered = false
    private var connectionTargets = emptyList<ConnectionTarget>()
    private var bluetoothRestartReconnectRunnable: Runnable? = null
    @Volatile private var bluetoothRestartReconnectPending = false

    // ── 公开状态 ──
    private val _scannedDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scannedDevices: StateFlow<List<ScannedDevice>> = _scannedDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _connectedDevices = MutableStateFlow<List<String>>(emptyList())
    val connectedDevices: StateFlow<List<String>> = _connectedDevices.asStateFlow()

    private val _state = MutableStateFlow(CollectionState.Idle)
    val state: StateFlow<CollectionState> = _state.asStateFlow()

    private val _recvCount = MutableStateFlow(0L)
    val recvCount: StateFlow<Long> = _recvCount.asStateFlow()

    private val _sensorData = MutableStateFlow<Map<String, SensorData>>(emptyMap())
    val sensorData: StateFlow<Map<String, SensorData>> = _sensorData.asStateFlow()

    private val waveDataMap = ConcurrentHashMap<String, WaveData>()
    private val _waveData = MutableStateFlow<Map<String, WaveSnapshot>>(emptyMap())
    val waveData: StateFlow<Map<String, WaveSnapshot>> = _waveData.asStateFlow()

    private val _syncLog = MutableStateFlow<List<String>>(emptyList())
    val syncLog: StateFlow<List<String>> = _syncLog.asStateFlow()

    /** 设备断线后重新初始化完成时回调，参数为 BLE 地址（原始格式），供 ViewModel 恢复离线状态 */
    var onDeviceReconnected: ((address: String) -> Unit)? = null

    private val _isSynced = MutableStateFlow(false)
    val isSynced: StateFlow<Boolean> = _isSynced.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncProgress = MutableStateFlow(0)
    val syncProgress: StateFlow<Int> = _syncProgress.asStateFlow()

    private val _needsSync = MutableStateFlow(false)
    val needsSync: StateFlow<Boolean> = _needsSync.asStateFlow()

    private val _batteryStatus = MutableStateFlow<Map<String, DotBatteryStatus>>(emptyMap())
    val batteryStatus: StateFlow<Map<String, DotBatteryStatus>> = _batteryStatus.asStateFlow()

    private val _deviceRssi = MutableStateFlow<Map<String, Int>>(emptyMap())
    val deviceRssi: StateFlow<Map<String, Int>> = _deviceRssi.asStateFlow()

    private val _deviceRssiUpdatedAt = MutableStateFlow<Map<String, Long>>(emptyMap())
    val deviceRssiUpdatedAt: StateFlow<Map<String, Long>> = _deviceRssiUpdatedAt.asStateFlow()

    private val _deviceSyncStates = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val deviceSyncStates: StateFlow<Map<String, Boolean>> = _deviceSyncStates.asStateFlow()

    private val _firmwareStatus = MutableStateFlow<Map<String, DotFirmwareStatus>>(emptyMap())
    val firmwareStatus: StateFlow<Map<String, DotFirmwareStatus>> = _firmwareStatus.asStateFlow()

    private val _initProgress = MutableStateFlow(Pair(0, 0))
    val initProgress: StateFlow<Pair<Int, Int>> = _initProgress.asStateFlow()

    // 0 = General（默认），1 = Dynamic（与官方 setSensorProfile 入参含义一致）
    private val _filterProfile = MutableStateFlow(0)
    val filterProfile: StateFlow<Int> = _filterProfile.asStateFlow()

    // ── 内部状态 ──
    private val devices       = CopyOnWriteArrayList<DotDevice>()
    private val addressToIndex = ConcurrentHashMap<String, Int>()
    private val initDoneAddresses = mutableSetOf<String>()
    private val initializedOnceAddresses = ConcurrentHashMap.newKeySet<String>()
    private val reconnectPendingAddresses = ConcurrentHashMap.newKeySet<String>()
    private val lastReconnectHandledAt = ConcurrentHashMap<String, Long>()
    private val outOfRangeLoggedAddresses = ConcurrentHashMap.newKeySet<String>()
    private val lastInitDoneAtMs = ConcurrentHashMap<String, Long>()

    // 地址映射缓存：connectDevices() 写入一次，之后只读（BLE 线程安全）
    private val addrToIdx = HashMap<String, Int>()
    private val addrToNorm = HashMap<String, String>()
    // BLE 多线程并发写入，需 ConcurrentHashMap
    private val lastPktCounter = ConcurrentHashMap<String, Int>()

    @Volatile private var connectSessionId  = 0
    @Volatile private var desiredMode       = DotPayload.PAYLOAD_TYPE_CUSTOM_MODE_1
    @Volatile private var targetDeviceCount = 0
    @Volatile private var measurementStarted = false
    private val totalRecvCount    = java.util.concurrent.atomic.AtomicLong(0L)
    private val lastUiUpdateAtom  = java.util.concurrent.atomic.AtomicLong(0L)

    private val pendingSensorData = ConcurrentHashMap<String, SensorData>()
    private var syncManager: DotSyncManager? = null
    @Volatile private var syncEpoch = 0
    @Volatile private var syncSessionStartedAtMs = 0L
    @Volatile private var syncAttemptStartedAtMs = 0L
    @Volatile private var syncConfirmedAtMs = 0L
    // 同步/离线录制目标采样率，与 UI 默认值保持一致（120Hz），startSync 前由 ViewModel 覆写
    @Volatile var syncOutputRate = 120
    @Volatile private var backgroundReadsPaused = false
    private var syncTimeoutRunnable: Runnable? = null
    private var onDataCallback: ((Int, SensorData) -> Unit)? = null
    @Volatile private var lastHeadingAction = "reset"
    private var lastFirmwareSummaryKey: String? = null

    private data class ConnectionTarget(
        val address: String,
        val name: String,
        val rssi: Int
    )

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_OFF -> {
                    bluetoothRestartReconnectRunnable?.let(mainHandler::removeCallbacks)
                    bluetoothRestartReconnectRunnable = null
                    bluetoothRestartReconnectPending = false
                }
                BluetoothAdapter.STATE_ON -> scheduleBluetoothRestartReconnect()
            }
        }
    }

    init {
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    bluetoothStateReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(bluetoothStateReceiver, filter)
            }
            bluetoothReceiverRegistered = true
        } catch (error: Exception) {
            Log.w(DIAG_TAG, "Bluetooth state receiver registration failed", error)
        }
    }

    // ── 工具 ──

    fun setOnDataCallback(cb: (Int, SensorData) -> Unit) { onDataCallback = cb }

    private fun normalizeAddress(addr: String): String =
        addr.replace(":", "").replace("-", "").uppercase()

    private fun isBluetoothEnabled(): Boolean =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter
            ?.isEnabled == true

    private fun connectionStateLabel(state: Int): String =
        when (state) {
            DotDevice.CONN_STATE_CONNECTED -> "CONNECTED"
            DotDevice.CONN_STATE_START_RECONNECTING -> "START_RECONNECTING"
            DotDevice.CONN_STATE_RECONNECTING -> "RECONNECTING"
            DotDevice.CONN_STATE_DISCONNECTED -> "DISCONNECTED"
            else -> "UNKNOWN($state)"
        }

    private fun logLinkDiag(address: String, message: String) {
        Log.i(DIAG_TAG, "[${normalizeAddress(address).takeLast(4)}] $message")
    }

    private fun scheduleBluetoothRestartReconnect() {
        bluetoothRestartReconnectRunnable?.let(mainHandler::removeCallbacks)
        if (userRequestedDisconnect || connectionTargets.isEmpty()) return
        bluetoothRestartReconnectRunnable = Runnable {
            bluetoothRestartReconnectRunnable = null
            if (
                userRequestedDisconnect ||
                !isBluetoothEnabled() ||
                connectionTargets.isEmpty() ||
                _connectedDevices.value.size >= connectionTargets.size
            ) {
                return@Runnable
            }
            bluetoothRestartReconnectPending = true
            appendSyncLog("蓝牙已恢复，正在自动查找 ${connectionTargets.size} 台设备")
            Log.i(DIAG_TAG, "Bluetooth restored: scanning for ${connectionTargets.size} DOT devices")
            startScan()
            bluetoothRestartReconnectRunnable = Runnable {
                bluetoothRestartReconnectRunnable = null
                if (
                    bluetoothRestartReconnectPending &&
                    !userRequestedDisconnect &&
                    isBluetoothEnabled() &&
                    _connectedDevices.value.size < connectionTargets.size
                ) {
                    scheduleBluetoothRestartReconnect()
                }
            }
            mainHandler.postDelayed(
                bluetoothRestartReconnectRunnable!!,
                SCAN_TIMEOUT_MS + 500L
            )
        }
        mainHandler.postDelayed(
            bluetoothRestartReconnectRunnable!!,
            BLUETOOTH_RESTART_RECONNECT_DELAY_MS
        )
    }

    private fun tryCompleteBluetoothRestartReconnect() {
        if (!bluetoothRestartReconnectPending || connectionTargets.isEmpty()) return
        if (_connectedDevices.value.size >= connectionTargets.size) {
            bluetoothRestartReconnectPending = false
            bluetoothRestartReconnectRunnable?.let(mainHandler::removeCallbacks)
            bluetoothRestartReconnectRunnable = null
            stopScan()
            return
        }
        val scannedByAddress = _scannedDevices.value.associateBy {
            normalizeAddress(it.address)
        }
        val targets = connectionTargets.mapNotNull { target ->
            scannedByAddress[normalizeAddress(target.address)]
        }
        if (targets.size != connectionTargets.size) return

        bluetoothRestartReconnectPending = false
        bluetoothRestartReconnectRunnable?.let(mainHandler::removeCallbacks)
        bluetoothRestartReconnectRunnable = null
        stopScan()
        appendSyncLog("已找到本次设备，正在自动重连")
        Log.i(DIAG_TAG, "Reconnect scan complete: rebuilding ${targets.size} DOT connections")
        connectDevices(targets.sortedBy { LongJumpDeviceRoles.roleSortIndex(it.address) })
    }

    private fun resolveDeviceIndex(address: String): Int? {
        val norm = normalizeAddress(address)
        addressToIndex[norm]?.let { return it }
        val idx = devices.indexOfFirst { normalizeAddress(it.address ?: "") == norm }
        if (idx >= 0) { addressToIndex[norm] = idx; return idx }
        return null
    }

    private fun initializedDevices(): List<DotDevice> =
        devices.filter { normalizeAddress(it.address ?: "") in initDoneAddresses }

    private fun refreshDeviceSyncStates() {
        _deviceSyncStates.value = devices
            .mapNotNull { dev ->
                val addr = dev.address ?: return@mapNotNull null
                normalizeAddress(addr) to dev.isSynced
            }
            .toMap()
    }

    private fun refreshGlobalSyncFromConnectedDevices() {
        if (_isSyncing.value) return
        val connected = devices.filter { dev ->
            dev.connectionState == DotDevice.CONN_STATE_CONNECTED &&
                !dev.address.isNullOrBlank()
        }
        if (connected.size < 2) return

        val allConnectedSynced = connected.all { it.isSynced }
        if (allConnectedSynced) {
            _needsSync.value = false
            _isSynced.value = true
        } else if (_isSynced.value) {
            _isSynced.value = false
            _needsSync.value = true
        }
    }

    private fun refreshConnectedDevices(excludeNormAddr: String? = null) {
        _connectedDevices.value = devices.mapNotNull { dev ->
            val addr = dev.address?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val norm = normalizeAddress(addr)
            addr.takeIf {
                norm != excludeNormAddr &&
                    dev.connectionState == DotDevice.CONN_STATE_CONNECTED
            }
        }
    }

    private fun clearRssi(normAddr: String) {
        _deviceRssi.value = _deviceRssi.value.toMutableMap().also {
            it.remove(normAddr)
        }
        _deviceRssiUpdatedAt.value = _deviceRssiUpdatedAt.value.toMutableMap().also {
            it.remove(normAddr)
        }
    }

    private fun startRssiMonitoring() {
        if (backgroundReadsPaused || _isSyncing.value) return
        mainHandler.removeCallbacks(rssiMonitorRunnable)
        mainHandler.postDelayed(rssiMonitorRunnable, RSSI_MONITOR_INTERVAL_MS)
    }

    private fun stopRssiMonitoring() {
        mainHandler.removeCallbacks(rssiMonitorRunnable)
        rssiMonitorIndex = 0
    }

    private fun pauseBackgroundReads() {
        backgroundReadsPaused = true
        stopRssiMonitoring()
    }

    private fun resumeBackgroundReads() {
        if (exportTransferActive || _isSyncing.value) {
            backgroundReadsPaused = true
            stopRssiMonitoring()
            return
        }
        backgroundReadsPaused = false
        if (devices.any { it.connectionState == DotDevice.CONN_STATE_CONNECTED }) {
            startRssiMonitoring()
        }
    }

    /**
     * Flash 导出依赖持续的 BLE notification。导出期间暂停 RSSI/电量等主动 GATT 读取，
     * 避免与 DotRecordingManager 的数据通道争用；全部文件结束后再恢复。
     */
    fun setExportInProgress(active: Boolean) {
        if (exportTransferActive == active) return
        exportTransferActive = active
        if (active) {
            pauseBackgroundReads()
            Log.i(DIAG_TAG, "Flash export active: background BLE reads paused")
        } else {
            resumeBackgroundReads()
            Log.i(DIAG_TAG, "Flash export finished: background BLE reads resumed")
        }
    }

    private fun markReconnectPending(normAddr: String) {
        if (normAddr !in initializedOnceAddresses) return
        initDoneAddresses.remove(normAddr)
        reconnectPendingAddresses.add(normAddr)
    }

    private fun consumeReconnectPending(normAddr: String): Boolean {
        if (normAddr !in initializedOnceAddresses) {
            reconnectPendingAddresses.remove(normAddr)
            return false
        }
        if (!reconnectPendingAddresses.remove(normAddr)) return false

        val now = System.currentTimeMillis()
        val lastHandledAt = lastReconnectHandledAt[normAddr] ?: 0L
        if (now - lastHandledAt < RECONNECT_DEBOUNCE_MS) return false
        lastReconnectHandledAt[normAddr] = now
        return true
    }

    private fun updateFirmwareStatus(normAddr: String, version: String?, compatible: Boolean) {
        val cleanVersion = version?.takeIf { it.isNotBlank() } ?: "未知"
        _firmwareStatus.value = _firmwareStatus.value.toMutableMap().also {
            it[normAddr] = DotFirmwareStatus(version = cleanVersion, compatible = compatible)
        }
        appendFirmwareSummaryIfReady()
    }

    private fun appendFirmwareSummaryIfReady() {
        if (targetDeviceCount <= 0 || _firmwareStatus.value.size < targetDeviceCount) return
        val entries = _firmwareStatus.value.entries.sortedBy { it.key }
        if (entries.any { it.value.version == "未知" }) return
        val summaryKey = entries.joinToString("|") { "${it.key}:${it.value.version}:${it.value.compatible}" }
        if (summaryKey == lastFirmwareSummaryKey) return
        lastFirmwareSummaryKey = summaryKey

        val versions = entries.map { it.value.version }.toSet()
        val hasIncompatible = entries.any { !it.value.compatible }
        val summary = entries.joinToString("，") { (addr, status) ->
            "${addr.takeLast(4)}=${status.version}${if (status.compatible) "" else "(不兼容)"}"
        }
        when {
            hasIncompatible -> appendSyncLog("固件检查：存在不兼容设备，$summary")
            versions.size == 1 -> appendSyncLog("固件检查：版本一致 ${versions.first()}")
            else -> appendSyncLog("固件检查：版本不一致，$summary")
        }
    }

    private fun appendSyncLog(msg: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        _syncLog.value = _syncLog.value + "[$t] $msg"
    }

    private fun logSyncTiming(message: String) {
        val now = SystemClock.elapsedRealtime()
        val total = if (syncSessionStartedAtMs > 0L) now - syncSessionStartedAtMs else 0L
        val attempt = if (syncAttemptStartedAtMs > 0L) now - syncAttemptStartedAtMs else 0L
        Log.i(SYNC_TIMING_TAG, "t+${total}ms attempt+${attempt}ms $message")
    }

    // ── 扫描 ──

    @Suppress("MissingPermission")
    fun startScan() {
        scanTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        scanTimeoutRunnable = null
        sdkScanner?.stopScan()
        _scannedDevices.value = emptyList()
        _isScanning.value = false
        _state.value = CollectionState.Scanning

        sdkScanner = DotScanner(context, this).also {
            it.setScanMode(ScanSettings.SCAN_MODE_BALANCED)
        }
        val session = ++scanSessionId
        _isScanning.value = sdkScanner?.startScan() == true
        if (_isScanning.value) {
            scanTimeoutRunnable = Runnable {
                if (session == scanSessionId) stopScan()
            }
            mainHandler.postDelayed(scanTimeoutRunnable!!, SCAN_TIMEOUT_MS)
        } else {
            _state.value = CollectionState.Idle
        }
    }

    fun stopScan() {
        scanSessionId++
        scanTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        scanTimeoutRunnable = null
        sdkScanner?.stopScan()
        _isScanning.value = false
        if (_state.value == CollectionState.Scanning) {
            _state.value = CollectionState.Idle
        }
    }

    @Suppress("MissingPermission")
    override fun onDotScanned(device: BluetoothDevice?, rssi: Int) {
        val dev = device ?: return
        mainHandler.post {
            val name = dev.name?.takeIf { it.isNotBlank() } ?: return@post
            if (!LongJumpDeviceRoles.isTargetDevice(dev.address)) return@post
            val list = _scannedDevices.value.toMutableList()
            val index = list.indexOfFirst { normalizeAddress(it.address) == normalizeAddress(dev.address) }
            if (index >= 0) {
                list[index] = list[index].copy(name = name, rssi = rssi)
                _scannedDevices.value = list.sortedBy { LongJumpDeviceRoles.roleSortIndex(it.address) }
            } else {
                list.add(ScannedDevice(dev, name, dev.address, rssi))
                _scannedDevices.value = list.sortedBy { LongJumpDeviceRoles.roleSortIndex(it.address) }
            }
            tryCompleteBluetoothRestartReconnect()
        }
    }

    // ── 连接 ──

    fun connectDevices(selected: List<ScannedDevice>) {
        if (selected.isEmpty()) return
        bluetoothRestartReconnectPending = false
        bluetoothRestartReconnectRunnable?.let(mainHandler::removeCallbacks)
        bluetoothRestartReconnectRunnable = null
        stopScan()
        userRequestedDisconnect = false
        connectionTargets = selected.map {
            ConnectionTarget(address = it.address, name = it.name, rssi = it.rssi)
        }
        devices.forEach { device ->
            try {
                device.cancelReconnecting()
                device.setDotCiCallback(null)
                device.setDotMeasurementCallback(null)
                device.setDotDeviceCallback(null)
                device.disconnect()
            } catch (_: Exception) {
                // Bluetooth service restarts can leave the previous GATT object unusable.
            }
        }
        connectSessionId++
        targetDeviceCount   = selected.size
        measurementStarted  = false
        _needsSync.value    = selected.size > 1
        _state.value        = CollectionState.Connecting
        devices.clear(); addressToIndex.clear(); initDoneAddresses.clear(); initializedOnceAddresses.clear()
        reconnectPendingAddresses.clear(); lastReconnectHandledAt.clear()
        lastFirmwareSummaryKey = null
        addrToIdx.clear(); addrToNorm.clear(); lastPktCounter.clear()
        // syncOutputRate 保留当前 ViewModel 设定值（默认 120），不在此重置
        _initProgress.value = Pair(0, selected.size)

        selected.forEachIndexed { i, scanned ->
            val xsDev = DotDevice(context, scanned.device, this)
            xsDev.setDotCiCallback(this)
            xsDev.setDotMeasurementCallback(this)
            devices.add(xsDev)
            val norm = normalizeAddress(scanned.address)
            addressToIndex[norm] = i
            addrToIdx[scanned.address] = i
            addrToNorm[scanned.address] = norm
        }

        val sid = connectSessionId
        selected.forEachIndexed { i, _ ->
            mainHandler.postDelayed({
                if (sid == connectSessionId) devices.getOrNull(i)?.connect()
            }, i * 300L)
        }

        // 20 秒后检查是否所有设备都完成初始化
        mainHandler.postDelayed({
            if (sid != connectSessionId) return@postDelayed
            val missing = targetDeviceCount - initDoneAddresses.size
            if (missing > 0) {
                appendSyncLog("连接超时：有 $missing 台设备未就绪，建议断开后扫描连接")
            }
        }, 20_000L)
    }

    fun disconnectAll() {
        userRequestedDisconnect = true
        connectionTargets = emptyList()
        bluetoothRestartReconnectRunnable?.let(mainHandler::removeCallbacks)
        bluetoothRestartReconnectRunnable = null
        bluetoothRestartReconnectPending = false
        connectSessionId++; syncEpoch++
        exportTransferActive = false
        stopLossReporting()
        stopRssiMonitoring()
        cancelSyncTimeout()
        requestStopSyncing(logSummary = false)

        val targets = devices.toList()
        targets.forEach { it.stopMeasuring() }
        mainHandler.postDelayed({ targets.forEach { it.disconnect() } }, 300)

        devices.clear(); addressToIndex.clear(); initDoneAddresses.clear(); initializedOnceAddresses.clear()
        reconnectPendingAddresses.clear(); lastReconnectHandledAt.clear()
        outOfRangeLoggedAddresses.clear()
        addrToIdx.clear(); addrToNorm.clear(); lastPktCounter.clear()
        targetDeviceCount = 0; measurementStarted = false
        _initProgress.value    = Pair(0, 0)
        _connectedDevices.value = emptyList()
        _state.value           = CollectionState.Idle
        _isSynced.value        = false
        _isSyncing.value       = false
        _syncProgress.value    = 0
        _needsSync.value       = false
        _recvCount.value       = 0
        _batteryStatus.value   = emptyMap()
        _deviceRssi.value      = emptyMap()
        _deviceSyncStates.value = emptyMap()
        syncConfirmedAtMs = 0L
        _firmwareStatus.value  = emptyMap()
        lastFirmwareSummaryKey = null
        totalRecvCount.set(0L)
        lastUiUpdateAtom.set(0L)
        pendingSensorData.clear()
        lossStats.clear(); recvStats.clear()
        _sensorData.value      = emptyMap()
        waveDataMap.clear()
        _waveData.value        = emptyMap()
    }

    fun close() {
        disconnectAll()
        if (bluetoothReceiverRegistered) {
            try {
                context.unregisterReceiver(bluetoothStateReceiver)
            } catch (_: IllegalArgumentException) {
                // Receiver was already removed with the process context.
            }
            bluetoothReceiverRegistered = false
        }
    }

    fun powerOffDevice(address: String): Boolean {
        val norm = normalizeAddress(address)
        val dev = devices.firstOrNull { normalizeAddress(it.address ?: "") == norm }
        if (dev == null) {
            appendSyncLog("[$address] 未找到已连接设备，无法关机")
            return false
        }
        if (dev.connectionState != DotDevice.CONN_STATE_CONNECTED) {
            appendSyncLog("[${dev.address ?: address}] 设备未连接，无法关机")
            return false
        }
        val sent = dev.powerOffDevice()
        appendSyncLog("[${dev.address ?: address}] ${if (sent) "已发送设备关机指令" else "设备关机指令发送失败"}")
        return sent
    }

    // ── SDK 设备回调 ──

    override fun onDotConnectionChanged(address: String?, state: Int) {
        mainHandler.post {
            val addr = address ?: return@post
            val normForLog = normalizeAddress(addr)
            logLinkDiag(
                addr,
                "connection=${connectionStateLabel(state)} initialized=${normForLog in initializedOnceAddresses} " +
                    "initDone=${normForLog in initDoneAddresses} measuring=$measurementStarted synced=${_isSynced.value}"
            )
            when (state) {
                DotDevice.CONN_STATE_CONNECTED -> {
                    resolveDeviceIndex(addr)
                    val norm = normalizeAddress(addr)
                    outOfRangeLoggedAddresses.remove(norm)
                    if (addrToIdx[addr] == null) {
                        val idx = addressToIndex[norm]
                        if (idx != null) {
                            addrToIdx[addr] = idx
                            addrToNorm[addr] = norm
                        }
                    }
                    refreshConnectedDevices()
                    refreshDeviceSyncStates()
                    if (!_isSyncing.value) {
                        devices.firstOrNull { normalizeAddress(it.address ?: "") == norm }?.readRssi()
                        startRssiMonitoring()
                    }
                    if (!measurementStarted) _state.value = CollectionState.Connecting
                }
                DotDevice.CONN_STATE_START_RECONNECTING,
                DotDevice.CONN_STATE_RECONNECTING -> {
                    val norm = normalizeAddress(addr)
                    if (_isSyncing.value && !isBluetoothEnabled()) {
                        abortActiveSync("蓝牙已关闭，同步已取消")
                    }
                    markReconnectPending(norm)
                    clearRssi(norm)
                    refreshConnectedDevices(excludeNormAddr = norm)
                    refreshDeviceSyncStates()
                    _initProgress.value = Pair(initDoneAddresses.size, targetDeviceCount)
                    if (outOfRangeLoggedAddresses.add(norm)) {
                        appendSyncLog("[$addr] 设备超距，等待回连")
                    }
                }
                DotDevice.CONN_STATE_DISCONNECTED -> {
                    if (_isSyncing.value) {
                        abortActiveSync("[$addr] 设备断开，同步已取消")
                    }
                    val norm = normalizeAddress(addr)
                    markReconnectPending(norm)
                    clearRssi(norm)
                    refreshConnectedDevices(excludeNormAddr = norm)
                    refreshDeviceSyncStates()
                    _initProgress.value = Pair(initDoneAddresses.size, targetDeviceCount)
                    if (outOfRangeLoggedAddresses.add(norm)) {
                        appendSyncLog("[$addr] 设备断开，等待回连")
                    }
                    if (devices.none { it.connectionState == DotDevice.CONN_STATE_CONNECTED })
                        _state.value = CollectionState.Idle
                }
            }
        }
    }

    override fun onDotServicesDiscovered(address: String?, status: Int) {}
    override fun onDotFirmwareVersionRead(address: String?, version: String?) {
        val addr = address ?: return
        val norm = normalizeAddress(addr)
        mainHandler.post {
            val dev = devices.firstOrNull { normalizeAddress(it.address ?: "") == norm }
            updateFirmwareStatus(norm, version, dev?.isCompatibleFirmwareVersion ?: true)
        }
    }
    override fun onDotTagChanged(address: String?, tag: String?) {}
    override fun onDotBatteryChanged(address: String?, status: Int, percentage: Int) {
        val addr = address ?: return
        val norm = normalizeAddress(addr)
        mainHandler.post {
            _batteryStatus.value = _batteryStatus.value.toMutableMap().also {
                it[norm] = DotBatteryStatus(percentage = percentage, status = status)
            }
        }
    }
    override fun onDotButtonClicked(address: String?, timestamp: Long) {}
    override fun onDotButtonDoubleClicked(address: String?, timestamp: Long) {}
    override fun onDotButtonTripleClicked(address: String?, timestamp: Long) {}
    override fun onDotPowerSavingTriggered(address: String?) {
        val addr = address ?: return
        mainHandler.post {
            logLinkDiag(addr, "power-saving-triggered")
            val norm = normalizeAddress(addr)
            initDoneAddresses.remove(norm)
            reconnectPendingAddresses.remove(norm)
            refreshConnectedDevices(excludeNormAddr = norm)
            _initProgress.value = Pair(initDoneAddresses.size, targetDeviceCount)
            appendSyncLog("[$addr] 设备进入省电/关机，需按键唤醒后重新连接")
        }
    }
    override fun onReadRemoteRssi(address: String?, rssi: Int) {
        val addr = address ?: return
        mainHandler.post {
            _scannedDevices.value = _scannedDevices.value.map { scanned ->
                if (normalizeAddress(scanned.address) == normalizeAddress(addr)) {
                    scanned.copy(rssi = rssi)
                } else {
                    scanned
                }
            }
            _deviceRssi.value = _deviceRssi.value.toMutableMap().also {
                it[normalizeAddress(addr)] = rssi
            }
            _deviceRssiUpdatedAt.value = _deviceRssiUpdatedAt.value.toMutableMap().also {
                it[normalizeAddress(addr)] = SystemClock.elapsedRealtime()
            }
        }
    }
    override fun onDotOutputRateUpdate(address: String?, outputRate: Int) {}
    override fun onDotFilterProfileUpdate(address: String?, filterProfileIndex: Int) {}
    override fun onDotGetFilterProfileInfo(address: String, filterProfileInfoList: ArrayList<FilterProfileInfo>) {
        // SDK 已在 DotDevice.filterProfileInfoList 内部缓存，无需我们额外存储
    }

    override fun onDotInitDone(address: String?) {
        mainHandler.post {
            val addr     = address ?: return@post
            val normAddr = normalizeAddress(addr)
            resolveDeviceIndex(addr) ?: return@post
            val dev = devices.firstOrNull { normalizeAddress(it.address ?: "") == normAddr } ?: return@post
            val isFirstReady = normAddr !in initDoneAddresses
            val isFirstInitInSession = normAddr !in initializedOnceAddresses
            val isReconnect = consumeReconnectPending(normAddr)
            val shouldHandleReconnect = isReconnect

            // 必须在任何BLE操作之前检查：DotSyncManager的readAck在30ms后也排队到主线程
            // 若此处发起GATT write，会占用GATT导致readAck失败→同步75%失败
            if (_isSyncing.value) return@post

            // 部分固件会在普通 GATT 读取后重复上报 initDone。重复回调不能再次触发
            // 电量、RSSI、采样率等 BLE 操作，否则会形成 initDone -> read -> initDone 循环。
            if (!isFirstReady && !isReconnect) return@post

            lastInitDoneAtMs[normAddr] = SystemClock.elapsedRealtime()
            logLinkDiag(
                addr,
                "initDone firstReady=$isFirstReady firstSession=$isFirstInitInSession reconnect=$isReconnect " +
                    "conn=${connectionStateLabel(dev.connectionState)} measuring=$measurementStarted synced=${_isSynced.value} " +
                    "rate=${dev.currentOutputRate} firmware=${dev.firmwareVersion ?: "unknown"}"
            )
            outOfRangeLoggedAddresses.remove(normAddr)
            initDoneAddresses.add(normAddr)
            initializedOnceAddresses.add(normAddr)
            _initProgress.value = Pair(initDoneAddresses.size, targetDeviceCount)
            refreshDeviceSyncStates()
            updateFirmwareStatus(normAddr, dev.firmwareVersion, dev.isCompatibleFirmwareVersion)
            val batteryReadSession = connectSessionId
            mainHandler.postDelayed({
                if (batteryReadSession == connectSessionId && !backgroundReadsPaused && !_isSyncing.value) {
                    dev.readBattery()
                    dev.readRssi()
                }
            }, 350L)

            if (targetDeviceCount == 1) {
                val wasActive = measurementStarted  // 重连场景下为 true
                val resumeRate = if (wasActive && syncOutputRate == 120) syncOutputRate else STREAM_OUTPUT_RATE_HZ
                dev.setOutputRate(if (wasActive) resumeRate else syncOutputRate)
                dev.measurementMode = desiredMode
                mainHandler.postDelayed({
                    if (wasActive) {
                        if (!shouldHandleReconnect) return@postDelayed
                        if (dev.startMeasuring()) {
                            lastReconnectHandledAt[normAddr] = System.currentTimeMillis()
                            startLossReporting()
                            _state.value = CollectionState.Measuring
                            appendSyncLog("[$addr] 重新连接，测量已恢复")
                            onDeviceReconnected?.invoke(addr)
                        } else {
                            appendSyncLog("[$addr] 启动测量失败，请断开重连")
                        }
                    } else {
                        // 离线模式断联重连，不再强制启动测量
                        if (isReconnect) {
                            appendSyncLog("[$addr] 已恢复原设定采样率 (${syncOutputRate}Hz)")
                            onDeviceReconnected?.invoke(addr)
                        }
                    }
                }, 2000)
            } else {
                if (measurementStarted) {
                    if (!shouldHandleReconnect) return@post
                    if (_isSynced.value) {
                        appendSyncLog("[$addr] 重新连接，保持 SDK 同步测量状态")
                    } else {
                        // 此分支：其他设备仍在测量中，仅对刚重连的设备独立恢复
                        appendSyncLog("[$addr] 重新连接，恢复测量…")
                        dev.setOutputRate(STREAM_OUTPUT_RATE_HZ)
                        dev.measurementMode = desiredMode
                        dev.startMeasuring()
                    }
                    onDeviceReconnected?.invoke(addr)
                } else {
                    // 离线模式 / 同步后断联重连，恢复预设的输出刷新率 (如 120Hz)
                    dev.setOutputRate(syncOutputRate)
                    dev.measurementMode = desiredMode
                    if (isReconnect) {
                        onDeviceReconnected?.invoke(addr)
                    }
                    
                    if (isFirstInitInSession && initDoneAddresses.size == targetDeviceCount && !_isSynced.value) {
                        // 全部设备就绪且还未同步：统一应用当前目标采样率，确保各设备 ODR 一致
                        devices.forEach { d -> d.setOutputRate(syncOutputRate) }
                        mainHandler.postDelayed({
                            devices.forEach { d ->
                                appendSyncLog("[${d.address}] rate=${d.currentOutputRate}Hz | filter=${d.currentFilterProfileIndex}")
                            }
                            appendSyncLog("全部 $targetDeviceCount 台设备已就绪，请选择「SDK 硬件同步」")
                        }, 300)
                    } else if (isReconnect && _isSynced.value) {
                        if (!dev.isSynced) {
                            _isSynced.value = false
                            appendSyncLog("[$addr] 重新连接但遭遇【硬件同步丢失】，请重新执行 SDK 同步！")
                        } else {
                            appendSyncLog("[$addr] 已恢复设定采样率 (${syncOutputRate}Hz) 且硬件同步状态完好 ✓")
                        }
                    }
                }
            }
        }
    }

    /**
     * 官方 §4.9.3："Once the sync succeeds, sensor will enter measurement mode."
     * 参数（outputRate/mode/filter）已在 onDotInitDone 中配置，同步后不可更改，此处仅 startMeasuring。
     */
    private fun startMeasuringAfterSync(targetDevices: List<DotDevice>) {
        if (targetDevices.isEmpty()) return
        val sid = connectSessionId
        mainHandler.postDelayed({
            if (sid != connectSessionId) return@postDelayed
            targetDevices.firstOrNull()?.isRootDevice = false
            targetDevices.forEach { dev -> dev.measurementMode = desiredMode }
            targetDevices.forEach { dev -> dev.startMeasuring() }
            measurementStarted = true
            startLossReporting()
            _state.value = CollectionState.Measuring
            appendSyncLog("测量已启动（${targetDevices.size} 台设备）")
        }, 500)
    }

    private fun applyModeAndStart(targetDevices: List<DotDevice> = initializedDevices()) {
        if (targetDevices.isEmpty()) return
        val sid = connectSessionId
        mainHandler.post {
            if (sid != connectSessionId) return@post
            // 与官方一致：仅在已测量时才发 stopMeasuring，避免向从未启动测量的设备发无效 GATT 写
            if (measurementStarted) targetDevices.forEach { it.stopMeasuring() }
            // 实时 BLE 流式采集固定 ODR（与官方一致）；离线/同步仍可用 syncOutputRate=120
            targetDevices.forEach { dev -> dev.setOutputRate(STREAM_OUTPUT_RATE_HZ) }
            targetDevices.forEach { dev -> dev.measurementMode = desiredMode }
            mainHandler.postDelayed({
                if (sid != connectSessionId) return@postDelayed
                val currentProfile = _filterProfile.value
                targetDevices.forEach { dev -> applyFilterProfileToDevice(dev, currentProfile) }
                targetDevices.forEach { dev -> dev.startMeasuring() }
                measurementStarted = true
                startLossReporting()
                if (_state.value != CollectionState.Recording) _state.value = CollectionState.Measuring
            }, 2000)
        }
    }

    override fun onSyncStatusUpdate(address: String?, isSynced: Boolean) {
        val addr = address ?: return
        val norm = normalizeAddress(addr)
        mainHandler.post {
            if (
                !isSynced &&
                _isSynced.value &&
                syncConfirmedAtMs > 0L &&
                SystemClock.elapsedRealtime() - syncConfirmedAtMs < SYNC_RESULT_SETTLE_MS
            ) {
                logLinkDiag(addr, "ignored stale sync-status=false after successful sync")
                return@post
            }
            _deviceSyncStates.value = _deviceSyncStates.value.toMutableMap().also {
                it[norm] = isSynced
            }
            if (!isSynced && _isSynced.value) {
                _isSynced.value = false
                _needsSync.value = true
            } else {
                refreshGlobalSyncFromConnectedDevices()
            }
        }
    }

    // ── 数据流 ──
    // 与官方 DeviceManager.onDotDataChanged 完全一致：BLE 回调线程只做数据转发/解析，
    // 不做 Log、不做 IPC，确保回调尽快返回，避免 BLE 通知队列拥堵导致丢帧。

    // 丢包统计（内存累积，主线程定期汇报，绝不在 BLE 线程做 Log.e）
    private val lossStats = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    private val recvStats = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    private val lossReportRunnable = object : Runnable {
        override fun run() {
            if (!measurementStarted) return
            val sb = StringBuilder()
            for ((addr, lost) in lossStats) {
                val lostVal = lost.getAndSet(0)
                val recvVal = recvStats[addr]?.getAndSet(0) ?: 0
                if (lostVal > 0 || recvVal > 0) {
                    sb.append("[$addr] recv=$recvVal lost=$lostVal  ")
                }
            }
            if (sb.isNotEmpty()) Log.w("BLE_STATS", sb.toString().trim())
            mainHandler.postDelayed(this, 5000)
        }
    }

    private var rssiMonitorIndex = 0

    private val rssiMonitorRunnable = object : Runnable {
        override fun run() {
            if (backgroundReadsPaused || _isSyncing.value) return
            val connected = devices
                .filter { it.connectionState == DotDevice.CONN_STATE_CONNECTED }
                .sortedBy { normalizeAddress(it.address.orEmpty()) }
            if (connected.isEmpty()) return
            if (rssiMonitorIndex >= connected.size) rssiMonitorIndex = 0
            connected[rssiMonitorIndex].readRssi()
            rssiMonitorIndex = (rssiMonitorIndex + 1) % connected.size
            mainHandler.postDelayed(this, RSSI_MONITOR_INTERVAL_MS)
        }
    }

    override fun onDotDataChanged(address: String?, data: DotData?) {
        if (address == null || data == null) return
        processData(address, data)
    }

    private fun processData(address: String, data: DotData) {
        val idx = addrToIdx[address] ?: return
        val key = addrToNorm[address] ?: return

        // 丢包检测：仅累积计数器，不做任何 Log/IPC（与官方一致）
        val pc = data.packetCounter
        if (pc != -1) {
            val last = lastPktCounter.put(key, pc)
            if (last != null) {
                val expected = (last + 1) and 0xFFFF
                if (pc != expected) {
                    val lost = ((pc - last - 1) and 0xFFFF).coerceAtMost(200)
                    lossStats.getOrPut(key) { java.util.concurrent.atomic.AtomicInteger(0) }.addAndGet(lost)
                }
            }
            recvStats.getOrPut(key) { java.util.concurrent.atomic.AtomicInteger(0) }.incrementAndGet()
        }

        val parsed = parseDotData(idx, key, address, data)
        onDataCallback?.invoke(idx, parsed)

        val wd = waveDataMap.getOrPut(key) { WaveData() }
        wd.push(parsed.timestamp, parsed.acc ?: floatArrayOf(0f, 0f, 0f), parsed.gyro)
        pendingSensorData[key] = parsed
        val count = totalRecvCount.incrementAndGet()

        val now = System.currentTimeMillis()
        val lastUpdate = lastUiUpdateAtom.get()
        if (now - lastUpdate >= 100L && lastUiUpdateAtom.compareAndSet(lastUpdate, now)) {
            val sensorSnap = HashMap(pendingSensorData)
            val waveSnap   = HashMap<String, WaveSnapshot>()
            for (k in sensorSnap.keys) waveSnap[k] = waveDataMap[k]?.snapshot() ?: continue
            val countSnap = count
            mainHandler.post {
                _recvCount.value  = countSnap
                _sensorData.value = _sensorData.value.toMutableMap().also { it.putAll(sensorSnap) }
                _waveData.value   = _waveData.value.toMutableMap().also { it.putAll(waveSnap) }
            }
        }
    }

    private fun startLossReporting() {
        mainHandler.removeCallbacks(lossReportRunnable)
        lossStats.clear(); recvStats.clear()
        mainHandler.postDelayed(lossReportRunnable, 5000)
    }

    private fun stopLossReporting() {
        mainHandler.removeCallbacks(lossReportRunnable)
    }

    // ── 数据解析 ──

    private class ParseBuf {
        val euler = FloatArray(3); val acc = FloatArray(3)
        val gyro  = FloatArray(3); val mag = FloatArray(3)
    }
    private val parseBufs = ConcurrentHashMap<String, ParseBuf>()

    private fun fillD(dst: FloatArray, src: DoubleArray?): Boolean {
        if (src == null || src.size < 3) return false
        dst[0] = src[0].toFloat(); dst[1] = src[1].toFloat(); dst[2] = src[2].toFloat(); return true
    }
    private fun fillF(dst: FloatArray, src: FloatArray?): Boolean {
        if (src == null || src.size < 3) return false
        dst[0] = src[0]; dst[1] = src[1]; dst[2] = src[2]; return true
    }

    private fun parseDotData(sensorId: Int, normalizedAddr: String, rawAddr: String, d: DotData): SensorData {
        val b = parseBufs.getOrPut(normalizedAddr) { ParseBuf() }
        val hasEuler = fillD(b.euler, d.getEuler())
        val hasAcc   = fillF(b.acc, d.getFreeAcc()) || fillD(b.acc, d.getAcc())
        val hasGyro  = fillD(b.gyro, d.getGyr())
        val hasMag   = fillD(b.mag, d.getMag())
        return SensorData(
            sensorId = sensorId, address = rawAddr,
            timestamp = d.sampleTimeFine.toDouble(), packetCounter = d.packetCounter,
            euler = if (hasEuler) b.euler.copyOf() else null,
            acc   = if (hasAcc)   b.acc.copyOf()   else floatArrayOf(0f, 0f, 0f),
            gyro  = if (hasGyro)  b.gyro.copyOf()  else null,
            mag   = if (hasMag)   b.mag.copyOf()   else null
        )
    }

    // ── 公开控制 ──

    fun getDevices(): List<DotDevice> = devices.toList()
    fun setOutputRate(rate: Int) { devices.forEach { it.setOutputRate(rate) } }
    /** 立即向所有已连接设备写入目标采样率（用户点击选择按钮时调用） */
    fun setAllDevicesOutputRate(rate: Int) {
        if (!canWriteDeviceParameters()) {
            appendSyncLog("当前已进入测量/同步状态，请先停止采集或解除同步再修改采样率")
            return
        }
        mainHandler.post { devices.forEach { d -> d.setOutputRate(rate) } }
    }
    fun prepareSyncParameters(rate: Int, filterMode: Int): SyncParameterPrepareResult {
        if (!canWriteDeviceParameters()) {
            appendSyncLog("当前已进入测量/同步状态，请先停止采集或解除同步再修改同步参数")
            return SyncParameterPrepareResult(success = false, wroteParameters = false, waitMsBeforeSync = 0L)
        }
        pauseBackgroundReads()
        syncSessionStartedAtMs = SystemClock.elapsedRealtime()
        syncAttemptStartedAtMs = 0L
        logSyncTiming("prepareSyncParameters begin rate=${rate}Hz filter=${filterMode} devices=${devices.size}")
        syncOutputRate = rate
        _filterProfile.value = filterMode

        val devList = devices.toList()
        val needsWrite = devList.any { dev ->
            dev.currentOutputRate != rate ||
                dev.currentFilterProfileIndex != filterMode ||
                dev.measurementMode != desiredMode
        }

        val newestInitAgeMs = devList.mapNotNull { dev ->
            lastInitDoneAtMs[normalizeAddress(dev.address ?: "")]
        }.minOfOrNull { SystemClock.elapsedRealtime() - it } ?: Long.MAX_VALUE
        val initQuietWaitMs = (1_200L - newestInitAgeMs).coerceIn(0L, 1_200L)

        if (!needsWrite) {
            logSyncTiming("prepareSyncParameters skipped writes, already matched wait=${initQuietWaitMs}ms")
            if (initQuietWaitMs > 0L) appendSyncLog("设备参数已匹配，等待设备稳定后同步…")
            return SyncParameterPrepareResult(
                success = true,
                wroteParameters = false,
                waitMsBeforeSync = initQuietWaitMs
            )
        }

        mainHandler.post {
            devList.forEach { dev ->
                dev.setOutputRate(rate)
                dev.measurementMode = desiredMode
                applyFilterProfileToDevice(dev, filterMode)
            }
            logSyncTiming("prepareSyncParameters writes posted")
            appendSyncLog("同步参数已写入：${rate}Hz / ${if (filterMode == 1) "Dynamic" else "General"}")
        }
        return SyncParameterPrepareResult(
            success = true,
            wroteParameters = true,
            waitMsBeforeSync = maxOf(2_000L, initQuietWaitMs)
        )
    }
    fun canWriteDeviceParameters(): Boolean =
        !_isSyncing.value && !_isSynced.value && !measurementStarted

    fun confirmExistingSyncIfAllConnected(): Boolean {
        val connected = devices.filter { dev ->
            dev.connectionState == DotDevice.CONN_STATE_CONNECTED &&
                !dev.address.isNullOrBlank()
        }
        if (connected.size < 2 || !connected.all { it.isSynced }) return false

        mainHandler.post {
            _needsSync.value = false
            _isSynced.value = true
            _isSyncing.value = false
            _syncProgress.value = 100
            refreshDeviceSyncStates()
            measurementStarted = true
            startLossReporting()
            _state.value = CollectionState.Measuring
            appendSyncLog("检测到设备已处于 SDK 同步状态，直接保持已同步")
        }
        return true
    }

    fun startMeasuring() { devices.forEach { it.startMeasuring() } }
    fun stopMeasuring() {
        devices.forEach { it.stopMeasuring() }
        measurementStarted = false
        stopLossReporting()
        mainHandler.post { _state.value = CollectionState.Connecting }
    }

    // ── 直接采集 ──

    fun startDirectMeasurement() {
        mainHandler.post {
            val ready = initializedDevices()
            if (ready.isEmpty()) { appendSyncLog("无已就绪设备，请先完成连接"); return@post }
            if (_isSyncing.value) { appendSyncLog("同步进行中，请等待或停止同步"); return@post }
            if (_isSynced.value) {
                measurementStarted = true
                startLossReporting()
                _state.value = CollectionState.Measuring
                appendSyncLog("已处于 SDK 同步测量状态，保持 ${syncOutputRate}Hz；如需 60Hz 实时采集请先解除同步")
                return@post
            } else {
                appendSyncLog("直接采集：各传感器独立计时（跳过时间同步），${STREAM_OUTPUT_RATE_HZ}Hz")
                _isSynced.value = false
                _needsSync.value = false
            }
            applyModeAndStart(ready)
        }
    }

    // ── SDK 硬件同步 ──

    /**
     * Starts or adopts an SDK sync session.
     *
     * @return true when the request was accepted (including an already active/synced session),
     * false when current device readiness prevents sync from starting.
     */
    fun startSync(): Boolean = startSyncInternal()

    /**
     * 官方 §4.9 同步流程：
     * 1. stopMeasuring（如果在测量中）
     * 2. 检查 isSynced → 只对已同步设备 stopSyncing，等回调
     * 3. setRootDevice(true)
     * 4. DotSyncManager.startSyncing(deviceList, requestCode)
     *
     * 采样率和滤波器由用户点击选择按钮时立即写入设备，此处不再重复写入（避免 GATT 并发）。
     */
    private fun startSyncInternal(): Boolean {
        if (syncSessionStartedAtMs == 0L) syncSessionStartedAtMs = SystemClock.elapsedRealtime()
        logSyncTiming("startSyncInternal called devices=${devices.size}")
        if (_isSyncing.value) {
            appendSyncLog("同步正在进行中，请稍候…")
            return true
        }
        if (devices.isEmpty()) {
            resumeBackgroundReads()
            appendSyncLog("没有可同步的设备")
            return false
        }
        if (devices.size < 2) {
            appendSyncLog("单设备无需 SDK 同步")
            resumeBackgroundReads()
            _isSynced.value = false
            applyModeAndStart()
            return true
        }
        if (devices.any { it.connectionState != DotDevice.CONN_STATE_CONNECTED }) {
            resumeBackgroundReads()
            appendSyncLog("部分设备未连接，请等待后重试")
            return false
        }

        // 额外检查：所有设备必须完成初始化（onDotInitDone 已调用）才能同步
        val uninitAddrs = devices.filter {
            normalizeAddress(it.address ?: "") !in initDoneAddresses
        }.mapNotNull { it.address }
        if (uninitAddrs.isNotEmpty()) {
            resumeBackgroundReads()
            appendSyncLog("设备尚未就绪：$uninitAddrs，请等待初始化后重试")
            return false
        }

        _isSyncing.value = true
        _syncProgress.value = 0
        pauseBackgroundReads()

        // 官方 §4.10.3：有条件 stopMeasuring
        if (measurementStarted) {
            devices.forEach { try { it.stopMeasuring() } catch (_: Exception) {} }
            measurementStarted = false
        }
        syncManager = null

        // 官方 §4.9.1：检查 isSynced，只对已同步设备 stopSyncing
        val syncedDevices = devices.filter { it.isSynced }
        if (syncedDevices.size == devices.size) {
            appendSyncLog("检测到全部设备已同步，保持当前 SDK 同步状态")
            logSyncTiming("all devices already synced, skip startSyncing")
            _needsSync.value = false
            _isSynced.value = true
            _isSyncing.value = false
            _syncProgress.value = 100
            refreshDeviceSyncStates()
            measurementStarted = true
            startLossReporting()
            _state.value = CollectionState.Measuring
            resumeBackgroundReads()
            return true
        }
        if (syncedDevices.isNotEmpty()) {
            appendSyncLog("检测到 ${syncedDevices.size} 台已同步设备，先解除…")
            logSyncTiming("stopSyncing before resync syncedDevices=${syncedDevices.size}")
            var proceeded = false
            val preEpoch = syncEpoch

            fun proceedToSync() {
                if (proceeded) return
                proceeded = true
                if (preEpoch == syncEpoch) doStartSyncing()
            }

            DotSyncManager.getInstance(object : DotSyncCallback {
                override fun onSyncingStarted(address: String?, isSuccess: Boolean, requestCode: Int) {}
                override fun onSyncingProgress(progress: Int, requestCode: Int) {}
                override fun onSyncingResult(address: String?, isSuccess: Boolean, requestCode: Int) {}
                override fun onSyncingDone(syncingResultMap: HashMap<String, Boolean>, isSuccess: Boolean, requestCode: Int) {}
                override fun onSyncingStopped(address: String?, isSuccess: Boolean, requestCode: Int) {
                    mainHandler.post {
                        if (preEpoch != syncEpoch) return@post
                        appendSyncLog("[${address ?: "?"}] 解除同步 ${if (isSuccess) "✓" else "✗"}")
                        logSyncTiming("stopSyncing callback address=${address ?: "?"} success=$isSuccess")
                        proceedToSync()
                    }
                }
            }).stopSyncing()

            // 超时兜底：2 秒内未收到回调则强制继续
            mainHandler.postDelayed({
                if (preEpoch == syncEpoch) {
                    appendSyncLog("解除同步超时，强制继续…")
                    logSyncTiming("stopSyncing timeout, continue")
                    proceedToSync()
                }
            }, 2000L)
            return true
        }

        // 没有已同步设备，直接开始
        doStartSyncing()
        return true
    }

    private fun doStartSyncing() {
        val myEpoch = ++syncEpoch
        syncAttemptStartedAtMs = SystemClock.elapsedRealtime()
        logSyncTiming("doStartSyncing")
        scheduleSyncTimeout(myEpoch)

        // 官方 §4.9.2：只对第一台设备设置 root（与官方示例 setRootDevice(true) 一致）
        // 不对其余设备设置 isRootDevice=false，避免触发 4 个并发 GATT write 导致拥塞
        devices.first().isRootDevice = true
        devices.forEach { d ->
            val role = if (d.isRootDevice) "root" else "scanner"
            appendSyncLog("[${d.address}] $role | isSynced=${d.isSynced} | rate=${d.currentOutputRate}Hz | filter=${d.currentFilterProfileIndex}")
        }

        var syncFinalized = false
        var lastLoggedProgress = -1

        fun finalizeSyncOnce(succeeded: Boolean, desc: String) {
            if (syncFinalized) return
            if (myEpoch != syncEpoch) return
            syncFinalized = true
            logSyncTiming("finalizeSyncOnce succeeded=$succeeded desc=$desc")
            cancelSyncTimeout()
            devices.firstOrNull()?.isRootDevice = false
            syncManager = null

            if (succeeded) {
                _needsSync.value = false
                _isSynced.value = true
                _isSyncing.value = false
                _syncProgress.value = 100
                syncConfirmedAtMs = SystemClock.elapsedRealtime()
                _deviceSyncStates.value = devices.mapNotNull { dev ->
                    dev.address?.let { normalizeAddress(it) to true }
                }.toMap()
                devices.forEach { dev -> try { dev.stopMeasuring() } catch (_: Exception) {} }
                measurementStarted = false
                stopLossReporting()
                _state.value = CollectionState.Connecting
                resumeBackgroundReads()
                appendSyncLog("$desc — 已同步，等待开始录制")
            } else {
                syncConfirmedAtMs = 0L
                _isSynced.value = false
                _isSyncing.value = false
                _syncProgress.value = 0
                refreshDeviceSyncStates()
                appendSyncLog("$desc — 请重新点击「SDK 硬件同步」重试")
                _state.value = CollectionState.Connecting
                resumeBackgroundReads()
            }
        }

        // 官方 §4.9.2：获取 DotSyncManager 单例，注册回调，发起同步
        syncManager = DotSyncManager.getInstance(object : DotSyncCallback {
            override fun onSyncingStarted(address: String?, isSuccess: Boolean, requestCode: Int) {
                mainHandler.post {
                    if (myEpoch != syncEpoch) return@post
                    logSyncTiming("onSyncingStarted address=${address ?: "?"} success=$isSuccess")
                    appendSyncLog("[${address ?: "?"}] 同步启动 ${if (isSuccess) "✓" else "✗"}")
                }
            }
            override fun onSyncingProgress(progress: Int, requestCode: Int) {
                mainHandler.post {
                    if (myEpoch != syncEpoch) return@post
                    if (progress != lastLoggedProgress) {
                        lastLoggedProgress = progress
                        _syncProgress.value = progress.coerceIn(0, 100)
                        logSyncTiming("onSyncingProgress progress=$progress")
                        appendSyncLog("同步进度: $progress%")
                    }
                }
            }
            override fun onSyncingResult(address: String?, isSuccess: Boolean, requestCode: Int) {
                mainHandler.post {
                    if (myEpoch != syncEpoch) return@post
                    logSyncTiming("onSyncingResult address=${address ?: "?"} success=$isSuccess")
                    appendSyncLog("[${address ?: "?"}] ${if (isSuccess) "✓ 同步确认" else "同步未确认"}")
                }
            }
            override fun onSyncingDone(syncingResultMap: HashMap<String, Boolean>, isSuccess: Boolean, requestCode: Int) {
                mainHandler.post {
                    if (myEpoch != syncEpoch) return@post
                    val count = syncingResultMap.values.count { it }
                    val total = syncingResultMap.size
                    logSyncTiming("onSyncingDone success=$isSuccess count=$count/$total")
                    val failedAddrs = syncingResultMap.filter { !it.value }.keys
                    if (failedAddrs.isNotEmpty()) {
                        appendSyncLog("失败设备：${failedAddrs.joinToString()}")
                    }

                    if (count == total) {
                        // 全部成功
                        finalizeSyncOnce(true, "同步完成 ✓ $count/$total 全部成功")
                    } else {
                        val desc = if (count == 0) "同步失败 ✗ 0/$total" else "同步部分成功 $count/$total"
                        finalizeSyncOnce(false, desc)
                    }
                }
            }
            override fun onSyncingStopped(address: String?, isSuccess: Boolean, requestCode: Int) {
                mainHandler.post {
                    if (myEpoch != syncEpoch) return@post
                    appendSyncLog("[${address ?: "?"}] 同步停止")
                }
            }
        })

        appendSyncLog("开始同步…")
        logSyncTiming("calling startSyncing")
        val started = syncManager?.startSyncing(ArrayList(devices), SYNCING_REQUEST_CODE) ?: false
        logSyncTiming("startSyncing returned $started")
        if (!started) {
            devices.firstOrNull()?.isRootDevice = false
            syncManager = null
            finalizeSyncOnce(false, "同步启动失败 ✗")
        }
    }

    private fun requestStopSyncing(logSummary: Boolean) {
        // syncManager 在同步完成后被清为 null，需重新获取 DotSyncManager 实例才能真正停止设备同步
        val mgr = syncManager ?: DotSyncManager.getInstance(object : DotSyncCallback {
            override fun onSyncingStarted(address: String?, isSuccess: Boolean, requestCode: Int) {}
            override fun onSyncingProgress(progress: Int, requestCode: Int) {}
            override fun onSyncingResult(address: String?, isSuccess: Boolean, requestCode: Int) {}
            override fun onSyncingDone(syncingResultMap: HashMap<String, Boolean>, isSuccess: Boolean, requestCode: Int) {}
            override fun onSyncingStopped(address: String?, isSuccess: Boolean, requestCode: Int) {
                mainHandler.post {
                    appendSyncLog("[${address ?: "?"}] 解除同步 ${if (isSuccess) "✓" else "✗"}")
                }
            }
        })
        val stopTargets = devices.filter { it.connectionState == DotDevice.CONN_STATE_CONNECTED }
        val sent = if (stopTargets.isNotEmpty()) {
            mgr.stopSyncing(ArrayList(stopTargets))
        } else {
            mgr.stopSyncing()
        }
        if (!sent) {
            appendSyncLog("解除同步指令发送失败，请确认设备仍在连接范围内")
        }
        syncManager = null
        if (logSummary) {
            appendSyncLog("已发送解除同步指令，等待设备回调确认")
        }
    }

    fun stopSync() {
        requestStopSyncing(logSummary = true)
        // 同时停止测量，清理本地采集状态
        if (measurementStarted) {
            devices.forEach { try { it.stopMeasuring() } catch (_: Exception) {} }
            measurementStarted = false
        }
        ++syncEpoch  // 使任何残留的 epoch 回调失效
        mainHandler.post {
        _isSynced.value  = false
        syncConfirmedAtMs = 0L
            _isSyncing.value = false
            _syncProgress.value = 0
            _state.value     = CollectionState.Connecting
            _deviceSyncStates.value = _deviceSyncStates.value.mapValues { false }
            resumeBackgroundReads()
        }
    }

    private fun abortActiveSync(reason: String) {
        if (!_isSyncing.value) return
        ++syncEpoch
        cancelSyncTimeout()
        devices.firstOrNull()?.isRootDevice = false
        syncManager = null
        syncConfirmedAtMs = 0L
        _isSynced.value = false
        _isSyncing.value = false
        _syncProgress.value = 0
        _needsSync.value = devices.size > 1
        refreshDeviceSyncStates()
        _state.value = CollectionState.Connecting
        resumeBackgroundReads()
        appendSyncLog("$reason，请恢复连接后重新准备采集")
        logSyncTiming("sync aborted: $reason")
    }

    private fun scheduleSyncTimeout(epoch: Int) {
        syncTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        syncTimeoutRunnable = Runnable {
            if (epoch != syncEpoch) return@Runnable
            if (_isSyncing.value) {
                abortActiveSync("同步超时（55s）")
            }
        }.also { mainHandler.postDelayed(it, 55_000) }
    }

    private fun cancelSyncTimeout() {
        syncTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        syncTimeoutRunnable = null
    }

    // ── Heading ──

    fun headingReset() {
        lastHeadingAction = "reset"
        if (!measurementStarted) { appendSyncLog("请先启动测量后再执行 Heading Reset"); return }
        initializedDevices().forEach { dev ->
            if (dev.isSupportedHeadingReset) {
                if (!dev.resetHeading()) appendSyncLog("[${dev.address}] Heading Reset 失败")
            } else appendSyncLog("[${dev.address}] 设备不支持 Heading Reset")
        }
    }

    fun headingRevert() {
        lastHeadingAction = "revert"
        if (!measurementStarted) return
        initializedDevices().forEach { dev ->
            if (dev.isSupportedHeadingReset)
                if (!dev.revertHeading()) appendSyncLog("[${dev.address}] Heading Revert 失败")
        }
    }

    override fun onDotHeadingChanged(address: String?, status: Int, result: Int) {
        mainHandler.post {
            val action = lastHeadingAction
            appendSyncLog("[${address ?: "?"}] Heading ${if (action == "reset") "Reset" else "Revert"} ${if (result == 1) "成功" else "失败"}")
        }
    }

    override fun onDotRotLocalRead(address: String?, quaternions: FloatArray?) {}
    override fun onDotCiResult(address: String, length: Int, result: ByteArray) {}

    // ── Payload 模式切换 ──

    fun applyPayloadMode(mode: Int) {
        desiredMode = mode
        if (devices.isEmpty()) return
        if (_isSynced.value) {
            appendSyncLog("已处于 SDK 同步测量状态，Payload 需解除同步后再修改")
            return
        }
        applyModeAndStart()
    }

    // ── 滤波器配置切换（General / Dynamic）──
    // 与官方 DeviceManager.setSensorProfile(device, index) 逻辑对齐：
    //   mode=0 → General（firstOrNull），mode=1 → Dynamic（getOrNull(1) ?: first）

    fun setFilterProfileMode(mode: Int) {
        if (!canWriteDeviceParameters()) {
            appendSyncLog("当前已进入测量/同步状态，请先停止采集或解除同步再修改滤波档")
            return
        }
        _filterProfile.value = mode
        mainHandler.post {
            devices.forEach { dev ->
                applyFilterProfileToDevice(dev, mode)
            }
        }
    }

    private fun applyFilterProfileToDevice(dev: DotDevice, mode: Int) {
        // 直接使用 SDK 内置列表（javap 确认为 public API），不依赖外部缓存
        val profiles = dev.filterProfileInfoList
        if (profiles.isNullOrEmpty()) return
        val target = if (mode == 0) profiles.firstOrNull()
                     else           profiles.getOrNull(1) ?: profiles.firstOrNull()
        target?.let { dev.setFilterProfile(it.index) }
    }

    /**
     * 离线录制专用：切换采样率和滤波档。
     *
     * 进入离线页面时只配置参数，不立即 startMeasuring，避免设备看起来已经在采集，
     * 也避免测量数据占用 GATT 队列导致 Flash 状态读取不稳定。
     */
    private fun applyOfflineModeSettings(rate: Int, filterMode: Int, startMeasurement: Boolean) {
        val devList = devices.toList()
        if (devList.isEmpty()) return
        if (_isSynced.value) {
            appendSyncLog("已处于 SDK 同步测量状态，参数修改需先解除同步")
            return
        }
        val sid = connectSessionId
        mainHandler.post {
            if (sid != connectSessionId) return@post
            if (measurementStarted) {
                devList.forEach { try { it.stopMeasuring() } catch (_: Exception) {} }
                measurementStarted = false
                stopLossReporting()
                _state.value = CollectionState.Connecting
            }
            devList.forEach { dev ->
                dev.setOutputRate(rate)
                dev.measurementMode = desiredMode
                applyFilterProfileToDevice(dev, filterMode)
            }
            val profileLabel = if (filterMode == 1) "Dynamic" else "General"
            if (!startMeasurement) {
                appendSyncLog("离线准备：${rate}Hz / $profileLabel，未开始录制")
                return@post
            }
            mainHandler.postDelayed({
                if (sid != connectSessionId) return@postDelayed
                devList.forEach { dev -> dev.startMeasuring() }
                measurementStarted = true
                _state.value = CollectionState.Measuring
                appendSyncLog("离线录制测量已启动：${rate}Hz / $profileLabel")
            }, 500)
        }
    }

    fun prepareOfflineModeSettings(rate: Int, filterMode: Int) {
        applyOfflineModeSettings(rate, filterMode, startMeasurement = false)
    }

    fun startOfflineRecordingMeasurement(rate: Int, filterMode: Int) {
        applyOfflineModeSettings(rate, filterMode, startMeasurement = true)
    }

    fun startSyncedRecordingMeasurement(): Boolean {
        val devList = devices.filter { it.connectionState == DotDevice.CONN_STATE_CONNECTED }
        if (!_isSynced.value || devList.isEmpty()) return false
        val sid = connectSessionId
        mainHandler.post {
            if (sid != connectSessionId) return@post
            devList.forEach { dev -> dev.measurementMode = desiredMode }
            devList.forEach { dev -> dev.startMeasuring() }
            measurementStarted = true
            startLossReporting()
            _state.value = CollectionState.Measuring
            appendSyncLog("同步设备测量已启动，正在开始录制")
        }
        return true
    }

    // ── 录制状态 ──

    fun setRecordingState(recording: Boolean) {
        if (recording) {
            if (_state.value == CollectionState.Measuring) _state.value = CollectionState.Recording
        } else {
            if (_state.value == CollectionState.Recording)  _state.value = CollectionState.Measuring
        }
    }

    enum class CollectionState { Idle, Scanning, Connecting, Measuring, Recording }
}
