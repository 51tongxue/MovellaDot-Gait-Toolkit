package com.buct.xsens.dot.engine

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Handler
import android.os.Looper
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
        private const val SYNCING_REQUEST_CODE = 1025
        /** BLE 实时波形流 ODR（与官方一致）；120Hz 仅同步+离线 Flash */
        private const val STREAM_OUTPUT_RATE_HZ = 60
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var sdkScanner: DotScanner? = null

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

    private val _needsSync = MutableStateFlow(false)
    val needsSync: StateFlow<Boolean> = _needsSync.asStateFlow()

    private val _initProgress = MutableStateFlow(Pair(0, 0))
    val initProgress: StateFlow<Pair<Int, Int>> = _initProgress.asStateFlow()

    // 0 = General（默认），1 = Dynamic（与官方 setSensorProfile 入参含义一致）
    private val _filterProfile = MutableStateFlow(0)
    val filterProfile: StateFlow<Int> = _filterProfile.asStateFlow()

    // ── 内部状态 ──
    private val devices       = CopyOnWriteArrayList<DotDevice>()
    private val addressToIndex = ConcurrentHashMap<String, Int>()
    private val initDoneAddresses = mutableSetOf<String>()

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
    private var syncRetryCount = 0
    private val maxSyncRetries = 3
    // 同步/离线录制目标采样率，与 UI 默认值保持一致（120Hz），startSync 前由 ViewModel 覆写
    @Volatile var syncOutputRate = 120
    private var syncTimeoutRunnable: Runnable? = null
    private var onDataCallback: ((Int, SensorData) -> Unit)? = null
    @Volatile private var lastHeadingAction = "reset"

    // ── 工具 ──

    fun setOnDataCallback(cb: (Int, SensorData) -> Unit) { onDataCallback = cb }

    private fun normalizeAddress(addr: String): String =
        addr.replace(":", "").replace("-", "").uppercase()

    private fun resolveDeviceIndex(address: String): Int? {
        val norm = normalizeAddress(address)
        addressToIndex[norm]?.let { return it }
        val idx = devices.indexOfFirst { normalizeAddress(it.address ?: "") == norm }
        if (idx >= 0) { addressToIndex[norm] = idx; return idx }
        return null
    }

    private fun initializedDevices(): List<DotDevice> =
        devices.filter { normalizeAddress(it.address ?: "") in initDoneAddresses }

    private fun appendSyncLog(msg: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        _syncLog.value = _syncLog.value + "[$t] $msg"
    }

    // ── 扫描 ──

    @Suppress("MissingPermission")
    fun startScan() {
        if (sdkScanner == null) {
            sdkScanner = DotScanner(context, this)
            sdkScanner!!.setScanMode(ScanSettings.SCAN_MODE_BALANCED)
        }
        _isScanning.value = sdkScanner!!.startScan()
        if (_isScanning.value) {
            mainHandler.postDelayed({ stopScan() }, 12_000)
        }
    }

    fun stopScan() { sdkScanner?.stopScan(); _isScanning.value = false }

    @Suppress("MissingPermission")
    override fun onDotScanned(device: BluetoothDevice?, rssi: Int) {
        val dev = device ?: return
        mainHandler.post {
            val name = dev.name ?: "Movella DOT"
            val list = _scannedDevices.value.toMutableList()
            if (list.none { it.address == dev.address }) {
                list.add(ScannedDevice(dev, name, dev.address, rssi))
                _scannedDevices.value = list
            }
        }
    }

    // ── 连接 ──

    fun connectDevices(selected: List<ScannedDevice>) {
        if (selected.isEmpty()) return
        stopScan()
        connectSessionId++
        targetDeviceCount   = selected.size
        measurementStarted  = false
        _needsSync.value    = selected.size > 1
        _state.value        = CollectionState.Connecting
        devices.clear(); addressToIndex.clear(); initDoneAddresses.clear()
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
                appendSyncLog("连接超时：有 $missing 台设备未就绪，建议断开后重新扫描连接")
            }
        }, 20_000L)
    }

    fun disconnectAll() {
        connectSessionId++; syncEpoch++
        stopLossReporting()
        cancelSyncTimeout()
        syncManager?.stopSyncing(); syncManager = null

        val targets = devices.toList()
        targets.forEach { it.stopMeasuring() }
        mainHandler.postDelayed({ targets.forEach { it.disconnect() } }, 300)

        devices.clear(); addressToIndex.clear(); initDoneAddresses.clear()
        addrToIdx.clear(); addrToNorm.clear(); lastPktCounter.clear()
        targetDeviceCount = 0; measurementStarted = false
        _initProgress.value    = Pair(0, 0)
        _connectedDevices.value = emptyList()
        _state.value           = CollectionState.Idle
        _isSynced.value        = false
        _isSyncing.value       = false
        _needsSync.value       = false
        _recvCount.value       = 0
        totalRecvCount.set(0L)
        lastUiUpdateAtom.set(0L)
        pendingSensorData.clear()
        lossStats.clear(); recvStats.clear()
        _sensorData.value      = emptyMap()
        waveDataMap.clear()
        _waveData.value        = emptyMap()
    }

    // ── SDK 设备回调 ──

    override fun onDotConnectionChanged(address: String?, state: Int) {
        mainHandler.post {
            val addr = address ?: return@post
            when (state) {
                DotDevice.CONN_STATE_CONNECTED -> {
                    resolveDeviceIndex(addr)
                    if (addrToIdx[addr] == null) {
                        val norm = normalizeAddress(addr)
                        val idx = addressToIndex[norm]
                        if (idx != null) {
                            addrToIdx[addr] = idx
                            addrToNorm[addr] = norm
                        }
                    }
                    _connectedDevices.value = devices.mapNotNull {
                        it.address?.takeIf { a -> a.isNotBlank() && it.connectionState == DotDevice.CONN_STATE_CONNECTED }
                    }
                    if (!measurementStarted) _state.value = CollectionState.Connecting
                }
                DotDevice.CONN_STATE_DISCONNECTED -> {
                    if (_isSyncing.value) return@post
                    val norm = normalizeAddress(addr)
                    initDoneAddresses.remove(norm)
                    _connectedDevices.value = devices.mapNotNull {
                        it.address?.takeIf { a -> a.isNotBlank() && it.connectionState == DotDevice.CONN_STATE_CONNECTED }
                    }
                    _initProgress.value = Pair(initDoneAddresses.size, targetDeviceCount)
                    if (devices.none { it.connectionState == DotDevice.CONN_STATE_CONNECTED })
                        _state.value = CollectionState.Idle
                }
            }
        }
    }

    override fun onDotServicesDiscovered(address: String?, status: Int) {}
    override fun onDotFirmwareVersionRead(address: String?, version: String?) {}
    override fun onDotTagChanged(address: String?, tag: String?) {}
    override fun onDotBatteryChanged(address: String?, status: Int, percentage: Int) {}
    override fun onDotButtonClicked(address: String?, timestamp: Long) {}
    override fun onDotButtonDoubleClicked(address: String?, timestamp: Long) {}
    override fun onDotButtonTripleClicked(address: String?, timestamp: Long) {}
    override fun onDotPowerSavingTriggered(address: String?) {}
    override fun onReadRemoteRssi(address: String?, rssi: Int) {}
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

            // 必须在任何BLE操作之前检查：DotSyncManager的readAck在30ms后也排队到主线程
            // 若此处发起GATT write，会占用GATT导致readAck失败→同步75%失败
            if (_isSyncing.value) return@post

            initDoneAddresses.add(normAddr)
            _initProgress.value = Pair(initDoneAddresses.size, targetDeviceCount)

            if (targetDeviceCount == 1) {
                val wasActive = measurementStarted  // 重连场景下为 true
                dev.setOutputRate(if (wasActive) STREAM_OUTPUT_RATE_HZ else syncOutputRate)
                dev.measurementMode = desiredMode
                mainHandler.postDelayed({
                    if (wasActive) {
                        if (dev.startMeasuring()) {
                            startLossReporting()
                            _state.value = CollectionState.Measuring
                            appendSyncLog("[$addr] 重新连接，测量已恢复")
                            onDeviceReconnected?.invoke(addr)
                        } else {
                            appendSyncLog("[$addr] 启动测量失败，请断开重连")
                        }
                    } else {
                        // 离线模式断联重连，不再强制启动测量
                        appendSyncLog("[$addr] 已恢复原设定采样率 (${syncOutputRate}Hz)")
                        onDeviceReconnected?.invoke(addr)
                    }
                }, 2000)
            } else {
                if (measurementStarted) {
                    // 此分支：其他设备仍在测量中，仅对刚重连的设备独立恢复
                    appendSyncLog("[$addr] 重新连接，恢复测量…")
                    dev.setOutputRate(STREAM_OUTPUT_RATE_HZ)
                    dev.measurementMode = desiredMode
                    dev.startMeasuring()
                    onDeviceReconnected?.invoke(addr)
                } else {
                    // 离线模式 / 同步后断联重连，恢复预设的输出刷新率 (如 120Hz)
                    dev.setOutputRate(syncOutputRate)
                    dev.measurementMode = desiredMode
                    onDeviceReconnected?.invoke(addr)
                    
                    if (initDoneAddresses.size == targetDeviceCount && !_isSynced.value) {
                        // 全部设备就绪且还未同步：统一应用当前目标采样率，确保各设备 ODR 一致
                        devices.forEach { d -> d.setOutputRate(syncOutputRate) }
                        mainHandler.postDelayed({
                            devices.forEach { d ->
                                appendSyncLog("[${d.address}] rate=${d.currentOutputRate}Hz | filter=${d.currentFilterProfileIndex}")
                            }
                            appendSyncLog("全部 $targetDeviceCount 台设备已就绪，请选择「SDK 硬件同步」")
                        }, 300)
                    } else if (_isSynced.value) {
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

    override fun onSyncStatusUpdate(address: String?, isSynced: Boolean) {}

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
        mainHandler.post { devices.forEach { d -> d.setOutputRate(rate) } }
    }
    fun startMeasuring() { devices.forEach { it.startMeasuring() } }
    fun stopMeasuring() {
        devices.forEach { it.stopMeasuring() }
        stopLossReporting()
        mainHandler.post { _state.value = CollectionState.Connecting }
    }

    // ── 直接采集 ──

    fun startDirectMeasurement() {
        mainHandler.post {
            val ready = initializedDevices()
            if (ready.isEmpty()) { appendSyncLog("无已就绪设备，请先完成连接"); return@post }
            if (_isSyncing.value) { appendSyncLog("同步进行中，请等待或停止同步"); return@post }
            if (syncOutputRate == 120) {
                appendSyncLog("120Hz 仅支持离线采集（与官方一致），无法启动实时 BLE 流")
                return@post
            }
            if (_isSynced.value) {
                appendSyncLog("开始实时采集（已同步，BLE 流式 ${STREAM_OUTPUT_RATE_HZ}Hz）")
            } else {
                appendSyncLog("直接采集：各传感器独立计时（跳过时间同步），${STREAM_OUTPUT_RATE_HZ}Hz")
                _isSynced.value = false
                _needsSync.value = false
            }
            applyModeAndStart(ready)
        }
    }

    // ── SDK 硬件同步 ──

    fun startSync() = startSyncInternal()

    /**
     * 官方 §4.9 同步流程：
     * 1. stopMeasuring（如果在测量中）
     * 2. 检查 isSynced → 只对已同步设备 stopSyncing，等回调
     * 3. setRootDevice(true)
     * 4. DotSyncManager.startSyncing(deviceList, requestCode)
     *
     * 采样率和滤波器由用户点击选择按钮时立即写入设备，此处不再重复写入（避免 GATT 并发）。
     */
    private fun startSyncInternal() {
        syncRetryCount = 0
        if (_isSyncing.value) { appendSyncLog("同步正在进行中，请稍候…"); return }
        if (devices.isEmpty()) return
        if (devices.size < 2) {
            appendSyncLog("单设备无需 SDK 同步")
            _isSynced.value = false; applyModeAndStart(); return
        }
        if (devices.any { it.connectionState != DotDevice.CONN_STATE_CONNECTED }) {
            appendSyncLog("部分设备未连接，请等待后重试"); return
        }

        // 额外检查：所有设备必须完成初始化（onDotInitDone 已调用）才能同步
        val uninitAddrs = devices.filter {
            normalizeAddress(it.address ?: "") !in initDoneAddresses
        }.mapNotNull { it.address }
        if (uninitAddrs.isNotEmpty()) {
            appendSyncLog("设备尚未就绪：$uninitAddrs，请等待初始化后重试"); return
        }

        _isSyncing.value = true

        // 官方 §4.10.3：有条件 stopMeasuring
        if (measurementStarted) {
            devices.forEach { try { it.stopMeasuring() } catch (_: Exception) {} }
            measurementStarted = false
        }
        syncManager = null

        // 官方 §4.9.1：检查 isSynced，只对已同步设备 stopSyncing
        val syncedDevices = devices.filter { it.isSynced }
        if (syncedDevices.isNotEmpty()) {
            appendSyncLog("检测到 ${syncedDevices.size} 台已同步设备，先解除…")
            var proceeded = false
            val preEpoch = syncEpoch

            fun proceedToSync() {
                if (proceeded) return
                proceeded = true
                if (preEpoch == syncEpoch) doStartSyncing()
            }

            DotSyncManager.getInstance(object : DotSyncCallback {
                override fun onSyncingStarted(a: String?, b: Boolean, c: Int) {}
                override fun onSyncingProgress(a: Int, b: Int) {}
                override fun onSyncingResult(a: String?, b: Boolean, c: Int) {}
                override fun onSyncingDone(a: HashMap<String, Boolean>, b: Boolean, c: Int) {}
                override fun onSyncingStopped(address: String?, isSuccess: Boolean, requestCode: Int) {
                    mainHandler.post {
                        if (preEpoch != syncEpoch) return@post
                        appendSyncLog("[${address ?: "?"}] 解除同步 ${if (isSuccess) "✓" else "✗"}")
                        proceedToSync()
                    }
                }
            }).stopSyncing()

            // 超时兜底：2 秒内未收到回调则强制继续
            mainHandler.postDelayed({
                if (preEpoch == syncEpoch) {
                    appendSyncLog("解除同步超时，强制继续…")
                    proceedToSync()
                }
            }, 2000L)
            return
        }

        // 没有已同步设备，直接开始
        doStartSyncing()
    }

    private fun doStartSyncing() {
        val myEpoch = ++syncEpoch
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
            cancelSyncTimeout()
            devices.firstOrNull()?.isRootDevice = false
            syncManager = null
            _isSyncing.value = false

            if (succeeded) {
                _needsSync.value = false
                _isSynced.value = true
                _state.value = CollectionState.Connecting
                appendSyncLog("$desc — 请选择「开始实时采集」或进入离线录制")
            } else {
                _isSynced.value = false
                appendSyncLog("$desc — 请重新点击「SDK 硬件同步」重试")
                _state.value = CollectionState.Connecting
            }
        }

        // 官方 §4.9.2：获取 DotSyncManager 单例，注册回调，发起同步
        syncManager = DotSyncManager.getInstance(object : DotSyncCallback {
            override fun onSyncingStarted(address: String?, isSuccess: Boolean, requestCode: Int) {
                mainHandler.post {
                    if (myEpoch != syncEpoch) return@post
                    appendSyncLog("[${address ?: "?"}] 同步启动 ${if (isSuccess) "✓" else "✗"}")
                }
            }
            override fun onSyncingProgress(progress: Int, requestCode: Int) {
                mainHandler.post {
                    if (myEpoch != syncEpoch) return@post
                    if (progress != lastLoggedProgress) {
                        lastLoggedProgress = progress
                        appendSyncLog("同步进度: $progress%")
                    }
                }
            }
            override fun onSyncingResult(address: String?, isSuccess: Boolean, requestCode: Int) {
                mainHandler.post {
                    if (myEpoch != syncEpoch) return@post
                    appendSyncLog("[${address ?: "?"}] ${if (isSuccess) "✓ 同步成功" else "✗ 同步失败"}")
                }
            }
            override fun onSyncingDone(syncingResultMap: HashMap<String, Boolean>, isSuccess: Boolean, requestCode: Int) {
                mainHandler.post {
                    if (myEpoch != syncEpoch) return@post
                    val count = syncingResultMap.values.count { it }
                    val total = syncingResultMap.size
                    val failedAddrs = syncingResultMap.filter { !it.value }.keys
                    if (failedAddrs.isNotEmpty()) {
                        appendSyncLog("失败设备：${failedAddrs.joinToString()}")
                    }

                    if (count == total) {
                        // 全部成功
                        finalizeSyncOnce(true, "同步完成 ✓ $count/$total 全部成功")
                    } else if (syncRetryCount < maxSyncRetries) {
                        // 部分/全部失败 → 自动重试（保持 _isSyncing=true，SDK 会重新管理设备连接）
                        syncRetryCount++
                        appendSyncLog("同步失败 $count/$total，自动重试 $syncRetryCount/$maxSyncRetries…")
                        mainHandler.postDelayed({
                            if (myEpoch == syncEpoch) doStartSyncing()
                        }, 3000L)
                    } else {
                        // 耗尽重试次数，报告最终失败
                        val desc = if (count == 0) "同步失败 ✗ 0/$total" else "同步部分成功 $count/$total"
                        finalizeSyncOnce(false, desc)
                        syncRetryCount = 0
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

        appendSyncLog("开始同步，请将传感器靠拢…")
        val started = syncManager?.startSyncing(ArrayList(devices), SYNCING_REQUEST_CODE) ?: false
        if (!started) {
            appendSyncLog("startSyncing 返回 false（isInSyncing=${syncManager?.isInSyncing}）")
            _isSyncing.value = false
            _state.value = CollectionState.Connecting
        }
    }

    fun stopSync() {
        // syncManager 在同步完成后被清为 null，需重新获取 DotSyncManager 实例才能真正停止设备同步
        val mgr = syncManager ?: DotSyncManager.getInstance(object : DotSyncCallback {
            override fun onSyncingStarted(a: String?, b: Boolean, c: Int) {}
            override fun onSyncingProgress(a: Int, b: Int) {}
            override fun onSyncingResult(a: String?, b: Boolean, c: Int) {}
            override fun onSyncingDone(a: HashMap<String, Boolean>, b: Boolean, c: Int) {}
            override fun onSyncingStopped(address: String?, isSuccess: Boolean, requestCode: Int) {
                mainHandler.post {
                    appendSyncLog("[${address ?: "?"}] 解除同步 ${if (isSuccess) "✓" else "✗"}")
                }
            }
        })
        mgr.stopSyncing()
        // 同时停止测量，清理本地采集状态
        if (measurementStarted) {
            devices.forEach { try { it.stopMeasuring() } catch (_: Exception) {} }
            measurementStarted = false
        }
        syncManager = null
        ++syncEpoch  // 使任何残留的 epoch 回调失效
        mainHandler.post {
            _isSynced.value  = false
            _isSyncing.value = false
            _state.value     = CollectionState.Connecting
            appendSyncLog("已发送解除同步指令，请在官方 App 确认设备已退出同步状态")
        }
    }

    private fun scheduleSyncTimeout(epoch: Int) {
        syncTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        syncTimeoutRunnable = Runnable {
            if (epoch != syncEpoch) return@Runnable
            if (_isSyncing.value) {
                _isSyncing.value = false; syncManager = null
                devices.firstOrNull()?.isRootDevice = false
                appendSyncLog("同步超时（30s），请重新点击「SDK 硬件同步」重试")
                _state.value = CollectionState.Connecting
            }
        }.also { mainHandler.postDelayed(it, 30_000) }
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
        applyModeAndStart()
    }

    // ── 滤波器配置切换（General / Dynamic）──
    // 与官方 DeviceManager.setSensorProfile(device, index) 逻辑对齐：
    //   mode=0 → General（firstOrNull），mode=1 → Dynamic（getOrNull(1) ?: first）

    fun setFilterProfileMode(mode: Int) {
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
     * 离线录制专用：切换采样率和滤波档，执行 stopMeasuring → 配置 → startMeasuring 安全序列。
     * 退出离线模式时用 rate=60、filterMode=当前实时档调用此方法恢复。
     */
    fun applyOfflineModeSettings(rate: Int, filterMode: Int) {
        val devList = devices.toList()
        if (devList.isEmpty()) return
        val sid = connectSessionId
        mainHandler.post {
            if (sid != connectSessionId) return@post
            if (measurementStarted) devList.forEach { try { it.stopMeasuring() } catch (_: Exception) {} }
            devList.forEach { dev ->
                dev.setOutputRate(rate)
                dev.measurementMode = desiredMode
                applyFilterProfileToDevice(dev, filterMode)
            }
            mainHandler.postDelayed({
                if (sid != connectSessionId) return@postDelayed
                devList.forEach { dev -> dev.startMeasuring() }
                measurementStarted = true
                appendSyncLog("离线模式：${rate}Hz / ${if (filterMode == 1) "Dynamic" else "General"}")
            }, 500)
        }
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
