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

internal fun isSyncCommandReady(
    isConnected: Boolean,
    initializedThisConnection: Boolean,
    initializedBefore: Boolean,
    connectedStableMs: Long,
    reconnectStableRequirementMs: Long,
): Boolean =
    isConnected &&
        (
            initializedThisConnection ||
                (
                    initializedBefore &&
                        connectedStableMs >= reconnectStableRequirementMs
                    )
            )

internal fun shouldRetryIncompleteSync(
    succeededCount: Int,
    totalCount: Int,
    retryCount: Int,
    maxRetries: Int,
): Boolean =
    totalCount > 0 &&
        succeededCount in 0 until totalCount &&
        retryCount < maxRetries

internal fun mergeObservedSyncState(
    previouslyConfirmed: Boolean?,
    sdkReportsSynced: Boolean,
): Boolean =
    sdkReportsSynced || previouslyConfirmed == true

internal fun shouldDeferNegativeSyncStatus(
    previouslyConfirmed: Boolean,
    flashRecordingActive: Boolean,
    reconnectPending: Boolean,
    connectedStableMs: Long,
    reconnectGuardMs: Long,
): Boolean =
    previouslyConfirmed &&
        (
            flashRecordingActive ||
                reconnectPending ||
                connectedStableMs < reconnectGuardMs
            )

internal fun shouldPollRssiForDevice(
    isConnected: Boolean,
    backgroundReadsPaused: Boolean,
    isSyncing: Boolean,
    isExportTarget: Boolean,
): Boolean =
    isConnected &&
        !backgroundReadsPaused &&
        !isSyncing &&
        !isExportTarget

internal fun shouldSetupRecordingManagerAfterConnection(
    isSyncing: Boolean,
    newlyConnectedAddresses: Set<String>,
    syncTargetAddresses: Set<String>,
): Boolean =
    !isSyncing &&
        newlyConnectedAddresses.none { it in syncTargetAddresses }

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
        private const val RECONNECT_SYNC_NEGATIVE_GUARD_MS = 8_000L
        // Android BLE GATT active reads must be serialized. Polling one of two DOTs every
        // 500 ms keeps each device near a 1 s refresh cadence without dropping callbacks.
        private const val RSSI_MONITOR_INTERVAL_MS = 500L
        private const val SYNC_RESULT_SETTLE_MS = 10_000L
        private const val MAX_TRANSIENT_SYNC_RETRIES = 1
        private const val TRANSIENT_SYNC_RETRY_DELAY_MS = 2_000L
        private const val SCAN_TIMEOUT_MS = 5_000L
        private const val BLUETOOTH_RESTART_RECONNECT_DELAY_MS = 1_500L
        private const val RECONNECT_SYNC_READY_STABLE_MS = 1_500L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var sdkScanner: DotScanner? = null
    private var scanTimeoutRunnable: Runnable? = null
    @Volatile private var scanSessionId = 0
    @Volatile private var exportTargetAddresses: Set<String> = emptySet()
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

    private val _manuallyDisconnectedAddresses = MutableStateFlow<Set<String>>(emptySet())
    val manuallyDisconnectedAddresses: StateFlow<Set<String>> =
        _manuallyDisconnectedAddresses.asStateFlow()

    private val _connectionTargetAddresses = MutableStateFlow<Set<String>>(emptySet())
    val connectionTargetAddresses: StateFlow<Set<String>> =
        _connectionTargetAddresses.asStateFlow()

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

    /** 由录制状态机提供，避免 SDK 在 Flash 录制回连时自动重复 startMeasuring。 */
    var isFlashRecordingDevice: ((normalizedAddress: String) -> Boolean)? = null

    private val _isSynced = MutableStateFlow(false)
    val isSynced: StateFlow<Boolean> = _isSynced.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncProgress = MutableStateFlow(0)
    val syncProgress: StateFlow<Int> = _syncProgress.asStateFlow()

    private val _syncTargetAddresses = MutableStateFlow<Set<String>>(emptySet())
    val syncTargetAddresses: StateFlow<Set<String>> = _syncTargetAddresses.asStateFlow()

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
    private val connectedSinceAtMs = ConcurrentHashMap<String, Long>()

    // 地址映射缓存：connectDevices() 写入一次，之后只读（BLE 线程安全）
    private val addrToIdx = HashMap<String, Int>()
    private val addrToNorm = HashMap<String, String>()
    // BLE 多线程并发写入，需 ConcurrentHashMap
    private val lastPktCounter = ConcurrentHashMap<String, Int>()

    @Volatile private var connectSessionId  = 0
    @Volatile private var desiredMode       = DotPayload.PAYLOAD_TYPE_CUSTOM_MODE_1
    @Volatile private var targetDeviceCount = 0
    @Volatile private var measurementStarted = false
    private val measuringAddresses = ConcurrentHashMap.newKeySet<String>()
    private val totalRecvCount    = java.util.concurrent.atomic.AtomicLong(0L)
    private val lastUiUpdateAtom  = java.util.concurrent.atomic.AtomicLong(0L)

    private val pendingSensorData = ConcurrentHashMap<String, SensorData>()
    private var syncManager: DotSyncManager? = null
    private var activeSyncDevices: List<DotDevice> = emptyList()
    @Volatile private var syncEpoch = 0
    @Volatile private var syncSessionStartedAtMs = 0L
    @Volatile private var syncAttemptStartedAtMs = 0L
    @Volatile private var syncConfirmedAtMs = 0L
    @Volatile private var activeSyncRetryCount = 0
    private val syncConfirmedAtByDevice = ConcurrentHashMap<String, Long>()
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

    private fun clearSyncRootRoles(targetDevices: List<DotDevice> = devices) {
        targetDevices.forEach { it.isRootDevice = false }
    }

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
        val activeFlashRecordingAddresses = targets
            .map { normalizeAddress(it.address) }
            .filter { isFlashRecordingDevice?.invoke(it) == true }
            .toSet()
        connectDevices(
            selected = targets.sortedBy { LongJumpDeviceRoles.roleSortIndex(it.address) },
            reconnectContextAddresses = activeFlashRecordingAddresses,
            preservedMeasuringAddresses =
                activeFlashRecordingAddresses.intersect(measuringAddresses),
        )
    }

    private fun resolveDeviceIndex(address: String): Int? {
        val norm = normalizeAddress(address)
        addressToIndex[norm]?.let { return it }
        val idx = devices.indexOfFirst { normalizeAddress(it.address ?: "") == norm }
        if (idx >= 0) { addressToIndex[norm] = idx; return idx }
        return null
    }

    private fun rebuildDeviceAddressIndexes() {
        addressToIndex.clear()
        addrToIdx.clear()
        addrToNorm.clear()
        devices.forEachIndexed { index, device ->
            val address = device.address ?: return@forEachIndexed
            val norm = normalizeAddress(address)
            addressToIndex[norm] = index
            addrToIdx[address] = index
            addrToNorm[address] = norm
        }
    }

    private fun initializedDevices(): List<DotDevice> =
        devices.filter { normalizeAddress(it.address ?: "") in initDoneAddresses }

    private fun devicesForAddresses(addresses: Set<String>): List<DotDevice> {
        val normalized = addresses.map(::normalizeAddress).toSet()
        return devices
            .filter { normalizeAddress(it.address.orEmpty()) in normalized }
            .sortedBy { LongJumpDeviceRoles.roleSortIndex(it.address.orEmpty()) }
    }

    private fun refreshDeviceSyncStates() {
        val previous = _deviceSyncStates.value
        _deviceSyncStates.value = devices
            .mapNotNull { dev ->
                val addr = dev.address ?: return@mapNotNull null
                val norm = normalizeAddress(addr)
                norm to mergeObservedSyncState(
                    previouslyConfirmed = previous[norm],
                    sdkReportsSynced = dev.isSynced,
                )
            }
            .toMap()
    }

    private fun connectedStableMs(norm: String): Long {
        val connectedAt = connectedSinceAtMs[norm] ?: return Long.MAX_VALUE
        return (SystemClock.elapsedRealtime() - connectedAt).coerceAtLeast(0L)
    }

    private fun deferNegativeSyncStatus(norm: String): Boolean =
        shouldDeferNegativeSyncStatus(
            previouslyConfirmed = _deviceSyncStates.value[norm] == true,
            flashRecordingActive = isFlashRecordingDevice?.invoke(norm) == true,
            reconnectPending = norm in reconnectPendingAddresses,
            connectedStableMs = connectedStableMs(norm),
            reconnectGuardMs = RECONNECT_SYNC_NEGATIVE_GUARD_MS,
        )

    private fun scheduleStableSyncStatusVerification(norm: String) {
        val sessionId = connectSessionId
        mainHandler.postDelayed({
            if (
                sessionId != connectSessionId ||
                norm in _manuallyDisconnectedAddresses.value ||
                isFlashRecordingDevice?.invoke(norm) == true
            ) {
                return@postDelayed
            }
            val device = devices.firstOrNull {
                normalizeAddress(it.address.orEmpty()) == norm
            } ?: return@postDelayed
            if (
                device.connectionState == DotDevice.CONN_STATE_CONNECTED &&
                _deviceSyncStates.value[norm] == true
            ) {
                device.readSyncStatus()
            }
        }, RECONNECT_SYNC_NEGATIVE_GUARD_MS + 500L)
    }

    private fun refreshGlobalSyncFromConnectedDevices() {
        if (_isSyncing.value) return
        val configuredDevices = devices.filter { !it.address.isNullOrBlank() }
        val allConfiguredSynced =
            configuredDevices.size >= 2 &&
                configuredDevices.size == targetDeviceCount &&
                configuredDevices.all { dev ->
                    val norm = normalizeAddress(dev.address.orEmpty())
                    dev.connectionState == DotDevice.CONN_STATE_CONNECTED &&
                        _deviceSyncStates.value[norm] == true
                }
        if (allConfiguredSynced) {
            _needsSync.value = false
            _isSynced.value = true
        } else {
            _isSynced.value = false
            _needsSync.value = configuredDevices.size >= 2
        }
    }

    private fun markDevicesMeasuring(targetDevices: Collection<DotDevice>) {
        targetDevices.forEach { device ->
            device.address?.let { measuringAddresses.add(normalizeAddress(it)) }
        }
        measurementStarted = measuringAddresses.isNotEmpty()
        if (measurementStarted) {
            startLossReporting()
            if (_state.value != CollectionState.Recording) {
                _state.value = CollectionState.Measuring
            }
        }
    }

    private fun unmarkDevicesMeasuring(targetDevices: Collection<DotDevice>) {
        targetDevices.forEach { device ->
            device.address?.let { measuringAddresses.remove(normalizeAddress(it)) }
        }
        measurementStarted = measuringAddresses.isNotEmpty()
        if (!measurementStarted) {
            stopLossReporting()
            _state.value = CollectionState.Connecting
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
        if (_isSyncing.value) {
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
     * Flash 导出依赖目标设备持续发送 BLE notification。只暂停导出目标设备的
     * RSSI/电量等主动 GATT 读取，其他运动员仍在录制时继续更新链路状态并可独立停止。
     */
    fun setExportTargets(addresses: Set<String>) {
        val normalized = addresses.map(::normalizeAddress).filter { it.isNotBlank() }.toSet()
        if (exportTargetAddresses == normalized) return
        exportTargetAddresses = normalized
        if (backgroundReadsPaused || _isSyncing.value) {
            stopRssiMonitoring()
        } else if (devices.any { device ->
                shouldPollRssiForDevice(
                    isConnected = device.connectionState == DotDevice.CONN_STATE_CONNECTED,
                    backgroundReadsPaused = false,
                    isSyncing = false,
                    isExportTarget = normalizeAddress(device.address.orEmpty()) in normalized,
                )
            }) {
            startRssiMonitoring()
        } else {
            stopRssiMonitoring()
        }
        Log.i(
            DIAG_TAG,
            if (normalized.isEmpty()) {
                "Flash export finished: RSSI monitoring restored for all connected devices"
            } else {
                "Flash export targets=${normalized.joinToString()}: active reads paused only for targets"
            },
        )
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
        Log.i(SYNC_TIMING_TAG, msg)
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
            val list = _scannedDevices.value.toMutableList()
            val index = list.indexOfFirst { normalizeAddress(it.address) == normalizeAddress(dev.address) }
            if (index >= 0) {
                list[index] = list[index].copy(name = name, rssi = rssi)
            } else {
                list.add(ScannedDevice(dev, name, dev.address, rssi))
            }
            _scannedDevices.value = list.sortedWith(
                compareBy<ScannedDevice> { LongJumpDeviceRoles.roleSortIndex(it.address) }
                    .thenBy { normalizeAddress(it.address) }
            )
            tryCompleteBluetoothRestartReconnect()
        }
    }

    // ── 连接 ──

    fun connectDevices(selected: List<ScannedDevice>) {
        connectDevices(
            selected = selected,
            reconnectContextAddresses = emptySet(),
            preservedMeasuringAddresses = emptySet(),
        )
    }

    private fun connectDevices(
        selected: List<ScannedDevice>,
        reconnectContextAddresses: Set<String>,
        preservedMeasuringAddresses: Set<String>,
    ) {
        if (selected.isEmpty()) return
        val reconnectContexts = reconnectContextAddresses
            .map(::normalizeAddress)
            .toSet()
        val preservedMeasuring = preservedMeasuringAddresses
            .map(::normalizeAddress)
            .toSet()
        bluetoothRestartReconnectPending = false
        bluetoothRestartReconnectRunnable?.let(mainHandler::removeCallbacks)
        bluetoothRestartReconnectRunnable = null
        stopScan()
        userRequestedDisconnect = false
        _manuallyDisconnectedAddresses.value = emptySet()
        connectionTargets = selected.map {
            ConnectionTarget(address = it.address, name = it.name, rssi = it.rssi)
        }
        _connectionTargetAddresses.value =
            connectionTargets.map { normalizeAddress(it.address) }.toSet()
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
        measuringAddresses.clear()
        measuringAddresses.addAll(preservedMeasuring)
        measurementStarted  = measuringAddresses.isNotEmpty()
        _needsSync.value    = selected.size > 1
        _state.value        = CollectionState.Connecting
        devices.clear(); addressToIndex.clear(); initDoneAddresses.clear(); initializedOnceAddresses.clear()
        reconnectPendingAddresses.clear(); lastReconnectHandledAt.clear()
        initializedOnceAddresses.addAll(reconnectContexts)
        reconnectPendingAddresses.addAll(reconnectContexts)
        connectedSinceAtMs.clear()
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

        // 多设备连接按 300ms 错峰，参与设备越多，初始化窗口相应延长。
        val connectionTimeoutMs = 20_000L + (selected.size - 2).coerceAtLeast(0) * 3_000L
        mainHandler.postDelayed({
            if (sid != connectSessionId) return@postDelayed
            val missing = targetDeviceCount - initDoneAddresses.size
            if (missing > 0) {
                appendSyncLog("连接超时：有 $missing 台设备未就绪，建议断开后扫描连接")
            }
        }, connectionTimeoutMs)
    }

    fun connectAdditionalDevices(selected: List<ScannedDevice>): Boolean {
        if (selected.isEmpty()) return false
        val connectedNorms = _connectedDevices.value.map(::normalizeAddress).toSet()
        val existingNorms = devices.mapNotNull { it.address }.map(::normalizeAddress).toSet()
        val additions = selected
            .distinctBy { normalizeAddress(it.address) }
            .filter { normalizeAddress(it.address) !in connectedNorms }
        if (additions.isEmpty()) return true

        stopScan()
        userRequestedDisconnect = false
        val additionNorms = additions.map { normalizeAddress(it.address) }.toSet()
        _manuallyDisconnectedAddresses.value -= additionNorms
        connectionTargets = (
            connectionTargets.filterNot { normalizeAddress(it.address) in additionNorms } +
                additions.map {
                    ConnectionTarget(address = it.address, name = it.name, rssi = it.rssi)
                }
            ).distinctBy { normalizeAddress(it.address) }
        _connectionTargetAddresses.value =
            connectionTargets.map { normalizeAddress(it.address) }.toSet()

        devices.removeAll { device ->
            normalizeAddress(device.address.orEmpty()) in additionNorms &&
                normalizeAddress(device.address.orEmpty()) in existingNorms
        }
        additions.forEach { scanned ->
            val xsDev = DotDevice(context, scanned.device, this)
            xsDev.setDotCiCallback(this)
            xsDev.setDotMeasurementCallback(this)
            devices.add(xsDev)
        }
        rebuildDeviceAddressIndexes()

        val sid = ++connectSessionId
        targetDeviceCount = devices.size
        _state.value = CollectionState.Connecting
        _needsSync.value = targetDeviceCount > 1
        _initProgress.value = Pair(initDoneAddresses.size, targetDeviceCount)
        additions.forEachIndexed { index, scanned ->
            val norm = normalizeAddress(scanned.address)
            initializedOnceAddresses.remove(norm)
            initDoneAddresses.remove(norm)
            reconnectPendingAddresses.remove(norm)
            lastReconnectHandledAt.remove(norm)
            connectedSinceAtMs.remove(norm)
            outOfRangeLoggedAddresses.remove(norm)
            mainHandler.postDelayed({
                if (sid == connectSessionId) {
                    devices.firstOrNull {
                        normalizeAddress(it.address.orEmpty()) == norm
                    }?.connect()
                }
            }, index * 300L)
        }
        appendSyncLog("正在连接 ${additions.size} 台分组设备")
        return true
    }

    fun disconnectDevices(addresses: Set<String>): Boolean {
        val normalizedTargets = addresses.map(::normalizeAddress).toSet()
        val targets = devices.filter {
            normalizeAddress(it.address.orEmpty()) in normalizedTargets
        }
        if (targets.isEmpty()) return false

        _manuallyDisconnectedAddresses.value += normalizedTargets
        connectionTargets = connectionTargets.filterNot {
            normalizeAddress(it.address) in normalizedTargets
        }
        _connectionTargetAddresses.value =
            connectionTargets.map { normalizeAddress(it.address) }.toSet()
        userRequestedDisconnect = connectionTargets.isEmpty()
        connectSessionId++
        requestStopSyncing(targets, logSummary = false)
        unmarkDevicesMeasuring(targets)

        targets.forEach { device ->
            runCatching { device.stopMeasuring() }
            runCatching { device.cancelReconnecting() }
            runCatching { device.setDotCiCallback(null) }
            runCatching { device.setDotMeasurementCallback(null) }
            runCatching { device.setDotDeviceCallback(null) }
        }
        mainHandler.postDelayed({
            targets.forEach { device -> runCatching { device.disconnect() } }
        }, 300)

        devices.removeAll(targets.toSet())
        normalizedTargets.forEach { norm ->
            initDoneAddresses.remove(norm)
            initializedOnceAddresses.remove(norm)
            reconnectPendingAddresses.remove(norm)
            lastReconnectHandledAt.remove(norm)
            connectedSinceAtMs.remove(norm)
            outOfRangeLoggedAddresses.remove(norm)
            lastInitDoneAtMs.remove(norm)
            measuringAddresses.remove(norm)
            lastPktCounter.remove(norm)
            syncConfirmedAtByDevice.remove(norm)
            pendingSensorData.remove(norm)
            waveDataMap.remove(norm)
            lossStats.remove(norm)
            recvStats.remove(norm)
        }
        rebuildDeviceAddressIndexes()
        targetDeviceCount = devices.size
        _initProgress.value = Pair(initDoneAddresses.size, targetDeviceCount)
        _connectedDevices.value = devices.mapNotNull { device ->
            device.address?.takeIf {
                device.connectionState == DotDevice.CONN_STATE_CONNECTED
            }
        }
        _batteryStatus.value = _batteryStatus.value - normalizedTargets
        _deviceRssi.value = _deviceRssi.value - normalizedTargets
        _deviceRssiUpdatedAt.value = _deviceRssiUpdatedAt.value - normalizedTargets
        _deviceSyncStates.value = _deviceSyncStates.value - normalizedTargets
        _firmwareStatus.value = _firmwareStatus.value - normalizedTargets
        _sensorData.value = _sensorData.value - normalizedTargets
        _waveData.value = _waveData.value - normalizedTargets
        refreshDeviceSyncStates()
        refreshGlobalSyncFromConnectedDevices()

        if (devices.isEmpty()) {
            stopRssiMonitoring()
            _state.value = CollectionState.Idle
            _needsSync.value = false
            _isSynced.value = false
        } else {
            _state.value = CollectionState.Connecting
            resumeBackgroundReads()
        }
        appendSyncLog("已断开 ${targets.size} 台分组设备")
        return true
    }

    fun disconnectAll() {
        userRequestedDisconnect = true
        connectionTargets = emptyList()
        _connectionTargetAddresses.value = emptySet()
        bluetoothRestartReconnectRunnable?.let(mainHandler::removeCallbacks)
        bluetoothRestartReconnectRunnable = null
        bluetoothRestartReconnectPending = false
        connectSessionId++; syncEpoch++
        exportTargetAddresses = emptySet()
        stopLossReporting()
        stopRssiMonitoring()
        cancelSyncTimeout()
        requestStopSyncing(devices.toList(), logSummary = false)

        val targets = devices.toList()
        targets.forEach { it.stopMeasuring() }
        mainHandler.postDelayed({ targets.forEach { it.disconnect() } }, 300)

        devices.clear(); addressToIndex.clear(); initDoneAddresses.clear(); initializedOnceAddresses.clear()
        reconnectPendingAddresses.clear(); lastReconnectHandledAt.clear()
        connectedSinceAtMs.clear()
        outOfRangeLoggedAddresses.clear()
        addrToIdx.clear(); addrToNorm.clear(); lastPktCounter.clear()
        targetDeviceCount = 0
        measurementStarted = false
        measuringAddresses.clear()
        _initProgress.value    = Pair(0, 0)
        _connectedDevices.value = emptyList()
        _manuallyDisconnectedAddresses.value = emptySet()
        _state.value           = CollectionState.Idle
        _isSynced.value        = false
        _isSyncing.value       = false
        _syncProgress.value    = 0
        _syncTargetAddresses.value = emptySet()
        activeSyncDevices = emptyList()
        _needsSync.value       = false
        _recvCount.value       = 0
        _batteryStatus.value   = emptyMap()
        _deviceRssi.value      = emptyMap()
        _deviceSyncStates.value = emptyMap()
        syncConfirmedAtMs = 0L
        syncConfirmedAtByDevice.clear()
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
            if (normForLog in _manuallyDisconnectedAddresses.value) return@post
            logLinkDiag(
                addr,
                "connection=${connectionStateLabel(state)} initialized=${normForLog in initializedOnceAddresses} " +
                    "initDone=${normForLog in initDoneAddresses} " +
                    "measuring=${normForLog in measuringAddresses} synced=${_deviceSyncStates.value[normForLog] == true}"
            )
            when (state) {
                DotDevice.CONN_STATE_CONNECTED -> {
                    resolveDeviceIndex(addr)
                    val norm = normalizeAddress(addr)
                    connectedSinceAtMs.putIfAbsent(norm, SystemClock.elapsedRealtime())
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
                    refreshGlobalSyncFromConnectedDevices()
                    if (!_isSyncing.value) {
                        devices.firstOrNull { normalizeAddress(it.address ?: "") == norm }?.readRssi()
                        startRssiMonitoring()
                    }
                    if (!measurementStarted) _state.value = CollectionState.Connecting
                }
                DotDevice.CONN_STATE_START_RECONNECTING,
                DotDevice.CONN_STATE_RECONNECTING -> {
                    val norm = normalizeAddress(addr)
                    if (isFlashRecordingDevice?.invoke(norm) == true) {
                        devices.firstOrNull {
                            normalizeAddress(it.address.orEmpty()) == norm
                        }?.setAutoPlot(false)
                        logLinkDiag(addr, "flash recording active: suppressed SDK auto measurement restore")
                    }
                    connectedSinceAtMs.remove(norm)
                    if (_isSyncing.value && !isBluetoothEnabled()) {
                        abortActiveSync("蓝牙已关闭，同步已取消")
                    }
                    markReconnectPending(norm)
                    clearRssi(norm)
                    refreshConnectedDevices(excludeNormAddr = norm)
                    refreshDeviceSyncStates()
                    refreshGlobalSyncFromConnectedDevices()
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
                    if (isFlashRecordingDevice?.invoke(norm) == true) {
                        devices.firstOrNull {
                            normalizeAddress(it.address.orEmpty()) == norm
                        }?.setAutoPlot(false)
                        logLinkDiag(addr, "flash recording active: kept SDK auto measurement restore disabled")
                    }
                    connectedSinceAtMs.remove(norm)
                    markReconnectPending(norm)
                    clearRssi(norm)
                    refreshConnectedDevices(excludeNormAddr = norm)
                    refreshDeviceSyncStates()
                    refreshGlobalSyncFromConnectedDevices()
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
            if (normAddr in _manuallyDisconnectedAddresses.value) return@post
            resolveDeviceIndex(addr) ?: return@post
            val dev = devices.firstOrNull { normalizeAddress(it.address ?: "") == normAddr } ?: return@post
            val isFirstReady = normAddr !in initDoneAddresses
            val isFirstInitInSession = normAddr !in initializedOnceAddresses
            val isReconnect = consumeReconnectPending(normAddr)
            val shouldHandleReconnect = isReconnect

            // 必须在任何BLE操作之前检查：DotSyncManager的readAck在30ms后也排队到主线程
            // 若此处发起GATT write，会占用GATT导致readAck失败→同步75%失败
            if (_isSyncing.value) {
                // 同步会主动让设备断联并回连。这里仍需恢复本地“初始化完成”状态，
                // 否则同步成功后解除同步，再次同步会被未初始化检查错误拦截。
                // DotSyncManager 会在约 30ms 后读取 ACK，此分支只能做最小内存标记：
                // 不能更新 StateFlow、刷新 UI、写日志或发起任何 GATT 操作。
                lastInitDoneAtMs[normAddr] = SystemClock.elapsedRealtime()
                initDoneAddresses.add(normAddr)
                initializedOnceAddresses.add(normAddr)
                outOfRangeLoggedAddresses.remove(normAddr)
                return@post
            }

            // 部分固件会在普通 GATT 读取后重复上报 initDone。重复回调不能再次触发
            // 电量、RSSI、采样率等 BLE 操作，否则会形成 initDone -> read -> initDone 循环。
            if (!isFirstReady && !isReconnect) return@post

            lastInitDoneAtMs[normAddr] = SystemClock.elapsedRealtime()
            connectedSinceAtMs.putIfAbsent(normAddr, SystemClock.elapsedRealtime())
            logLinkDiag(
                addr,
                "initDone firstReady=$isFirstReady firstSession=$isFirstInitInSession reconnect=$isReconnect " +
                    "conn=${connectionStateLabel(dev.connectionState)} " +
                    "measuring=${normAddr in measuringAddresses} synced=${dev.isSynced} " +
                    "rate=${dev.currentOutputRate} firmware=${dev.firmwareVersion ?: "unknown"}"
            )
            outOfRangeLoggedAddresses.remove(normAddr)
            initDoneAddresses.add(normAddr)
            initializedOnceAddresses.add(normAddr)
            _initProgress.value = Pair(initDoneAddresses.size, targetDeviceCount)
            refreshDeviceSyncStates()
            refreshGlobalSyncFromConnectedDevices()
            updateFirmwareStatus(normAddr, dev.firmwareVersion, dev.isCompatibleFirmwareVersion)
            if (
                shouldHandleReconnect &&
                isFlashRecordingDevice?.invoke(normAddr) == true
            ) {
                // Flash recording continues on the sensor while BLE is unavailable. Rewriting
                // output rate, payload mode, filter profile, or measurement state here truncates
                // the active file even though the recording-state query still reports onRecording.
                appendSyncLog("[$addr] 已回连，保持离线录制参数不变")
                onDeviceReconnected?.invoke(addr)
                return@post
            }
            if (shouldHandleReconnect) {
                scheduleStableSyncStatusVerification(normAddr)
            }
            val batteryReadSession = connectSessionId
            mainHandler.postDelayed({
                if (
                    batteryReadSession == connectSessionId &&
                    shouldPollRssiForDevice(
                        isConnected = dev.connectionState == DotDevice.CONN_STATE_CONNECTED,
                        backgroundReadsPaused = backgroundReadsPaused,
                        isSyncing = _isSyncing.value,
                        isExportTarget = normAddr in exportTargetAddresses,
                    )
                ) {
                    dev.readBattery()
                    dev.readRssi()
                }
            }, 350L)

            if (targetDeviceCount == 1) {
                val wasActive = normAddr in measuringAddresses
                val resumeRate = if (wasActive && syncOutputRate == 120) syncOutputRate else STREAM_OUTPUT_RATE_HZ
                dev.setOutputRate(if (wasActive) resumeRate else syncOutputRate)
                dev.measurementMode = desiredMode
                mainHandler.postDelayed({
                    if (wasActive) {
                        if (!shouldHandleReconnect) return@postDelayed
                        if (dev.startMeasuring()) {
                            markDevicesMeasuring(listOf(dev))
                            lastReconnectHandledAt[normAddr] = System.currentTimeMillis()
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
                val wasActive = normAddr in measuringAddresses
                if (wasActive) {
                    if (!shouldHandleReconnect) return@post
                    if (dev.isSynced) {
                        appendSyncLog("[$addr] 重新连接，保持 SDK 同步测量状态")
                    } else {
                        appendSyncLog("[$addr] 重新连接，恢复测量…")
                        dev.setOutputRate(STREAM_OUTPUT_RATE_HZ)
                        dev.measurementMode = desiredMode
                        if (dev.startMeasuring()) {
                            markDevicesMeasuring(listOf(dev))
                        }
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
                    } else if (isReconnect) {
                        if (!dev.isSynced) {
                            if (_deviceSyncStates.value[normAddr] == true) {
                                appendSyncLog("[$addr] 回连初始化中，保持已确认同步状态")
                            } else {
                                _deviceSyncStates.value =
                                    _deviceSyncStates.value.toMutableMap().also {
                                        it[normAddr] = false
                                    }
                                appendSyncLog("[$addr] 重新连接但硬件同步未确认，请重新同步该运动员左右脚")
                            }
                            refreshGlobalSyncFromConnectedDevices()
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
            markDevicesMeasuring(targetDevices)
            appendSyncLog("测量已启动（${targetDevices.size} 台设备）")
        }, 500)
    }

    private fun applyModeAndStart(targetDevices: List<DotDevice> = initializedDevices()) {
        if (targetDevices.isEmpty()) return
        val sid = connectSessionId
        mainHandler.post {
            if (sid != connectSessionId) return@post
            // 与官方一致：仅在已测量时才发 stopMeasuring，避免向从未启动测量的设备发无效 GATT 写
            val measuringTargets = targetDevices.filter {
                normalizeAddress(it.address.orEmpty()) in measuringAddresses
            }
            measuringTargets.forEach { it.stopMeasuring() }
            unmarkDevicesMeasuring(measuringTargets)
            // 实时 BLE 流式采集固定 ODR（与官方一致）；离线/同步仍可用 syncOutputRate=120
            targetDevices.forEach { dev -> dev.setOutputRate(STREAM_OUTPUT_RATE_HZ) }
            targetDevices.forEach { dev -> dev.measurementMode = desiredMode }
            mainHandler.postDelayed({
                if (sid != connectSessionId) return@postDelayed
                val currentProfile = _filterProfile.value
                targetDevices.forEach { dev -> applyFilterProfileToDevice(dev, currentProfile) }
                targetDevices.forEach { dev -> dev.startMeasuring() }
                markDevicesMeasuring(targetDevices)
            }, 2000)
        }
    }

    override fun onSyncStatusUpdate(address: String?, isSynced: Boolean) {
        val addr = address ?: return
        val norm = normalizeAddress(addr)
        mainHandler.post {
            if (
                !isSynced &&
                (
                    deferNegativeSyncStatus(norm) ||
                        (
                            _deviceSyncStates.value[norm] == true &&
                                SystemClock.elapsedRealtime() -
                                (syncConfirmedAtByDevice[norm] ?: 0L) <
                                SYNC_RESULT_SETTLE_MS
                            )
                    )
            ) {
                logLinkDiag(addr, "deferred transient sync-status=false")
                return@post
            }
            _deviceSyncStates.value = _deviceSyncStates.value.toMutableMap().also {
                it[norm] = isSynced
            }
            if (!isSynced) syncConfirmedAtByDevice.remove(norm)
            refreshGlobalSyncFromConnectedDevices()
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
                .filter { device ->
                    shouldPollRssiForDevice(
                        isConnected = device.connectionState == DotDevice.CONN_STATE_CONNECTED,
                        backgroundReadsPaused = backgroundReadsPaused,
                        isSyncing = _isSyncing.value,
                        isExportTarget =
                            normalizeAddress(device.address.orEmpty()) in exportTargetAddresses,
                    )
                }
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
    fun isDeviceInitialized(address: String): Boolean =
        normalizeAddress(address) in initDoneAddresses

    private fun isDeviceReadyForSync(device: DotDevice): Boolean {
        val norm = normalizeAddress(device.address.orEmpty())
        val stableForMs = connectedSinceAtMs[norm]?.let {
            SystemClock.elapsedRealtime() - it
        } ?: 0L
        return isSyncCommandReady(
            isConnected = device.connectionState == DotDevice.CONN_STATE_CONNECTED,
            initializedThisConnection = norm in initDoneAddresses,
            initializedBefore = norm in initializedOnceAddresses,
            connectedStableMs = stableForMs,
            reconnectStableRequirementMs = RECONNECT_SYNC_READY_STABLE_MS,
        )
    }

    fun areDevicesReadyForSync(targetAddresses: Set<String>): Boolean {
        val normalizedTargets = targetAddresses.map(::normalizeAddress).toSet()
        val targetDevices = devicesForAddresses(normalizedTargets)
        return targetDevices.size == normalizedTargets.size &&
            targetDevices.size >= 2 &&
            targetDevices.all(::isDeviceReadyForSync)
    }

    fun syncReadinessDescription(targetAddresses: Set<String>): String {
        val normalizedTargets = targetAddresses.map(::normalizeAddress).toSet()
        val targetDevices = devicesForAddresses(normalizedTargets)
        val found = targetDevices.map { normalizeAddress(it.address.orEmpty()) }.toSet()
        val missing = normalizedTargets - found
        if (missing.isNotEmpty()) {
            return "未找到设备 ${missing.joinToString { it.takeLast(4) }}"
        }
        val disconnected = targetDevices.filter {
            it.connectionState != DotDevice.CONN_STATE_CONNECTED
        }
        if (disconnected.isNotEmpty()) {
            return "设备 ${disconnected.joinToString { it.address.orEmpty().takeLast(4) }} 尚未连接"
        }
        val initializing = targetDevices.filterNot(::isDeviceReadyForSync)
        if (initializing.isNotEmpty()) {
            return "设备 ${initializing.joinToString { it.address.orEmpty().takeLast(4) }} 正在初始化"
        }
        return "设备已就绪"
    }

    fun setOutputRate(rate: Int) { devices.forEach { it.setOutputRate(rate) } }
    /** 立即向所有已连接设备写入目标采样率（用户点击选择按钮时调用） */
    fun setAllDevicesOutputRate(rate: Int) {
        if (!canWriteDeviceParameters()) {
            appendSyncLog("当前已进入测量/同步状态，请先停止采集或解除同步再修改采样率")
            return
        }
        mainHandler.post { devices.forEach { d -> d.setOutputRate(rate) } }
    }
    fun prepareSyncParameters(rate: Int, filterMode: Int): SyncParameterPrepareResult =
        prepareSyncParametersForDevices(
            devices.mapNotNull { it.address }.map(::normalizeAddress).toSet(),
            rate,
            filterMode,
        )

    fun prepareSyncParametersForDevices(
        targetAddresses: Set<String>,
        rate: Int,
        filterMode: Int,
    ): SyncParameterPrepareResult {
        val devList = devicesForAddresses(targetAddresses)
        if (devList.size != targetAddresses.map(::normalizeAddress).toSet().size || devList.isEmpty()) {
            appendSyncLog("同步组设备不完整，请保持设备连接后重试")
            return SyncParameterPrepareResult(success = false, wroteParameters = false, waitMsBeforeSync = 0L)
        }
        if (_isSyncing.value || measurementStarted) {
            appendSyncLog("当前已进入测量/同步状态，请先停止采集再修改同步参数")
            return SyncParameterPrepareResult(success = false, wroteParameters = false, waitMsBeforeSync = 0L)
        }
        if (devList.all { it.isSynced }) {
            return SyncParameterPrepareResult(success = true, wroteParameters = false, waitMsBeforeSync = 0L)
        }
        if (devList.any { it.isSynced }) {
            appendSyncLog("同步组状态不一致，将先解除该组同步后重新同步")
            return SyncParameterPrepareResult(success = true, wroteParameters = false, waitMsBeforeSync = 0L)
        }
        pauseBackgroundReads()
        syncSessionStartedAtMs = SystemClock.elapsedRealtime()
        syncAttemptStartedAtMs = 0L
        logSyncTiming(
            "prepareSyncParameters begin rate=${rate}Hz filter=$filterMode " +
                "devices=${devList.joinToString { it.address.orEmpty() }}"
        )
        syncOutputRate = rate
        _filterProfile.value = filterMode

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
        !_isSyncing.value && devices.none { it.isSynced } && !measurementStarted

    fun confirmExistingSyncIfAllConnected(): Boolean {
        val connected = devices.filter { dev ->
            dev.connectionState == DotDevice.CONN_STATE_CONNECTED &&
                !dev.address.isNullOrBlank()
        }
        if (
            connected.size < 2 ||
            connected.size != targetDeviceCount ||
            !connected.all { it.isSynced }
        ) return false

        mainHandler.post {
            _needsSync.value = false
            _isSynced.value = true
            _isSyncing.value = false
            _syncProgress.value = 100
            val now = SystemClock.elapsedRealtime()
            _deviceSyncStates.value = _deviceSyncStates.value.toMutableMap().also { states ->
                connected.forEach { device ->
                    val norm = normalizeAddress(device.address.orEmpty())
                    states[norm] = true
                    syncConfirmedAtByDevice[norm] = now
                }
            }
            connected.forEach { device -> runCatching { device.stopMeasuring() } }
            unmarkDevicesMeasuring(connected)
            appendSyncLog("检测到各运动员设备已同步，已进入录制准备状态")
        }
        return true
    }

    fun startMeasuring() {
        devices.forEach { it.startMeasuring() }
        markDevicesMeasuring(devices)
    }
    fun stopMeasuring() {
        devices.forEach { it.stopMeasuring() }
        measuringAddresses.clear()
        measurementStarted = false
        stopLossReporting()
        mainHandler.post { _state.value = CollectionState.Connecting }
    }

    fun stopMeasuring(targetAddresses: Set<String>) {
        val targetDevices = devicesForAddresses(targetAddresses).filter { device ->
            normalizeAddress(device.address.orEmpty()) in measuringAddresses
        }
        targetDevices.forEach { it.stopMeasuring() }
        unmarkDevicesMeasuring(targetDevices)
    }

    // ── 直接采集 ──

    fun startDirectMeasurement() {
        mainHandler.post {
            val ready = initializedDevices()
            if (ready.isEmpty()) { appendSyncLog("无已就绪设备，请先完成连接"); return@post }
            if (_isSyncing.value) { appendSyncLog("同步进行中，请等待或停止同步"); return@post }
            if (_isSynced.value) {
                markDevicesMeasuring(ready)
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
    fun startSync(): Boolean = startSync(
        devices.mapNotNull { it.address }.map(::normalizeAddress).toSet()
    )

    fun startSync(targetAddresses: Set<String>): Boolean =
        startSyncInternal(targetAddresses)

    /**
     * 官方 §4.9 同步流程：
     * 1. stopMeasuring（如果在测量中）
     * 2. 检查 isSynced → 只对已同步设备 stopSyncing，等回调
     * 3. setRootDevice(true)
     * 4. DotSyncManager.startSyncing(deviceList, requestCode)
     *
     * 采样率和滤波器由用户点击选择按钮时立即写入设备，此处不再重复写入（避免 GATT 并发）。
     */
    private fun startSyncInternal(
        targetAddresses: Set<String>,
        retryingIncompleteResult: Boolean = false,
    ): Boolean {
        val normalizedTargets = targetAddresses.map(::normalizeAddress).toSet()
        val targetDevices = devicesForAddresses(normalizedTargets)
        if (syncSessionStartedAtMs == 0L) syncSessionStartedAtMs = SystemClock.elapsedRealtime()
        logSyncTiming("startSyncInternal called targets=${normalizedTargets.joinToString()}")
        if (_isSyncing.value && !retryingIncompleteResult) {
            val sameTargets = normalizedTargets == _syncTargetAddresses.value
            appendSyncLog(
                if (sameTargets) "该运动员同步正在进行中，请稍候…"
                else "其他运动员设备正在同步，请稍候…"
            )
            logSyncTiming("startSyncInternal active sameTargets=$sameTargets")
            return sameTargets
        }
        if (targetDevices.isEmpty()) {
            resumeBackgroundReads()
            appendSyncLog("没有可同步的设备")
            logSyncTiming("startSyncInternal rejected: no devices")
            return false
        }
        if (targetDevices.size != normalizedTargets.size || targetDevices.size < 2) {
            resumeBackgroundReads()
            appendSyncLog("同步组必须包含两台已连接设备")
            logSyncTiming(
                "startSyncInternal rejected: targets=${normalizedTargets.size} found=${targetDevices.size}"
            )
            return false
        }
        val disconnectedDevices = targetDevices.filter {
            it.connectionState != DotDevice.CONN_STATE_CONNECTED
        }
        if (disconnectedDevices.isNotEmpty()) {
            resumeBackgroundReads()
            appendSyncLog("部分设备未连接，请等待后重试")
            logSyncTiming(
                "startSyncInternal rejected: disconnected=" +
                    disconnectedDevices.joinToString { device ->
                        "${device.address ?: "?"}:${connectionStateLabel(device.connectionState)}"
                    }
            )
            return false
        }

        // 额外检查：所有设备必须完成初始化（onDotInitDone 已调用）才能同步
        val uninitAddrs = targetDevices.filterNot(::isDeviceReadyForSync).mapNotNull { it.address }
        if (uninitAddrs.isNotEmpty()) {
            resumeBackgroundReads()
            appendSyncLog("设备尚未就绪：$uninitAddrs，请等待初始化后重试")
            logSyncTiming("startSyncInternal rejected: init pending=$uninitAddrs")
            return false
        }

        _isSyncing.value = true
        if (!retryingIncompleteResult) {
            _syncProgress.value = 0
            activeSyncRetryCount = 0
        }
        activeSyncDevices = targetDevices
        _syncTargetAddresses.value = normalizedTargets
        pauseBackgroundReads()

        // 官方 §4.10.3：有条件 stopMeasuring
        val measuringTargets = targetDevices.filter {
            normalizeAddress(it.address.orEmpty()) in measuringAddresses
        }
        if (measuringTargets.isNotEmpty()) {
            measuringTargets.forEach { try { it.stopMeasuring() } catch (_: Exception) {} }
            unmarkDevicesMeasuring(measuringTargets)
        }
        syncManager = null

        // 官方 §4.9.1：检查 isSynced，只对已同步设备 stopSyncing
        val syncedDevices = targetDevices.filter { it.isSynced }
        if (syncedDevices.size == targetDevices.size) {
            appendSyncLog("该运动员左右脚已同步，保持当前状态")
            logSyncTiming("all devices already synced, skip startSyncing")
            _isSyncing.value = false
            _syncProgress.value = 100
            _syncTargetAddresses.value = emptySet()
            activeSyncDevices = emptyList()
            refreshDeviceSyncStates()
            refreshGlobalSyncFromConnectedDevices()
            _state.value = CollectionState.Connecting
            resumeBackgroundReads()
            return true
        }
        if (syncedDevices.isNotEmpty()) {
            appendSyncLog("检测到 ${syncedDevices.size} 台已同步设备，先解除…")
            logSyncTiming("stopSyncing before resync syncedDevices=${syncedDevices.size}")
            var proceeded = false
            val preEpoch = syncEpoch
            val pendingStops = syncedDevices.mapNotNull { device ->
                device.address?.let(::normalizeAddress)
            }.toMutableSet()
            val stopRetryCounts = mutableMapOf<String, Int>()
            var stopFailed = false

            fun proceedToSync() {
                if (proceeded) return
                proceeded = true
                if (preEpoch == syncEpoch) doStartSyncing()
            }

            fun failBeforeSync(message: String) {
                if (proceeded || preEpoch != syncEpoch) return
                proceeded = true
                _isSyncing.value = false
                _syncProgress.value = 0
                _syncTargetAddresses.value = emptySet()
                activeSyncDevices = emptyList()
                refreshGlobalSyncFromConnectedDevices()
                _needsSync.value = true
                resumeBackgroundReads()
                appendSyncLog(message)
                logSyncTiming("pre-sync stop failed: $message")
            }

            lateinit var stopManager: DotSyncManager
            stopManager = DotSyncManager.getInstance(object : DotSyncCallback {
                override fun onSyncingStarted(address: String?, isSuccess: Boolean, requestCode: Int) {}
                override fun onSyncingProgress(progress: Int, requestCode: Int) {}
                override fun onSyncingResult(address: String?, isSuccess: Boolean, requestCode: Int) {}
                override fun onSyncingDone(syncingResultMap: HashMap<String, Boolean>, isSuccess: Boolean, requestCode: Int) {}
                override fun onSyncingStopped(address: String?, isSuccess: Boolean, requestCode: Int) {
                    mainHandler.post {
                        if (preEpoch != syncEpoch) return@post
                        appendSyncLog("[${address ?: "?"}] 解除同步 ${if (isSuccess) "✓" else "✗"}")
                        logSyncTiming("stopSyncing callback address=${address ?: "?"} success=$isSuccess")
                        val norm = address?.let(::normalizeAddress)
                        if (!isSuccess && norm != null && norm in pendingStops) {
                            val retryCount = stopRetryCounts[norm] ?: 0
                            val retryDevice = syncedDevices.firstOrNull {
                                normalizeAddress(it.address.orEmpty()) == norm
                            }
                            if (
                                retryCount < 2 &&
                                retryDevice?.connectionState == DotDevice.CONN_STATE_CONNECTED
                            ) {
                                stopRetryCounts[norm] = retryCount + 1
                                appendSyncLog("[$address] BLE 通道繁忙，正在重试解除同步…")
                                mainHandler.postDelayed({
                                    if (
                                        preEpoch == syncEpoch &&
                                        !proceeded &&
                                        norm in pendingStops
                                    ) {
                                        stopManager.stopSyncing(arrayListOf(retryDevice))
                                    }
                                }, 500L)
                                return@post
                            }
                            stopFailed = true
                        }
                        norm?.let(pendingStops::remove)
                        if (pendingStops.isEmpty()) {
                            if (stopFailed) {
                                failBeforeSync("部分设备解除同步失败，请保持设备靠近后重试")
                            } else {
                                mainHandler.postDelayed({
                                    if (preEpoch == syncEpoch) proceedToSync()
                                }, 1_000L)
                            }
                        }
                    }
                }
            })
            val stopSent = stopManager.stopSyncing(ArrayList(syncedDevices))
            logSyncTiming("stopSyncing(targets) returned $stopSent")

            // 未收到全部解除回调时不能继续同步，否则仍处于同步状态的设备会让新一轮失败。
            mainHandler.postDelayed({
                if (
                    preEpoch == syncEpoch &&
                    !proceeded &&
                    pendingStops.isNotEmpty()
                ) {
                    failBeforeSync(
                        "解除同步超时：${pendingStops.joinToString()}，请重新同步"
                    )
                }
            }, 8_000L)
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
        val expectedTargets = _syncTargetAddresses.value
        val syncDevices = activeSyncDevices
            .filter { it.connectionState == DotDevice.CONN_STATE_CONNECTED }
            .sortedBy { LongJumpDeviceRoles.roleSortIndex(it.address.orEmpty()) }
        if (
            syncDevices.size != expectedTargets.size ||
            syncDevices.size < 2 ||
            syncDevices.any { normalizeAddress(it.address.orEmpty()) !in expectedTargets }
        ) {
            abortActiveSync("同步启动前设备连接状态已变化")
            return
        }

        // isRootDevice 是 SDK 本地角色字段，不会写 GATT。每轮必须先清空旧角色，
        // 再设置唯一 root，避免断联重排或失败重试后遗留多个 root。
        clearSyncRootRoles()
        syncDevices.first().isRootDevice = true
        syncDevices.forEach { d ->
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
            clearSyncRootRoles(syncDevices)
            syncManager = null
            _initProgress.value = Pair(initDoneAddresses.size, targetDeviceCount)

            if (succeeded) {
                activeSyncRetryCount = 0
                _isSyncing.value = false
                _syncProgress.value = 100
                syncConfirmedAtMs = SystemClock.elapsedRealtime()
                _deviceSyncStates.value = _deviceSyncStates.value.toMutableMap().also { states ->
                    syncDevices.forEach { dev ->
                        dev.address?.let {
                            val norm = normalizeAddress(it)
                            states[norm] = true
                            syncConfirmedAtByDevice[norm] = syncConfirmedAtMs
                        }
                    }
                }
                syncDevices.forEach { dev -> try { dev.stopMeasuring() } catch (_: Exception) {} }
                unmarkDevicesMeasuring(syncDevices)
                resumeBackgroundReads()
                _syncTargetAddresses.value = emptySet()
                activeSyncDevices = emptyList()
                refreshGlobalSyncFromConnectedDevices()
                appendSyncLog("$desc — 该运动员左右脚已同步")
            } else {
                activeSyncRetryCount = 0
                syncConfirmedAtMs = 0L
                _isSyncing.value = false
                _syncProgress.value = 0
                _syncTargetAddresses.value = emptySet()
                activeSyncDevices = emptyList()
                refreshDeviceSyncStates()
                refreshGlobalSyncFromConnectedDevices()
                appendSyncLog("$desc — 请重新同步该运动员设备")
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
                        _syncProgress.value = maxOf(
                            _syncProgress.value,
                            progress.coerceIn(0, 100),
                        )
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
                    } else if (
                        total == syncDevices.size &&
                        shouldRetryIncompleteSync(
                            succeededCount = count,
                            totalCount = total,
                            retryCount = activeSyncRetryCount,
                            maxRetries = MAX_TRANSIENT_SYNC_RETRIES,
                        )
                    ) {
                        if (syncFinalized) return@post
                        syncFinalized = true
                        cancelSyncTimeout()
                        clearSyncRootRoles(syncDevices)
                        syncManager = null
                        activeSyncRetryCount++
                        appendSyncLog(
                            if (count == 0) {
                                "设备重连确认延迟，正在继续同步…"
                            } else {
                                "同步确认不完整 $count/$total，正在自动恢复…"
                            }
                        )
                        logSyncTiming(
                            "incomplete result $count/$total; retry=$activeSyncRetryCount/" +
                                MAX_TRANSIENT_SYNC_RETRIES
                        )
                        mainHandler.postDelayed({
                            if (myEpoch != syncEpoch || !_isSyncing.value) {
                                return@postDelayed
                            }
                            startSyncInternal(
                                targetAddresses = expectedTargets,
                                retryingIncompleteResult = true,
                            )
                        }, TRANSIENT_SYNC_RETRY_DELAY_MS)
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
        val started = syncManager?.startSyncing(ArrayList(syncDevices), SYNCING_REQUEST_CODE) ?: false
        logSyncTiming("startSyncing returned $started")
        if (!started) {
            clearSyncRootRoles(syncDevices)
            syncManager = null
            finalizeSyncOnce(false, "同步启动失败 ✗")
        }
    }

    private fun requestStopSyncing(
        targetDevices: List<DotDevice>,
        logSummary: Boolean,
    ) {
        val stopTargets = targetDevices.filter {
            it.connectionState == DotDevice.CONN_STATE_CONNECTED
        }
        val pendingStops = stopTargets.mapNotNull { device ->
            device.address?.let(::normalizeAddress)
        }.toMutableSet()
        var stopCompleted = false
        var stopFailed = false

        // syncManager 在同步完成后被清为 null，需重新获取 DotSyncManager 实例才能真正停止设备同步
        val mgr = syncManager ?: DotSyncManager.getInstance(object : DotSyncCallback {
            override fun onSyncingStarted(address: String?, isSuccess: Boolean, requestCode: Int) {}
            override fun onSyncingProgress(progress: Int, requestCode: Int) {}
            override fun onSyncingResult(address: String?, isSuccess: Boolean, requestCode: Int) {}
            override fun onSyncingDone(syncingResultMap: HashMap<String, Boolean>, isSuccess: Boolean, requestCode: Int) {}
            override fun onSyncingStopped(address: String?, isSuccess: Boolean, requestCode: Int) {
                mainHandler.post {
                    if (stopCompleted) return@post
                    appendSyncLog("[${address ?: "?"}] 解除同步 ${if (isSuccess) "✓" else "✗"}")
                    if (!isSuccess) stopFailed = true
                    address?.let(::normalizeAddress)?.let(pendingStops::remove)
                    if (pendingStops.isEmpty()) {
                        stopCompleted = true
                        if (logSummary) {
                            appendSyncLog(
                                if (stopFailed) "部分设备解除同步失败，请保持设备靠近后重试"
                                else "该运动员左右脚已解除同步"
                            )
                        }
                    }
                }
            }
        })
        if (logSummary) {
            appendSyncLog("正在解除同步，等待设备确认…")
        }
        val accepted = if (stopTargets.isNotEmpty()) {
            mgr.stopSyncing(ArrayList(stopTargets))
        } else {
            mgr.stopSyncing()
        }
        logSyncTiming(
            "stopSyncing request accepted=$accepted targets=" +
                stopTargets.joinToString { it.address.orEmpty() }
        )
        syncManager = null
        if (pendingStops.isNotEmpty()) {
            mainHandler.postDelayed({
                if (!stopCompleted && pendingStops.isNotEmpty()) {
                    stopCompleted = true
                    if (logSummary) {
                        appendSyncLog(
                            "解除同步超时：${pendingStops.joinToString { it.takeLast(4) }}，请保持设备靠近后重试"
                        )
                    }
                }
            }, 8_000L)
        }
    }

    fun stopSync() {
        stopSync(devices.mapNotNull { it.address }.map(::normalizeAddress).toSet())
    }

    fun stopSync(targetAddresses: Set<String>) {
        val targetDevices = devicesForAddresses(targetAddresses)
        if (targetDevices.isEmpty()) {
            appendSyncLog("没有可解除同步的设备")
            return
        }
        requestStopSyncing(targetDevices, logSummary = true)
        clearSyncRootRoles(targetDevices)
        // 同时停止测量，清理本地采集状态
        val measuringTargets = targetDevices.filter {
            normalizeAddress(it.address.orEmpty()) in measuringAddresses
        }
        if (measuringTargets.isNotEmpty()) {
            measuringTargets.forEach { try { it.stopMeasuring() } catch (_: Exception) {} }
            unmarkDevicesMeasuring(measuringTargets)
        }
        ++syncEpoch  // 使任何残留的 epoch 回调失效
        mainHandler.post {
            syncConfirmedAtMs = 0L
            _isSyncing.value = false
            _syncProgress.value = 0
            _syncTargetAddresses.value = emptySet()
            activeSyncDevices = emptyList()
            _state.value = CollectionState.Connecting
            _deviceSyncStates.value = _deviceSyncStates.value.toMutableMap().also { states ->
                targetDevices.forEach { device ->
                    device.address?.let {
                        val norm = normalizeAddress(it)
                        states[norm] = false
                        syncConfirmedAtByDevice.remove(norm)
                    }
                }
            }
            refreshGlobalSyncFromConnectedDevices()
            resumeBackgroundReads()
        }
    }

    private fun abortActiveSync(reason: String) {
        if (!_isSyncing.value) return
        ++syncEpoch
        cancelSyncTimeout()
        clearSyncRootRoles()
        syncManager = null
        syncConfirmedAtMs = 0L
        activeSyncRetryCount = 0
        _isSyncing.value = false
        _syncProgress.value = 0
        _syncTargetAddresses.value = emptySet()
        activeSyncDevices = emptyList()
        _needsSync.value = devices.size > 1
        refreshDeviceSyncStates()
        refreshGlobalSyncFromConnectedDevices()
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
    private fun applyOfflineModeSettings(rate: Int, filterMode: Int) {
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
                unmarkDevicesMeasuring(devList)
            }
            devList.forEach { dev ->
                dev.setOutputRate(rate)
                dev.measurementMode = desiredMode
                applyFilterProfileToDevice(dev, filterMode)
            }
            val profileLabel = if (filterMode == 1) "Dynamic" else "General"
            appendSyncLog("离线准备：${rate}Hz / $profileLabel，未开始录制")
        }
    }

    fun prepareOfflineModeSettings(rate: Int, filterMode: Int) {
        applyOfflineModeSettings(rate, filterMode)
    }

    /** Flash recording is device-local and must not share the BLE real-time measurement channel. */
    fun prepareDevicesForFlashRecording(targetAddresses: Set<String>): Boolean {
        val normalizedTargets = targetAddresses.map(::normalizeAddress).toSet()
        val devList = devicesForAddresses(normalizedTargets)
        if (
            normalizedTargets.isEmpty() ||
            devList.size != normalizedTargets.size ||
            devList.any { device ->
                val norm = normalizeAddress(device.address.orEmpty())
                device.connectionState != DotDevice.CONN_STATE_CONNECTED ||
                    norm !in initDoneAddresses ||
                    _deviceSyncStates.value[norm] != true
            }
        ) {
            return false
        }

        appendSyncLog("离线录制通道已就绪（${devList.size} 台设备）")
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
