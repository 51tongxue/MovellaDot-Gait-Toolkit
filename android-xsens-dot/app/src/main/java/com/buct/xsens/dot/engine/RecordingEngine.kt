package com.buct.xsens.dot.engine

import android.Manifest
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.buct.xsens.dot.data.TimestampUtcCalculator
import com.xsens.dot.android.sdk.events.DotData
import com.xsens.dot.android.sdk.interfaces.DotRecordingCallback
import com.xsens.dot.android.sdk.models.DotDevice
import com.xsens.dot.android.sdk.models.DotRecordingFileInfo
import com.xsens.dot.android.sdk.models.DotRecordingState
import com.xsens.dot.android.sdk.recording.DotRecordingManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 可导出的数据字段描述符，对标官方 DotLogger.RECORDING_DATA_TITLE_MAPPER。
 *
 * [id]        DotRecordingManager.RECORDING_DATA_ID_* 的字节值
 * [label]     UI 显示名称
 * [columns]   CSV 列标题（不含尾部逗号）
 * [extract]   从 DotData 提取该字段对应的 CSV 列字符串
 */
data class ExportDataField(
    val id: Byte,
    val label: String,
    val columns: String,
    val extract: (DotData) -> String
)

data class ExportTaskProgress(
    val isExporting: Boolean = false,
    val totalFiles: Int = 0,
    val activeFileKeys: Set<String> = emptySet(),
    val framesByFile: Map<String, Int> = emptyMap(),
    val targetFramesByFile: Map<String, Int> = emptyMap(),
    val targetBytesByFile: Map<String, Long> = emptyMap(),
    val writtenBytesByFile: Map<String, Long> = emptyMap(),
    val completedFileKeys: Set<String> = emptySet(),
    val failedFileKeys: Set<String> = emptySet(),
) {
    val finishedFileKeys: Set<String>
        get() = completedFileKeys + failedFileKeys

    val hasPendingFiles: Boolean
        get() = totalFiles > 0 && finishedFileKeys.size < totalFiles
}

data class EraseTaskProgress(
    val isErasing: Boolean = false,
    val totalDevices: Int = 0,
    val completedDevices: Set<String> = emptySet(),
    val failedDevices: Set<String> = emptySet(),
)

enum class FlashRecordingPhase {
    Idle,
    Starting,
    Recording,
    Stopping,
}

/**
 * 离线采集引擎 — 对标官方 SDK §4.12–4.13
 *
 * 流程：
 *   setup(devices) → enableDataRecordingNotification()
 *     → requestFlashInfo()
 *     → startRecording() / stopRecording()
 *     → requestFileInfo()
 *     → exportAll() → selectExportedData(selectedIds) + startExporting
 *     → CSV 写盘（onDotDataExported，列由 selectedExportIds 决定）
 *     → clear()
 *
 * 录制时无需配置 Mode；导出时通过 selectedExportIds 选择需要的字段。
 */
class RecordingEngine(private val context: Context) : DotRecordingCallback {

    companion object {
        private const val TAG = "RecordingEngine"
        private const val LINK_DIAG_TAG = "DOT_LINK_DIAG"
        private const val EXPORT_WATCHDOG_INTERVAL_MS = 5_000L
        private const val EXPORT_STALL_TIMEOUT_MS = 15_000L
        private const val EXPORT_MAX_RETRIES = 2
        private const val EXPORT_UI_PUBLISH_INTERVAL_MS = 250L
        private const val EXPORT_WRITE_BUFFER_SIZE = 64 * 1024
        private const val EXPORT_PROTOCOL_BYTES_PER_FRAME = 3
        private const val AUTO_FILE_INFO_TIMEOUT_MS = 8_000L
        private val EXPORT_FIELD_BINARY_LENGTHS = intArrayOf(
            4, 16, 9, 12, 12, 16, 12, 12, 12, 6, 2, 1, 1
        )

        /**
         * 13 种可导出字段，按官方 RECORDING_DATA_TITLE_MAPPER 顺序。
         * ID 字节值与 DotRecordingManager.RECORDING_DATA_ID_* 常量一致（0–12）。
         */
        val ALL_EXPORT_FIELDS: List<ExportDataField> = listOf(
            ExportDataField(
                id = 0, label = "Timestamp",
                columns = "SampleTimeFine",
                extract = { d -> "${d.sampleTimeFine}" }
            ),
            ExportDataField(
                id = 1, label = "Orientation (Quat)",
                columns = "Quat_W,Quat_X,Quat_Y,Quat_Z",
                extract = { d ->
                    val q = d.getQuat()
                    "${fF(q, 0)},${fF(q, 1)},${fF(q, 2)},${fF(q, 3)}"
                }
            ),
            ExportDataField(
                id = 2, label = "IQ",
                columns = "iq_X,iq_Y,iq_Z",
                extract = { _ -> "0,0,0" }   // DotData SDK 未开放 IQ getter
            ),
            ExportDataField(
                id = 3, label = "IV",
                columns = "iv_X,iv_Y,iv_Z",
                extract = { _ -> "0,0,0" }   // DotData SDK 未开放 IV getter
            ),
            ExportDataField(
                id = 4, label = "Euler Angles",
                columns = "Euler_X,Euler_Y,Euler_Z",
                extract = { d ->
                    val e = d.getEuler()
                    "${fD(e, 0)},${fD(e, 1)},${fD(e, 2)}"
                }
            ),
            ExportDataField(
                id = 5, label = "DeltaQ (dq)",
                columns = "dq_W,dq_X,dq_Y,dq_Z",
                extract = { d ->
                    val v = d.dq
                    "${fD(v, 0)},${fD(v, 1)},${fD(v, 2)},${fD(v, 3)}"
                }
            ),
            ExportDataField(
                id = 6, label = "DeltaV (dv)",
                columns = "dv_1,dv_2,dv_3",
                extract = { d ->
                    val v = d.dv
                    "${fD(v, 0)},${fD(v, 1)},${fD(v, 2)}"
                }
            ),
            ExportDataField(
                id = 7, label = "Calibrated Acc",
                columns = "Acc_X,Acc_Y,Acc_Z",
                extract = { d ->
                    val a = d.getAcc()
                    "${fD(a, 0)},${fD(a, 1)},${fD(a, 2)}"
                }
            ),
            ExportDataField(
                id = 8, label = "Calibrated Gyro",
                columns = "Gyr_X,Gyr_Y,Gyr_Z",
                extract = { d ->
                    val g = d.getGyr()
                    "${fD(g, 0)},${fD(g, 1)},${fD(g, 2)}"
                }
            ),
            ExportDataField(
                id = 9, label = "Calibrated Mag",
                columns = "Mag_X,Mag_Y,Mag_Z",
                extract = { d ->
                    val m = d.getMag()
                    "${fD(m, 0)},${fD(m, 1)},${fD(m, 2)}"
                }
            ),
            ExportDataField(
                id = 10, label = "Status",
                columns = "Status",
                extract = { d -> "${d.status}" }
            ),
            ExportDataField(
                id = 11, label = "Clip Acc",
                columns = "ClipCountAcc",
                extract = { d -> "${d.clipCountAcc}" }
            ),
            ExportDataField(
                id = 12, label = "Clip Gyro",
                columns = "ClipCountGyr",
                extract = { d -> "${d.clipCountGyr}" }
            )
        )

        // 辅助：安全读取数组元素
        private fun fD(arr: DoubleArray?, i: Int) = if (arr != null && arr.size > i) arr[i] else 0.0
        private fun fF(arr: FloatArray?,  i: Int) = if (arr != null && arr.size > i) arr[i] else 0f
        // 默认导出字段：Timestamp + Quat + Euler + Acc + Gyro。
        // 分析端离线格式需要 Quat_* 才能把局部加速度旋转到全局坐标系；Euler
        // 虽不参与当前算法，但作为原始诊断数据保留。实测移除 Euler 不会提升 SDK
        // 按帧导出吞吐，因此不以牺牲原始数据完整性换取无效的体积优化。
        val DEFAULT_EXPORT_IDS: Set<Byte> = setOf(0, 1, 4, 7, 8)
        private val SUPPORTED_EXPORT_IDS: Set<Byte> = ALL_EXPORT_FIELDS
            .map { it.id }
            .filterNot { it == 2.toByte() || it == 3.toByte() }
            .toSet()
        val SUPPORTED_EXPORT_FIELDS: List<ExportDataField> = ALL_EXPORT_FIELDS
            .filter { it.id in SUPPORTED_EXPORT_IDS }
    }

    private data class RecordingClockAnchors(
        val startCommandUtcMs: Long? = null,
        val startAckUtcMs: Long? = null,
        val isSyncedAtStart: Boolean? = null,
    )

    private enum class RecordingCommand {
        Start,
        Stop,
        RequestFiles,
        Erase,
        ExportAll,
        ExportSelected,
    }

    private data class PendingRecordingOperation(
        val command: RecordingCommand,
        val targets: Set<String>,
        val selectedKeys: Set<String> = emptySet(),
        val epoch: Int,
    )

    private data class AutoExportAfterStop(
        val targets: Set<String>,
        val startUtcMs: Long,
        val stopUtcMs: Long,
        val baselineKeys: Set<String>,
        val baselineKnown: Boolean,
    )

    private data class DeviceExportRequest(
        val address: String,
        val files: List<DotRecordingFileInfo>,
        val exportIds: ByteArray,
    )

    // ── 导出字段选择 ──
    private val _selectedExportIds = MutableStateFlow(DEFAULT_EXPORT_IDS)
    val selectedExportIds: StateFlow<Set<Byte>> = _selectedExportIds.asStateFlow()

    fun setSelectedExportIds(ids: Set<Byte>) {
        val supported = ids.filter { it in SUPPORTED_EXPORT_IDS }.toSet()
        if (supported.isEmpty()) return
        _selectedExportIds.value = supported
    }

    // ── 每台设备的 Manager ──
    private val managers = ConcurrentHashMap<String, DotRecordingManager>()

    // ── 公开状态 ──
    private val _flashInfo = MutableStateFlow<Map<String, Pair<Int, Int>>>(emptyMap()) // addr → (used KB, total KB)
    val flashInfo: StateFlow<Map<String, Pair<Int, Int>>> = _flashInfo.asStateFlow()

    private val _notificationReady = MutableStateFlow<Set<String>>(emptySet())
    val notificationReady: StateFlow<Set<String>> = _notificationReady.asStateFlow()

    private val _recordingActive = MutableStateFlow(false)
    val recordingActive: StateFlow<Boolean> = _recordingActive.asStateFlow()

    private val _recordingPhase = MutableStateFlow(FlashRecordingPhase.Idle)
    val recordingPhase: StateFlow<FlashRecordingPhase> = _recordingPhase.asStateFlow()

    private val _recordingStates = MutableStateFlow<Map<String, DotRecordingState>>(emptyMap())
    val recordingStates: StateFlow<Map<String, DotRecordingState>> = _recordingStates.asStateFlow()

    private val _fileList = MutableStateFlow<Map<String, List<DotRecordingFileInfo>>>(emptyMap())
    val fileList: StateFlow<Map<String, List<DotRecordingFileInfo>>> = _fileList.asStateFlow()

    private val _exportProgress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val exportProgress: StateFlow<Map<String, Int>> = _exportProgress.asStateFlow()

    private val _exportDone = MutableStateFlow<Set<String>>(emptySet())
    val exportDone: StateFlow<Set<String>> = _exportDone.asStateFlow()

    private val _exportTaskProgress = MutableStateFlow(ExportTaskProgress())
    val exportTaskProgress: StateFlow<ExportTaskProgress> = _exportTaskProgress.asStateFlow()

    private val _pendingRecordingExportKeys = MutableStateFlow<Set<String>>(emptySet())
    val pendingRecordingExportKeys: StateFlow<Set<String>> =
        _pendingRecordingExportKeys.asStateFlow()

    private val _preparingRecordingExport = MutableStateFlow(false)
    val preparingRecordingExport: StateFlow<Boolean> =
        _preparingRecordingExport.asStateFlow()

    private val _eraseTaskProgress = MutableStateFlow(EraseTaskProgress())
    val eraseTaskProgress: StateFlow<EraseTaskProgress> = _eraseTaskProgress.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val recordingLog: StateFlow<List<String>> = _log.asStateFlow()

    // ── 导出 CSV 写入器 (writerKey = "$addr-$fileId") ──
    private val exportWriters = ConcurrentHashMap<String, ExportCsvWriter>()
    private val exportWriterFailures = ConcurrentHashMap.newKeySet<String>()
    private val exportRequests = ConcurrentHashMap<String, DeviceExportRequest>()
    private val exportStartedDevices = ConcurrentHashMap.newKeySet<String>()
    private val exportDevicesWithData = ConcurrentHashMap.newKeySet<String>()
    private val exportResettingDevices = ConcurrentHashMap.newKeySet<String>()
    private val exportLastProgressAt = ConcurrentHashMap<String, Long>()
    private val exportRetryCounts = ConcurrentHashMap<String, Int>()
    private val exportFrameCounts = ConcurrentHashMap<String, AtomicLong>()
    private val exportDeviceFrameCounts = ConcurrentHashMap<String, AtomicLong>()
    private val exportWrittenBytes = ConcurrentHashMap<String, Long>()
    private val exportStartedAt = ConcurrentHashMap<String, Long>()
    private val exportFirstDataAt = ConcurrentHashMap<String, Long>()
    private val exportLastUiPublishAt = ConcurrentHashMap<String, Long>()
    private val exportProgressPublishLock = Any()
    @Volatile private var exportSessionStartedAt = 0L
    @Volatile private var exportStopRequested = false

    private val exportWatchdogRunnable = object : Runnable {
        override fun run() {
            if (exportStopRequested || exportRequests.isEmpty()) return
            val now = System.currentTimeMillis()
            exportRequests.keys.toList().forEach { norm ->
                val lastProgress = exportLastProgressAt[norm] ?: now
                if (now - lastProgress >= EXPORT_STALL_TIMEOUT_MS) {
                    retryDeviceExport(norm, "导出长时间无进度")
                }
            }
            if (!exportStopRequested && exportRequests.isNotEmpty()) {
                mainHandler.postDelayed(this, EXPORT_WATCHDOG_INTERVAL_MS)
            }
        }
    }

    // addr -> latest recording clock anchors captured by this app session.
    private val recordingAnchors = ConcurrentHashMap<String, RecordingClockAnchors>()
    private val activeRecordingDevices = ConcurrentHashMap.newKeySet<String>()
    private val pendingStartAcks = ConcurrentHashMap.newKeySet<String>()
    private val pendingStopAcks = ConcurrentHashMap.newKeySet<String>()
    private val latestRecordingStates = ConcurrentHashMap<String, DotRecordingState>()
    private val pendingStateChecks = ConcurrentHashMap<String, DotRecordingState>()
    private val lastNotificationEnableRequestAt = ConcurrentHashMap<String, Long>()
    private val pendingAutoFileInfoTargets = ConcurrentHashMap.newKeySet<String>()
    private val pendingAutoFileInfoRequests = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var startAckTargets: Set<String> = emptySet()
    @Volatile private var stopAckTargets: Set<String> = emptySet()
    @Volatile private var recordingSessionBaselineKeys: Set<String> = emptySet()
    @Volatile private var recordingSessionTargets: Set<String> = emptySet()
    @Volatile private var recordingSessionStartUtcMs: Long? = null
    @Volatile private var autoExportAfterStop: AutoExportAfterStop? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var totalDevices = 0
    @Volatile private var operationEpoch = 0
    @Volatile private var pendingOperation: PendingRecordingOperation? = null

    // ── 工具 ──

    private fun normalizeAddress(addr: String): String =
        addr.replace(":", "").replace("-", "").uppercase()

    private fun exportFileKey(addr: String, fileInfo: DotRecordingFileInfo): String =
        "${normalizeAddress(addr)}-${fileInfo.fileId}"

    private fun fileKeysForTargets(targets: Set<String>): Set<String> =
        targets.flatMap { addr ->
            _fileList.value[addr].orEmpty().map { file -> exportFileKey(addr, file) }
        }.toSet()

    private fun recordingDateMs(fileInfo: DotRecordingFileInfo): Long {
        val raw = fileInfo.startRecordingTimestamp
        return when {
            raw > 10_000_000_000L -> raw
            raw > 0L -> raw * 1000L
            else -> parseTimestampFromName(fileInfo.fileName)?.time ?: 0L
        }
    }

    private fun parseTimestampFromName(name: String?): Date? {
        if (name.isNullOrBlank()) return null
        val match = Regex("(20\\d{6})[_-]?(\\d{6})").find(name) ?: return null
        return runCatching {
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).parse(
                "${match.groupValues[1]}_${match.groupValues[2]}"
            )
        }.getOrNull()
    }

    private fun estimatedExportFrameCount(
        fileInfo: DotRecordingFileInfo,
        exportIds: ByteArray,
    ): Int {
        val frameBytes = EXPORT_PROTOCOL_BYTES_PER_FRAME + exportIds.sumOf { id ->
            EXPORT_FIELD_BINARY_LENGTHS.getOrElse(id.toInt()) { 0 }
        }
        if (frameBytes <= EXPORT_PROTOCOL_BYTES_PER_FRAME) return 1
        return ((fileInfo.dataSize.toLong() + frameBytes - 1L) / frameBytes)
            .coerceIn(1L, Int.MAX_VALUE.toLong())
            .toInt()
    }

    private fun resetExportTracking(
        targetFiles: Map<String, Long>,
        targetFrames: Map<String, Int>,
    ) {
        _exportProgress.value = emptyMap()
        _exportDone.value = emptySet()
        val targetFileKeys = targetFiles.keys
        _exportTaskProgress.value = if (targetFileKeys.isEmpty()) {
            ExportTaskProgress()
        } else {
            ExportTaskProgress(
                isExporting = true,
                totalFiles = targetFileKeys.size,
                targetFramesByFile = targetFrames,
                targetBytesByFile = targetFiles,
            )
        }
        exportWriterFailures.clear()
    }

    private fun clearExportRuntimeTracking() {
        exportFrameCounts.clear()
        exportDeviceFrameCounts.clear()
        exportWrittenBytes.clear()
        exportStartedAt.clear()
        exportFirstDataAt.clear()
        exportLastUiPublishAt.clear()
        exportSessionStartedAt = 0L
    }

    private fun resetDeviceExportRuntime(address: String, resetPublishedProgress: Boolean) {
        val norm = normalizeAddress(address)
        val prefix = "$norm-"
        exportFrameCounts.keys.removeIf { it.startsWith(prefix) }
        exportWrittenBytes.keys.removeIf { it.startsWith(prefix) }
        exportDeviceFrameCounts.remove(norm)
        exportStartedAt.remove(norm)
        exportFirstDataAt.remove(norm)
        exportLastUiPublishAt.remove(norm)
        if (!resetPublishedProgress) return

        synchronized(exportProgressPublishLock) {
            _exportProgress.value = _exportProgress.value.toMutableMap().also { it.remove(norm) }
            val task = _exportTaskProgress.value
            _exportTaskProgress.value = task.copy(
                activeFileKeys = task.activeFileKeys.filterNot { it.startsWith(prefix) }.toSet(),
                framesByFile = task.framesByFile.toMutableMap().also { map ->
                    map.keys.removeIf { it.startsWith(prefix) }
                },
                writtenBytesByFile = task.writtenBytesByFile.toMutableMap().also { map ->
                    map.keys.removeIf { it.startsWith(prefix) }
                },
            )
        }
    }

    private fun publishExportProgress(
        norm: String,
        writerKey: String,
        force: Boolean = false,
    ) {
        val now = SystemClock.elapsedRealtime()
        val lastPublish = exportLastUiPublishAt[norm] ?: 0L
        if (!force && now - lastPublish < EXPORT_UI_PUBLISH_INTERVAL_MS) return
        exportLastUiPublishAt[norm] = now

        val fileFrames = exportFrameCounts[writerKey]
            ?.get()
            ?.coerceAtMost(Int.MAX_VALUE.toLong())
            ?.toInt()
            ?: 0
        val deviceFrames = exportDeviceFrameCounts[norm]
            ?.get()
            ?.coerceAtMost(Int.MAX_VALUE.toLong())
            ?.toInt()
            ?: 0
        val bytesWritten = exportWrittenBytes[writerKey] ?: 0L

        synchronized(exportProgressPublishLock) {
            _exportProgress.value = _exportProgress.value.toMutableMap().also {
                it[norm] = deviceFrames
            }
            val task = _exportTaskProgress.value
            if (writerKey in task.finishedFileKeys && !force) return
            _exportTaskProgress.value = task.copy(
                isExporting = task.totalFiles > 0,
                activeFileKeys = task.activeFileKeys + writerKey,
                framesByFile = task.framesByFile.toMutableMap().also {
                    it[writerKey] = fileFrames
                },
                writtenBytesByFile = task.writtenBytesByFile.toMutableMap().also {
                    it[writerKey] = bytesWritten
                },
            )
        }
    }

    private fun appendLog(msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _log.value = (_log.value + "[$t] $msg").takeLast(200) // 防止无限增长
        Log.i(TAG, msg)
    }

    private fun connectedManagerKeys(): Set<String> =
        managers.filterValues { it.mDevice?.connectionState == DotDevice.CONN_STATE_CONNECTED }.keys

    private fun setExportLinkPriority(address: String, highPriority: Boolean) {
        val norm = normalizeAddress(address)
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "[$norm] cannot change export link priority without BLUETOOTH_CONNECT")
            return
        }
        val gatt = managers[norm]?.mDevice?.gatt ?: return
        val priority = if (highPriority) {
            BluetoothGatt.CONNECTION_PRIORITY_HIGH
        } else {
            BluetoothGatt.CONNECTION_PRIORITY_BALANCED
        }
        val priorityAccepted = runCatching {
            gatt.requestConnectionPriority(priority)
        }.getOrDefault(false)
        if (highPriority && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                gatt.setPreferredPhy(
                    BluetoothDevice.PHY_LE_2M_MASK,
                    BluetoothDevice.PHY_LE_2M_MASK,
                    BluetoothDevice.PHY_OPTION_NO_PREFERRED,
                )
            }.onFailure {
                Log.w(TAG, "[$norm] request 2M PHY failed: ${it.message}")
            }
        }
        Log.i(
            TAG,
            "[$norm] export link priority=${if (highPriority) "HIGH" else "BALANCED"}, " +
                "accepted=$priorityAccepted"
        )
    }

    private fun updateRecordingActive() {
        _recordingActive.value = activeRecordingDevices.isNotEmpty()
    }

    private fun updateRecordingPhaseFromState() {
        _recordingPhase.value = if (activeRecordingDevices.isNotEmpty()) {
            FlashRecordingPhase.Recording
        } else {
            FlashRecordingPhase.Idle
        }
    }

    private fun updateRecordingPhaseFromStateIfStable() {
        if (_recordingPhase.value == FlashRecordingPhase.Idle ||
            _recordingPhase.value == FlashRecordingPhase.Recording
        ) {
            updateRecordingPhaseFromState()
        }
    }

    private fun syncLocalRecordingState(
        addr: String,
        state: DotRecordingState,
        applyActiveState: Boolean
    ) {
        val norm = normalizeAddress(addr)
        val oldState = latestRecordingStates[norm]
        if (
            applyActiveState &&
            _recordingPhase.value == FlashRecordingPhase.Recording &&
            oldState == DotRecordingState.onRecording &&
            state in setOf(DotRecordingState.idle, DotRecordingState.success, DotRecordingState.unknown)
        ) {
            appendLog("[$addr] 忽略录制中的旧状态：$state")
            return
        }
        latestRecordingStates[norm] = state
        _recordingStates.value = _recordingStates.value.toMutableMap().also { it[norm] = state }
        if (!applyActiveState) return
        when (state) {
            DotRecordingState.onRecording -> activeRecordingDevices.add(norm)
            DotRecordingState.idle,
            DotRecordingState.success,
            DotRecordingState.fail,
            DotRecordingState.invalidCmd -> activeRecordingDevices.remove(norm)
            else -> Unit
        }
        updateRecordingActive()
        updateRecordingPhaseFromStateIfStable()
    }

    private fun finishStartAcksIfComplete() {
        if (pendingStartAcks.isNotEmpty()) return
        val targets = startAckTargets
        startAckTargets = emptySet()
        val recordingTargets = targets.filter { it in activeRecordingDevices }.toSet()
        if (targets.isNotEmpty() && recordingTargets.containsAll(targets)) {
            _recordingPhase.value = FlashRecordingPhase.Recording
            appendLog("开始录制 ACK 已全部返回")
            return
        }
        recordingSessionStartUtcMs = null
        recordingSessionBaselineKeys = emptySet()
        recordingSessionTargets = emptySet()
        val rollbackTargets = targets.filter { norm ->
            managers[norm]?.mDevice?.connectionState == DotDevice.CONN_STATE_CONNECTED
        }.toSet()
        if (rollbackTargets.isNotEmpty()) {
            appendLog("开始录制未全部确认，正在回滚")
            pendingStopAcks.clear()
            pendingStopAcks.addAll(rollbackTargets)
            stopAckTargets = rollbackTargets
            _recordingPhase.value = FlashRecordingPhase.Stopping
            rollbackTargets.forEach { norm ->
                val mgr = managers[norm] ?: return@forEach
                if (!mgr.stopRecording()) {
                    pendingStopAcks.remove(norm)
                    activeRecordingDevices.remove(norm)
                    appendLog("[${mgr.mDevice?.address ?: norm}] 回滚停止录制失败")
                }
            }
            updateRecordingActive()
            if (pendingStopAcks.isEmpty()) {
                finishStopAcksIfComplete()
            } else {
                scheduleStopAckTimeout(label = "回滚停止录制")
            }
        } else {
            updateRecordingPhaseFromState()
            appendLog("开始录制失败，未进入录制状态")
        }
    }

    private fun finishStopAcksIfComplete() {
        if (pendingStopAcks.isNotEmpty()) return
        val requestedTargets = stopAckTargets
        val stillRecording = activeRecordingDevices.toSet()
        if (stillRecording.isNotEmpty()) {
            stopAckTargets = emptySet()
            _recordingPhase.value = FlashRecordingPhase.Recording
            updateRecordingActive()
            appendLog(
                "部分设备未确认停止，仍保持录制状态：${stillRecording.joinToString()}"
            )
            return
        }
        val stoppedTargets = recordingSessionTargets.ifEmpty { requestedTargets }
        val sessionStartUtcMs = recordingSessionStartUtcMs
        val baselineKeys = recordingSessionBaselineKeys
        val baselineKnown = stoppedTargets.all { it in _fileList.value.keys }
        val stopUtcMs = System.currentTimeMillis()
        stopAckTargets = emptySet()
        recordingSessionStartUtcMs = null
        recordingSessionBaselineKeys = emptySet()
        recordingSessionTargets = emptySet()
        updateRecordingActive()
        _recordingPhase.value = FlashRecordingPhase.Idle
        appendLog("停止录制 ACK 已全部返回")
        if (stoppedTargets.isNotEmpty() && sessionStartUtcMs != null) {
            val session = AutoExportAfterStop(
                targets = stoppedTargets,
                startUtcMs = sessionStartUtcMs,
                stopUtcMs = stopUtcMs,
                baselineKeys = baselineKeys,
                baselineKnown = baselineKnown,
            )
            autoExportAfterStop = session
            _preparingRecordingExport.value = true
            mainHandler.postDelayed({
                if (_recordingPhase.value == FlashRecordingPhase.Idle && autoExportAfterStop == session) {
                    requestAutoExportFileInfo(session)
                }
            }, 1_200L)
        } else {
            refreshFlashAfterStop()
        }
    }

    private fun refreshFlashAfterStop() {
        mainHandler.postDelayed({
            if (_recordingPhase.value != FlashRecordingPhase.Idle) return@postDelayed
            requestFlashInfo()
        }, 700L)
    }

    private fun requestAutoExportFileInfo(session: AutoExportAfterStop) {
        pendingAutoFileInfoTargets.clear()
        pendingAutoFileInfoTargets.addAll(session.targets)
        pendingAutoFileInfoRequests.clear()
        _fileList.value = _fileList.value.toMutableMap().also { current ->
            session.targets.forEach { current.remove(it) }
        }
        appendLog("正在读取刚才录制的文件…")
        session.targets.forEach { addr ->
            managers[addr]?.requestFlashInfo()
        }
        mainHandler.postDelayed({
            if (autoExportAfterStop !== session) return@postDelayed
            finishAutoExportFileInfoIfReady(force = true)
        }, AUTO_FILE_INFO_TIMEOUT_MS)
    }

    private fun finishAutoExportFileInfoIfReady(force: Boolean = false) {
        val session = autoExportAfterStop ?: return
        if (!force && pendingAutoFileInfoTargets.isNotEmpty()) return
        if (force && pendingAutoFileInfoTargets.isNotEmpty()) {
            val missing = pendingAutoFileInfoTargets.toSet()
            pendingAutoFileInfoTargets.clear()
            pendingAutoFileInfoRequests.clear()
            autoExportAfterStop = null
            _preparingRecordingExport.value = false
            appendLog("部分设备文件列表读取超时，未生成不完整导出提示：${missing.joinToString()}")
            return
        }
        pendingAutoFileInfoTargets.clear()
        pendingAutoFileInfoRequests.clear()

        val selectedKeysByTarget = session.targets.associateWith { addr ->
            val files = _fileList.value[addr].orEmpty()
            val byTime = files
                .filter { file ->
                    val ts = recordingDateMs(file)
                    ts > 0L && ts >= session.startUtcMs - 3_000L && ts <= session.stopUtcMs + 30_000L
                }
                .map { file -> exportFileKey(addr, file) }
            val byBaseline = files
                .filter { file -> exportFileKey(addr, file) !in session.baselineKeys }
                .map { file -> exportFileKey(addr, file) }
            when {
                byTime.isNotEmpty() -> byTime
                session.baselineKnown && byBaseline.isNotEmpty() -> byBaseline
                else -> listOfNotNull(
                    files
                        .maxByOrNull { file -> recordingDateMs(file).takeIf { it > 0L } ?: file.fileId.toLong() }
                        ?.let { file -> exportFileKey(addr, file) }
                )
            }
        }
        val missingTargets = selectedKeysByTarget
            .filterValues { it.isEmpty() }
            .keys
        if (missingTargets.isNotEmpty()) {
            appendLog("未完整找到本次录制文件，未生成导出提示：${missingTargets.joinToString()}")
            autoExportAfterStop = null
            _preparingRecordingExport.value = false
            return
        }
        val selectedKeys = selectedKeysByTarget.values.flatten().toSet()

        if (selectedKeys.isEmpty()) {
            appendLog("未找到刚才录制的文件，请手动读取文件")
            autoExportAfterStop = null
            _preparingRecordingExport.value = false
            return
        }

        autoExportAfterStop = null
        _preparingRecordingExport.value = false
        _pendingRecordingExportKeys.value = selectedKeys
        appendLog("本次录制的 ${selectedKeys.size} 个文件已就绪，等待确认是否导出")
    }

    fun exportLatestRecording() {
        val selectedKeys = _pendingRecordingExportKeys.value
        if (selectedKeys.isEmpty()) return
        _preparingRecordingExport.value = false
        _pendingRecordingExportKeys.value = emptySet()
        appendLog("导出本次录制的 ${selectedKeys.size} 个文件")
        exportSelected(selectedKeys)
    }

    fun dismissLatestRecordingExport() {
        if (_preparingRecordingExport.value || _pendingRecordingExportKeys.value.isNotEmpty()) {
            appendLog("已暂不导出本次录制，可从文件列表选择其他数据")
        }
        autoExportAfterStop = null
        pendingAutoFileInfoTargets.clear()
        pendingAutoFileInfoRequests.clear()
        _preparingRecordingExport.value = false
        _pendingRecordingExportKeys.value = emptySet()
    }

    private fun scheduleStopAckTimeout(label: String) {
        mainHandler.postDelayed({
            if (pendingStopAcks.isNotEmpty()) {
                val timedOut = pendingStopAcks.toSet()
                appendLog("$label ACK 超时，正在复查状态：${timedOut.joinToString()}")
                timedOut.forEach { norm ->
                    managers[norm]?.requestRecordingState()
                }
                mainHandler.postDelayed({
                    val stillPending = pendingStopAcks.toSet()
                    if (stillPending.isNotEmpty()) {
                        val finished = stillPending.filter { latestRecordingStates[it] != DotRecordingState.onRecording }
                        finished.forEach { norm ->
                            pendingStopAcks.remove(norm)
                            activeRecordingDevices.remove(norm)
                        }
                        if (finished.isNotEmpty()) {
                            appendLog("状态复查确认已停止：${finished.joinToString()}")
                        }
                        if (pendingStopAcks.isNotEmpty()) {
                            val unresolved = pendingStopAcks.toSet()
                            pendingStopAcks.clear()
                            stopAckTargets = emptySet()
                            _recordingPhase.value = FlashRecordingPhase.Recording
                            updateRecordingActive()
                            appendLog(
                                "仍有设备未确认停止，请保持设备靠近后重试：${unresolved.joinToString()}"
                            )
                        } else {
                            finishStopAcksIfComplete()
                        }
                    }
                }, 2_000L)
            }
        }, 7_000L)
    }

    private fun flashReadyForStart(targets: Set<String>): Boolean {
        val flash = _flashInfo.value
        val missing = targets.filter { it !in flash.keys }
        if (missing.isNotEmpty()) {
            appendLog("等待 Flash 信息：${missing.joinToString()}")
            return false
        }
        val full = targets.filter { addr ->
            val (used, total) = flash[addr] ?: return@filter true
            total <= 0 || used.toFloat() / total.toFloat() >= 0.9f
        }
        if (full.isNotEmpty()) {
            appendLog("Flash 可用空间不足，先擦除或导出：${full.joinToString()}")
            return false
        }
        return true
    }

    private fun requestStateThenRun(command: RecordingCommand, selectedKeys: Set<String> = emptySet()): Boolean {
        if (pendingOperation != null || pendingStartAcks.isNotEmpty() || pendingStopAcks.isNotEmpty()) {
            appendLog("已有录制操作等待 ACK，请稍候")
            return false
        }
        if (managers.isEmpty()) {
            appendLog("没有可操作的设备")
            return false
        }

        val connectedKeys = connectedManagerKeys()
        val targets = when (command) {
            RecordingCommand.Start -> managers.keys.toSet()
            RecordingCommand.Stop -> activeRecordingDevices.ifEmpty { managers.keys }.toSet()
            RecordingCommand.RequestFiles ->
                connectedKeys
            RecordingCommand.Erase,
            RecordingCommand.ExportAll -> connectedKeys
            RecordingCommand.ExportSelected -> selectedKeys
                .mapNotNull { it.substringBefore("-", missingDelimiterValue = "").ifBlank { null } }
                .toSet()
                .ifEmpty { connectedKeys }
        }

        if (targets.isEmpty()) {
            appendLog("没有已连接设备可执行操作")
            return false
        }

        val disconnected = targets - connectedKeys
        if (disconnected.isNotEmpty()) {
            appendLog("有设备未回连，暂不能执行操作：${disconnected.joinToString()}")
            return false
        }

        if (command == RecordingCommand.Start) {
            val notReady = targets - _notificationReady.value
            if (notReady.isNotEmpty()) {
                appendLog("录制通知未全部就绪：${notReady.joinToString()}")
                return false
            }
            if (!flashReadyForStart(targets)) return false
        }

        if (command == RecordingCommand.RequestFiles) {
            doRequestFileInfo(targets)
            return true
        }

        val epoch = ++operationEpoch
        pendingStateChecks.clear()
        pendingOperation = PendingRecordingOperation(command, targets, selectedKeys, epoch)

        var requestCount = 0
        targets.forEach { addr ->
            val mgr = managers[addr] ?: return@forEach
            if (mgr.requestRecordingState()) {
                requestCount++
            } else {
                appendLog("[${mgr.mDevice?.address ?: addr}] 查询录制状态失败")
            }
        }

        if (requestCount == 0) {
            pendingOperation = null
            return false
        }

        mainHandler.postDelayed({
            val op = pendingOperation
            if (op?.epoch == epoch) {
                appendLog("录制状态查询超时，操作已取消")
                pendingOperation = null
                pendingStateChecks.clear()
                if (op.command == RecordingCommand.Erase) {
                    _eraseTaskProgress.value = EraseTaskProgress()
                }
                if (op.command == RecordingCommand.Start || op.command == RecordingCommand.Stop) {
                    updateRecordingPhaseFromState()
                }
            }
        }, 2_500L)
        return true
    }

    private fun handlePendingState(address: String, state: DotRecordingState) {
        val op = pendingOperation ?: return
        val norm = normalizeAddress(address)
        if (norm !in op.targets) return
        pendingStateChecks[norm] = state
        if (!op.targets.all { it in pendingStateChecks.keys }) return

        val states = op.targets.associateWith { pendingStateChecks[it] ?: DotRecordingState.unknown }
        pendingOperation = null
        pendingStateChecks.clear()

        val blocked = states.filter { (_, value) -> !isStateAllowedForOperation(op.command, value) }
        if (blocked.isNotEmpty()) {
            val desc = blocked.map { (addr, value) -> "$addr=$value" }.joinToString()
            appendLog("设备当前状态不允许操作：$desc")
            if (op.command == RecordingCommand.Erase) {
                _eraseTaskProgress.value = EraseTaskProgress(
                    isErasing = false,
                    totalDevices = op.targets.size,
                    failedDevices = op.targets
                )
            }
            if (op.command == RecordingCommand.Start || op.command == RecordingCommand.Stop) {
                updateRecordingPhaseFromState()
            }
            return
        }

        when (op.command) {
            RecordingCommand.Start -> doStartRecording(op.targets)
            RecordingCommand.Stop -> doStopRecording(op.targets)
            RecordingCommand.RequestFiles -> doRequestFileInfo(op.targets)
            RecordingCommand.Erase -> doEraseAll(op.targets)
            RecordingCommand.ExportAll -> doExportAll(op.targets)
            RecordingCommand.ExportSelected -> doExportSelected(op.targets, op.selectedKeys)
        }
    }

    private fun isStateAllowedForOperation(command: RecordingCommand, state: DotRecordingState): Boolean =
        when (command) {
            RecordingCommand.Start -> state == DotRecordingState.idle || state == DotRecordingState.success
            RecordingCommand.Stop -> state == DotRecordingState.onRecording
            RecordingCommand.RequestFiles,
            RecordingCommand.Erase,
            RecordingCommand.ExportAll,
            RecordingCommand.ExportSelected -> state == DotRecordingState.idle || state == DotRecordingState.success
        }

    // ── 生命周期 ──

    fun setup(devices: List<DotDevice>) {
        clear()
        totalDevices = devices.size
        _notificationReady.value = emptySet()
        _flashInfo.value = emptyMap()
        _fileList.value = emptyMap()
        _exportProgress.value = emptyMap()
        _exportDone.value = emptySet()
        _log.value = emptyList()
        _recordingActive.value = false
        _recordingStates.value = emptyMap()
        recordingAnchors.clear()
        activeRecordingDevices.clear()
        pendingStartAcks.clear()
        pendingStopAcks.clear()
        latestRecordingStates.clear()
        pendingStateChecks.clear()
        lastNotificationEnableRequestAt.clear()
        startAckTargets = emptySet()
        stopAckTargets = emptySet()
        pendingOperation = null
        _recordingStates.value = emptyMap()
        _recordingPhase.value = FlashRecordingPhase.Idle

        devices.forEach { dev ->
            val addr = normalizeAddress(dev.address ?: return@forEach)
            val mgr = DotRecordingManager(context, dev, this)
            managers[addr] = mgr
            if (mgr.enableDataRecordingNotification()) {
                lastNotificationEnableRequestAt[addr] = System.currentTimeMillis()
                appendLog("[${dev.address}] 正在启用录制通知…")
            } else {
                appendLog("[${dev.address}] 发送启用录制通知失败")
            }
            mainHandler.postDelayed({
                if (mgr.requestRecordingState()) {
                    appendLog("[${dev.address}] 已发送录制状态查询")
                } else {
                    appendLog("[${dev.address}] 发送录制状态查询失败")
                }
            }, 500L)
            mainHandler.postDelayed({
                mgr.requestFlashInfo()
            }, 1_200L)
            mainHandler.postDelayed({
                if (addr !in latestRecordingStates.keys) {
                    appendLog("[${dev.address}] 录制状态无返回，可先尝试强制停止")
                }
            }, 3_000L)
        }
    }

    fun requestFlashInfo() {
        managers.values.forEach { it.requestFlashInfo() }
    }

    fun refreshSetupState() {
        managers.forEach { (norm, mgr) ->
            val rawAddr = mgr.mDevice?.address ?: norm
            val now = System.currentTimeMillis()
            val lastRequestAt = lastNotificationEnableRequestAt[norm] ?: 0L
            if (norm !in _notificationReady.value && now - lastRequestAt >= 2_000L) {
                if (mgr.enableDataRecordingNotification()) {
                    lastNotificationEnableRequestAt[norm] = now
                    appendLog("[$rawAddr] 补发录制通知启用…")
                }
            }
            mgr.requestRecordingState()
            mgr.requestFlashInfo()
        }
    }

    /**
     * 设备断线重连后重新启用录制通知，使后续的 stopRecording/requestFileInfo 能正常工作。
     * address 为 BLE 地址（含冒号格式或规范化格式均可）。
     */
    fun reenableNotification(address: String) {
        val norm = normalizeAddress(address)
        val mgr  = managers[norm] ?: return
        val now = System.currentTimeMillis()
        val lastRequestAt = lastNotificationEnableRequestAt[norm] ?: 0L
        if (now - lastRequestAt < 5_000L) {
            Log.i(LINK_DIAG_TAG, "[${norm.takeLast(4)}] recording-notification skipped; requestStateOnly")
            mgr.requestRecordingState()
            return
        }
        if (mgr.enableDataRecordingNotification()) {
            lastNotificationEnableRequestAt[norm] = now
            Log.i(LINK_DIAG_TAG, "[${norm.takeLast(4)}] recording-notification enable requested")
            appendLog("[$address] 重新连接，恢复录制通知…")
        } else {
            Log.i(LINK_DIAG_TAG, "[${norm.takeLast(4)}] recording-notification enable failed")
            appendLog("[$address] 重新连接后启用录制通知失败")
        }
        mgr.requestRecordingState()
    }

    fun eraseAll() {
        val targets = connectedManagerKeys()
        if (targets.isNotEmpty()) {
            _eraseTaskProgress.value = EraseTaskProgress(isErasing = true, totalDevices = targets.size)
        }
        if (!requestStateThenRun(RecordingCommand.Erase)) {
            _eraseTaskProgress.value = EraseTaskProgress()
        }
    }

    private fun doEraseAll(targets: Set<String>) {
        _eraseTaskProgress.value = EraseTaskProgress(isErasing = true, totalDevices = targets.size)
        targets.forEach { addr ->
            val mgr = managers[addr] ?: return@forEach
            appendLog("[${mgr.mDevice?.address ?: addr}] 正在擦除 Flash…")
            if (!mgr.eraseRecordingData()) {
                val task = _eraseTaskProgress.value
                val failed = task.failedDevices + addr
                val finished = task.completedDevices + failed
                _eraseTaskProgress.value = task.copy(
                    isErasing = finished.size < task.totalDevices,
                    failedDevices = failed
                )
                appendLog("[${mgr.mDevice?.address ?: addr}] 发送擦除 Flash 失败")
            }
        }
    }

    fun startRecording() {
        if (_recordingPhase.value != FlashRecordingPhase.Idle &&
            _recordingPhase.value != FlashRecordingPhase.Starting
        ) {
            appendLog("当前不能开始录制：${_recordingPhase.value}")
            return
        }
        _recordingPhase.value = FlashRecordingPhase.Starting
        if (!requestStateThenRun(RecordingCommand.Start)) {
            updateRecordingPhaseFromState()
        }
    }

    fun prepareStartRecording(): Boolean {
        if (_recordingPhase.value != FlashRecordingPhase.Idle) {
            appendLog("当前不能开始录制：${_recordingPhase.value}")
            return false
        }
        _recordingPhase.value = FlashRecordingPhase.Starting
        return true
    }

    private fun doStartRecording(targets: Set<String>) {
        autoExportAfterStop = null
        pendingAutoFileInfoTargets.clear()
        pendingAutoFileInfoRequests.clear()
        _preparingRecordingExport.value = false
        _pendingRecordingExportKeys.value = emptySet()
        _exportProgress.value = emptyMap()
        _exportDone.value = emptySet()
        _exportTaskProgress.value = ExportTaskProgress()
        recordingSessionBaselineKeys = fileKeysForTargets(targets)
        recordingSessionTargets = targets
        recordingSessionStartUtcMs = System.currentTimeMillis()
        try {
            // 用安全访问替代 !! 防止 mDevice 在同步后被 SDK 内部修改
            val unsynced = targets.mapNotNull { key ->
                val mgr = managers[key] ?: return@mapNotNull null
                val dev = mgr.mDevice ?: return@mapNotNull null
                if (!dev.isSynced) (dev.address ?: key) else null
            }
            if (unsynced.isNotEmpty()) {
                appendLog("⚠ 以下设备未同步，SampleTimeFine 可能无法跨设备对齐: $unsynced")
            }
        } catch (e: Exception) {
            Log.w(TAG, "startRecording sync check failed: ${e.message}")
        }
        pendingStartAcks.clear()
        pendingStartAcks.addAll(targets)
        startAckTargets = targets
        _recordingPhase.value = FlashRecordingPhase.Starting
        targets.forEach { norm ->
            val mgr = managers[norm] ?: return@forEach
            val commandUtcMs = System.currentTimeMillis()
            val isSyncedAtStart = runCatching { mgr.mDevice?.isSynced }.getOrNull()
            recordingAnchors[norm] = RecordingClockAnchors(
                startCommandUtcMs = commandUtcMs,
                isSyncedAtStart = isSyncedAtStart,
            )
            if (!mgr.startRecording()) {
                pendingStartAcks.remove(norm)
                appendLog("[${mgr.mDevice?.address ?: norm}] 发送开始录制失败")
            }
        }
        if (pendingStartAcks.isEmpty()) {
            finishStartAcksIfComplete()
        }
        appendLog("已发送开始录制，等待 SDK ACK（${pendingStartAcks.size} 台设备）")
        mainHandler.postDelayed({
            if (pendingStartAcks.isNotEmpty()) {
                val timedOut = pendingStartAcks.toSet()
                pendingStartAcks.removeAll(timedOut)
                timedOut.forEach { activeRecordingDevices.remove(it) }
                updateRecordingActive()
                appendLog("开始录制 ACK 超时：${timedOut.joinToString()}")
                finishStartAcksIfComplete()
            }
        }, 6_000L)
    }

    fun stopRecording() {
        if (_recordingPhase.value != FlashRecordingPhase.Recording) {
            appendLog("当前不能停止录制：${_recordingPhase.value}")
            return
        }
        _recordingPhase.value = FlashRecordingPhase.Stopping
        if (!requestStateThenRun(RecordingCommand.Stop)) {
            updateRecordingPhaseFromState()
        }
    }

    fun forceStopRecording() {
        if (managers.isEmpty()) {
            appendLog("没有可操作的设备")
            return
        }
        val targets = connectedManagerKeys().ifEmpty { managers.keys }
        if (targets.isEmpty()) {
            appendLog("没有已连接设备可停止录制")
            return
        }
        pendingOperation = null
        pendingStateChecks.clear()
        pendingStopAcks.clear()
        pendingStopAcks.addAll(targets)
        stopAckTargets = targets
        _recordingPhase.value = FlashRecordingPhase.Stopping
        targets.forEach { norm ->
            val mgr = managers[norm] ?: return@forEach
            if (!mgr.stopRecording()) {
                pendingStopAcks.remove(norm)
                appendLog("[${mgr.mDevice?.address ?: norm}] 发送强制停止录制失败")
            }
        }
        appendLog("已发送强制停止录制，等待 SDK ACK（${pendingStopAcks.size} 台设备）")
        mainHandler.postDelayed({
            if (pendingStopAcks.isNotEmpty()) {
                val timedOut = pendingStopAcks.toSet()
                appendLog("强制停止未收到 ACK，正在复查设备状态：${timedOut.joinToString()}")
                timedOut.forEach { norm -> managers[norm]?.requestRecordingState() }
                mainHandler.postDelayed({
                    val unresolved = pendingStopAcks.toSet()
                    val confirmedStopped = unresolved.filter {
                        latestRecordingStates[it] != DotRecordingState.onRecording
                    }
                    confirmedStopped.forEach { norm ->
                        pendingStopAcks.remove(norm)
                        activeRecordingDevices.remove(norm)
                    }
                    if (pendingStopAcks.isNotEmpty()) {
                        val stillRecording = pendingStopAcks.toSet()
                        pendingStopAcks.clear()
                        stopAckTargets = emptySet()
                        _recordingPhase.value = FlashRecordingPhase.Recording
                        updateRecordingActive()
                        appendLog(
                            "仍有设备未确认停止，请保持设备靠近后重试：${stillRecording.joinToString()}"
                        )
                    } else {
                        finishStopAcksIfComplete()
                    }
                }, 2_000L)
            }
        }, 3_000L)
    }

    private fun doStopRecording(targets: Set<String>) {
        pendingStopAcks.clear()
        stopAckTargets = targets
        _recordingPhase.value = FlashRecordingPhase.Stopping
        targets.forEach { norm ->
            val mgr = managers[norm] ?: return@forEach
            val state = latestRecordingStates[norm]
            if (state != DotRecordingState.onRecording && norm in activeRecordingDevices) {
                activeRecordingDevices.remove(norm)
                appendLog("[${mgr.mDevice?.address ?: norm}] SDK 状态已非录制中，本地状态已更新")
                return@forEach
            }
            pendingStopAcks.add(norm)
            if (!mgr.stopRecording()) {
                pendingStopAcks.remove(norm)
                appendLog("[${mgr.mDevice?.address ?: norm}] 发送停止录制失败")
            }
        }
        updateRecordingActive()
        if (pendingStopAcks.isEmpty()) {
            finishStopAcksIfComplete()
        } else {
            scheduleStopAckTimeout(label = "停止录制")
        }
        appendLog("已发送停止录制，等待 SDK ACK（${pendingStopAcks.size} 台设备）")
    }

    fun requestFileInfo() {
        requestStateThenRun(RecordingCommand.RequestFiles)
    }

    private fun doRequestFileInfo(targets: Set<String>) {
        _fileList.value = emptyMap()
        targets.forEach { addr -> managers[addr]?.requestFileInfo() }
        appendLog("正在获取文件列表…")
    }

    /** 对所有设备并行导出全部文件。 */
    fun exportAll() {
        requestStateThenRun(RecordingCommand.ExportAll)
    }

    private fun doExportAll(targets: Set<String>) {
        val sortedIds = _selectedExportIds.value.sorted()
        val exportIds = ByteArray(sortedIds.size) { sortedIds[it] }
        val fieldLabels = sortedIds.mapNotNull { id ->
            ALL_EXPORT_FIELDS.find { it.id == id }?.label
        }.joinToString(", ")

        val exportTargets = targets.filter { addr -> !_fileList.value[addr].isNullOrEmpty() }
        val targetFiles = exportTargets
            .flatMap { addr ->
                _fileList.value[addr].orEmpty().map { file ->
                    exportFileKey(addr, file) to file.dataSize.toLong().coerceAtLeast(1L)
                }
            }
            .toMap()
        val targetFrames = exportTargets
            .flatMap { addr ->
                _fileList.value[addr].orEmpty().map { file ->
                    exportFileKey(addr, file) to estimatedExportFrameCount(file, exportIds)
                }
            }
            .toMap()
        resetExportTracking(targetFiles, targetFrames)
        val skipped = targets.filter { addr -> _fileList.value[addr].isNullOrEmpty() }
        skipped.forEach { addr ->
            val rawAddr = managers[addr]?.mDevice?.address ?: addr
            appendLog("[$rawAddr] 无文件可导出，跳过")
        }
        appendLog("导出字段：$fieldLabels | 并行导出 ${exportTargets.size} 台设备")
        startParallelDeviceExports(
            exportTargets.mapNotNull { addr ->
                _fileList.value[addr]?.let { files ->
                    DeviceExportRequest(addr, files, exportIds.copyOf())
                }
            }
        )
    }

    /** 仅导出 selectedKeys 中指定的文件（key = "$normalizedAddr-$fileId"）*/
    fun exportSelected(selectedKeys: Set<String>) {
        requestStateThenRun(RecordingCommand.ExportSelected, selectedKeys)
    }

    private fun doExportSelected(checkedTargets: Set<String>, selectedKeys: Set<String>) {
        val sortedIds = _selectedExportIds.value.sorted()
        val exportIds = ByteArray(sortedIds.size) { sortedIds[it] }
        val fieldLabels = sortedIds.mapNotNull { id ->
            ALL_EXPORT_FIELDS.find { it.id == id }?.label
        }.joinToString(", ")

        val targets = checkedTargets.filter { addr ->
            val files = _fileList.value[addr]?.filter { f -> "$addr-${f.fileId}" in selectedKeys }
            !files.isNullOrEmpty()
        }
        val targetFiles = targets
            .flatMap { addr ->
                _fileList.value[addr]
                    .orEmpty()
                    .filter { file -> "$addr-${file.fileId}" in selectedKeys }
                    .map { file -> exportFileKey(addr, file) to file.dataSize.toLong().coerceAtLeast(1L) }
            }
            .toMap()
        val targetFrames = targets
            .flatMap { addr ->
                _fileList.value[addr]
                    .orEmpty()
                    .filter { file -> "$addr-${file.fileId}" in selectedKeys }
                    .map { file ->
                        exportFileKey(addr, file) to estimatedExportFrameCount(file, exportIds)
                    }
            }
            .toMap()
        resetExportTracking(targetFiles, targetFrames)
        appendLog("导出字段：$fieldLabels | 并行导出 ${targets.size} 台设备")
        startParallelDeviceExports(
            targets.mapNotNull { addr ->
                val files = _fileList.value[addr]
                    ?.filter { file -> "$addr-${file.fileId}" in selectedKeys }
                    .orEmpty()
                files.takeIf { it.isNotEmpty() }?.let {
                    DeviceExportRequest(addr, it, exportIds.copyOf())
                }
            }
        )
    }

    private fun startParallelDeviceExports(requests: List<DeviceExportRequest>) {
        mainHandler.removeCallbacks(exportWatchdogRunnable)
        exportRequests.clear()
        exportStartedDevices.clear()
        exportDevicesWithData.clear()
        exportResettingDevices.clear()
        exportLastProgressAt.clear()
        exportRetryCounts.clear()
        clearExportRuntimeTracking()
        exportStopRequested = false
        if (requests.isEmpty()) {
            val task = _exportTaskProgress.value
            _exportTaskProgress.value = task.copy(
                isExporting = false,
                failedFileKeys = task.targetBytesByFile.keys,
            )
            appendLog("没有可启动的设备导出任务")
            return
        }
        requests.forEachIndexed { index, request ->
            val norm = normalizeAddress(request.address)
            exportRequests[norm] = request.copy(address = norm)
            exportLastProgressAt[norm] = System.currentTimeMillis()
            exportResettingDevices.add(norm)
            setExportLinkPriority(norm, highPriority = true)
            managers[norm]?.stopExporting()
            mainHandler.postDelayed(
                { startDeviceExport(norm) },
                600L + index * 400L
            )
        }
        mainHandler.postDelayed(exportWatchdogRunnable, EXPORT_WATCHDOG_INTERVAL_MS)
    }

    private fun startDeviceExport(address: String) {
        if (exportStopRequested) return
        val norm = normalizeAddress(address)
        val request = exportRequests[norm] ?: return
        val manager = managers[norm]
        if (manager == null) {
            failDeviceExport(norm, "设备导出管理器不存在")
            return
        }

        val isRetry = exportRetryCounts.getOrDefault(norm, 0) > 0
        closeDeviceExportWriters(norm, deletePartial = isRetry)
        resetDeviceExportRuntime(norm, resetPublishedProgress = isRetry)
        appendLog("[${manager.mDevice?.address ?: norm}] 开始导出 ${request.files.size} 个文件…")
        Log.i(TAG, "[$norm] start exporting ${request.files.size} files")
        if (!manager.selectExportedData(request.exportIds)) {
            failDeviceExport(norm, "设置导出字段失败")
            return
        }
        exportStartedAt[norm] = SystemClock.elapsedRealtime()
        if (exportSessionStartedAt == 0L) {
            exportSessionStartedAt = exportStartedAt[norm] ?: 0L
        }
        exportStartedDevices.add(norm)
        if (!manager.startExporting(ArrayList(request.files))) {
            exportStartedDevices.remove(norm)
            exportStartedAt.remove(norm)
            retryDeviceExport(norm, "启动导出失败")
            return
        }
        exportLastProgressAt[norm] = System.currentTimeMillis()
        val task = _exportTaskProgress.value
        _exportTaskProgress.value = task.copy(isExporting = true)
    }

    private fun failDeviceExport(address: String, reason: String) {
        val norm = normalizeAddress(address)
        synchronized(exportProgressPublishLock) {
            val task = _exportTaskProgress.value
            val deviceFileKeys = task.targetBytesByFile.keys
                .filter { it.startsWith("$norm-") }
                .toSet()
            val failed = task.failedFileKeys + deviceFileKeys
            val finished = task.completedFileKeys + failed
            _exportTaskProgress.value = task.copy(
                isExporting = task.totalFiles > 0 && finished.size < task.totalFiles,
                activeFileKeys = task.activeFileKeys - deviceFileKeys,
                failedFileKeys = failed,
            )
        }
        exportStartedDevices.remove(norm)
        exportDevicesWithData.remove(norm)
        exportResettingDevices.remove(norm)
        exportLastProgressAt.remove(norm)
        exportRetryCounts.remove(norm)
        exportRequests.remove(norm)
        setExportLinkPriority(norm, highPriority = false)
        closeDeviceExportWriters(norm, deletePartial = true)
        resetDeviceExportRuntime(norm, resetPublishedProgress = false)
        appendLog("[${managers[norm]?.mDevice?.address ?: norm}] $reason")
        Log.e(TAG, "[$norm] $reason")
        finishExportSessionIfDone()
    }

    private fun retryDeviceExport(address: String, reason: String) {
        val norm = normalizeAddress(address)
        if (exportStopRequested || !exportRequests.containsKey(norm)) return
        val retries = exportRetryCounts.getOrDefault(norm, 0)
        if (retries >= EXPORT_MAX_RETRIES) {
            failDeviceExport(norm, "$reason，重试 $retries 次后仍失败")
            return
        }
        exportRetryCounts[norm] = retries + 1
        exportStartedDevices.remove(norm)
        exportDevicesWithData.remove(norm)
        exportLastProgressAt[norm] = System.currentTimeMillis()
        exportResettingDevices.add(norm)
        closeDeviceExportWriters(norm, deletePartial = true)
        resetDeviceExportRuntime(norm, resetPublishedProgress = true)
        appendLog("[${managers[norm]?.mDevice?.address ?: norm}] $reason，正在重试 ${retries + 1}/$EXPORT_MAX_RETRIES")
        Log.w(TAG, "[$norm] retry export ${retries + 1}/$EXPORT_MAX_RETRIES: $reason")
        managers[norm]?.stopExporting()
        setExportLinkPriority(norm, highPriority = true)
        mainHandler.postDelayed({ startDeviceExport(norm) }, 800L)
    }

    private fun closeDeviceExportWriters(address: String, deletePartial: Boolean) {
        val norm = normalizeAddress(address)
        exportWriters.keys.filter { it.startsWith("$norm-") }.forEach { key ->
            val writer = exportWriters.remove(key)
            writer?.close()
            if (deletePartial && writer != null) {
                runCatching { File(writer.filePath).delete() }
            }
        }
        exportWriterFailures.removeIf { it.startsWith("$norm-") }
    }

    private fun finishExportSessionIfDone() {
        val done = synchronized(exportProgressPublishLock) {
            val task = _exportTaskProgress.value
            val sessionDone = !task.hasPendingFiles || exportRequests.isEmpty()
            _exportTaskProgress.value = task.copy(
                isExporting = !sessionDone,
                activeFileKeys = if (sessionDone) emptySet() else task.activeFileKeys,
            )
            sessionDone
        }
        if (done) {
            mainHandler.removeCallbacks(exportWatchdogRunnable)
            val startedAt = exportSessionStartedAt
            if (startedAt > 0L) {
                val elapsedMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1L)
                Log.i(TAG, "export session timing: elapsed=${elapsedMs}ms")
            }
            exportSessionStartedAt = 0L
        }
    }

    fun stopExporting() {
        mainHandler.removeCallbacksAndMessages(null)
        exportStopRequested = true
        exportRequests.clear()
        exportStartedDevices.clear()
        exportDevicesWithData.clear()
        exportResettingDevices.clear()
        exportLastProgressAt.clear()
        exportRetryCounts.clear()
        clearExportRuntimeTracking()
        pendingOperation = null
        pendingStateChecks.clear()
        pendingAutoFileInfoTargets.clear()
        pendingAutoFileInfoRequests.clear()
        autoExportAfterStop = null
        managers.values.forEach { it.stopExporting() }
        managers.keys.forEach { setExportLinkPriority(it, highPriority = false) }
        exportWriters.values.forEach { it.close() }
        exportWriters.clear()
        exportWriterFailures.clear()
        val task = _exportTaskProgress.value
        val unfinished = task.targetBytesByFile.keys - task.finishedFileKeys
        _exportTaskProgress.value = task.copy(
            isExporting = false,
            activeFileKeys = emptySet(),
            failedFileKeys = task.failedFileKeys + unfinished,
        )
        if (unfinished.isNotEmpty()) {
            appendLog("已停止导出，${unfinished.size} 个文件未完成")
        }
    }

    fun clear() {
        stopExporting()
        managers.values.forEach { it.clear() }
        managers.clear()
        totalDevices = 0
        activeRecordingDevices.clear()
        pendingStartAcks.clear()
        pendingStopAcks.clear()
        latestRecordingStates.clear()
        pendingStateChecks.clear()
        lastNotificationEnableRequestAt.clear()
        pendingAutoFileInfoTargets.clear()
        pendingAutoFileInfoRequests.clear()
        startAckTargets = emptySet()
        stopAckTargets = emptySet()
        recordingSessionStartUtcMs = null
        recordingSessionBaselineKeys = emptySet()
        recordingSessionTargets = emptySet()
        autoExportAfterStop = null
        _preparingRecordingExport.value = false
        _pendingRecordingExportKeys.value = emptySet()
        pendingOperation = null
        _exportProgress.value = emptyMap()
        _exportDone.value = emptySet()
        _exportTaskProgress.value = ExportTaskProgress()
        _eraseTaskProgress.value = EraseTaskProgress()
        updateRecordingActive()
        _recordingPhase.value = FlashRecordingPhase.Idle
    }

    // ── DotRecordingCallback ──

    override fun onDotRecordingNotification(address: String?, isEnabled: Boolean) {
        val addr = address ?: return
        val norm = normalizeAddress(addr)
        if (isEnabled) {
            val wasReady = norm in _notificationReady.value
            _notificationReady.value = _notificationReady.value + norm
            if (!wasReady) {
                appendLog("[$addr] 录制通知已启用")
            }
            managers[norm]?.requestFlashInfo()
            managers[norm]?.requestRecordingState()
        } else {
            _notificationReady.value = _notificationReady.value - norm
            appendLog("[$addr] 录制通知未能启用")
        }
    }

    override fun onDotRequestFlashInfoDone(address: String?, usedFlashSpace: Int, totalFlashSpace: Int) {
        val addr = address ?: return
        val norm = normalizeAddress(addr)
        _flashInfo.value = _flashInfo.value.toMutableMap().also {
            it[norm] = Pair(usedFlashSpace, totalFlashSpace)
        }
        appendLog("[$addr] Flash: ${usedFlashSpace / 1024}KB / ${totalFlashSpace / 1024}KB 已用")
        if (
            norm in pendingAutoFileInfoTargets &&
            autoExportAfterStop != null &&
            pendingAutoFileInfoRequests.add(norm)
        ) {
            mainHandler.postDelayed({
                if (norm in pendingAutoFileInfoTargets && autoExportAfterStop != null) {
                    managers[norm]?.requestFileInfo()
                }
            }, 150L)
        }
    }

    override fun onDotEraseDone(address: String?, isSuccess: Boolean) {
        val addr = address ?: return
        appendLog("[$addr] 擦除${if (isSuccess) "成功 ✓" else "失败 ✗"}")
        val norm = normalizeAddress(addr)
        val task = _eraseTaskProgress.value
        if (task.totalDevices > 0) {
            val completed = if (isSuccess) task.completedDevices + norm else task.completedDevices
            val failed = if (isSuccess) task.failedDevices else task.failedDevices + norm
            val finished = completed + failed
            _eraseTaskProgress.value = task.copy(
                isErasing = finished.size < task.totalDevices,
                completedDevices = completed,
                failedDevices = failed
            )
        }
        if (isSuccess) {
            _fileList.value = _fileList.value.toMutableMap().also { it.remove(norm) }
            managers[norm]?.requestFlashInfo()
        }
    }

    override fun onDotRecordingAck(
        address: String,
        recordingId: Int,
        isSuccess: Boolean,
        recordingState: DotRecordingState
    ) {
        val action = when (recordingId) {
            DotRecordingManager.RECORDING_ID_START_RECORDING        -> "开始录制"
            DotRecordingManager.RECORDING_ID_STOP_RECORDING         -> "停止录制"
            DotRecordingManager.RECORDING_ID_GET_STATE              -> "查询状态"
            DotRecordingManager.RECORDING_ID_REQUEST_RECORDING_TIME -> "查询时长"
            else -> "操作($recordingId)"
        }
        val norm = normalizeAddress(address)
        if (recordingId == DotRecordingManager.RECORDING_ID_START_RECORDING &&
            !pendingStartAcks.contains(norm)
        ) {
            return
        }
        if (recordingId == DotRecordingManager.RECORDING_ID_STOP_RECORDING &&
            !pendingStopAcks.contains(norm)
        ) {
            return
        }
        appendLog("[$address] $action ${if (isSuccess) "✓" else "✗"}  state=$recordingState")
        val nowUtcMs = System.currentTimeMillis()
        if (recordingId == DotRecordingManager.RECORDING_ID_GET_STATE) {
            // The DOT keeps Flash recording independently from the app. Always accept a queried
            // state so reconnecting/restarting the app restores the actual device recording state.
            syncLocalRecordingState(address, recordingState, applyActiveState = true)
            handlePendingState(address, recordingState)
            return
        }
        when (recordingId) {
            DotRecordingManager.RECORDING_ID_START_RECORDING -> {
                if (!pendingStartAcks.remove(norm)) return
                if (isSuccess) {
                    syncLocalRecordingState(address, DotRecordingState.onRecording, applyActiveState = true)
                    activeRecordingDevices.add(norm)
                    recordingAnchors.compute(norm) { _, old ->
                        (old ?: RecordingClockAnchors()).copy(startAckUtcMs = nowUtcMs)
                    }
                    appendLog("[$address] startAckUtcMs=$nowUtcMs")
                } else {
                    activeRecordingDevices.remove(norm)
                }
                updateRecordingActive()
                if (pendingStartAcks.isEmpty()) {
                    finishStartAcksIfComplete()
                }
            }
            DotRecordingManager.RECORDING_ID_STOP_RECORDING -> {
                if (!pendingStopAcks.remove(norm)) return
                if (isSuccess) {
                    syncLocalRecordingState(address, DotRecordingState.idle, applyActiveState = true)
                    activeRecordingDevices.remove(norm)
                }
                updateRecordingActive()
                if (pendingStopAcks.isEmpty()) {
                    finishStopAcksIfComplete()
                }
            }
        }
    }

    override fun onDotGetRecordingTime(
        address: String?,
        startUTCSeconds: Int,
        totalRecordingSeconds: Int,
        remainingRecordingSeconds: Int
    ) {
        val addr = address ?: return
        appendLog("[$addr] 录制时长: ${totalRecordingSeconds}s  剩余: ${remainingRecordingSeconds}s")
    }

    override fun onDotRequestFileInfoDone(
        address: String?,
        list: ArrayList<DotRecordingFileInfo>?,
        isSuccess: Boolean
    ) {
        val addr = address ?: return
        val norm = normalizeAddress(addr)
        if (isSuccess && !list.isNullOrEmpty()) {
            _fileList.value = _fileList.value.toMutableMap().also { it[norm] = list.toList() }
            appendLog("[$addr] 共 ${list.size} 个录制文件")
            list.forEach { f ->
                appendLog("  ↳ ${f.fileName}  ${f.dataSize / 1024}KB")
            }
        } else {
            appendLog("[$addr] ${if (isSuccess) "无录制文件" else "获取文件列表失败"}")
            if (isSuccess) {
                _fileList.value = _fileList.value.toMutableMap().also { it[norm] = emptyList() }
            }
        }
        if (norm in pendingAutoFileInfoTargets) {
            pendingAutoFileInfoTargets.remove(norm)
            pendingAutoFileInfoRequests.remove(norm)
            finishAutoExportFileInfoIfReady()
        }
    }

    override fun onDotDataExported(
        address: String?,
        fileInfo: DotRecordingFileInfo?,
        exportedData: DotData?
    ) {
        val addr = address ?: return
        val info = fileInfo ?: return
        val data = exportedData ?: return
        val norm = normalizeAddress(addr)
        val writerKey = exportFileKey(addr, info)
        if (norm !in exportStartedDevices || writerKey in exportWriterFailures) return
        exportDevicesWithData.add(norm)
        exportResettingDevices.remove(norm)
        exportLastProgressAt[norm] = System.currentTimeMillis()
        exportFirstDataAt.putIfAbsent(norm, SystemClock.elapsedRealtime())
        try {
            val writer = exportWriters.getOrPut(writerKey) {
                ExportCsvWriter.create(addr, info, _selectedExportIds.value, recordingAnchors[norm])
            }
            val bytesWritten = writer.write(data)
            exportFrameCounts.computeIfAbsent(writerKey) { AtomicLong() }.incrementAndGet()
            exportDeviceFrameCounts.computeIfAbsent(norm) { AtomicLong() }.incrementAndGet()
            exportWrittenBytes[writerKey] = bytesWritten
            publishExportProgress(norm, writerKey)
        } catch (e: Exception) {
            if (exportWriterFailures.add(writerKey)) {
                exportWriters.remove(writerKey)?.close()
                synchronized(exportProgressPublishLock) {
                    val task = _exportTaskProgress.value
                    val failed = task.failedFileKeys + writerKey
                    val finished = task.completedFileKeys + failed
                    _exportTaskProgress.value = task.copy(
                        isExporting = task.totalFiles > 0 && finished.size < task.totalFiles,
                        activeFileKeys = task.activeFileKeys - writerKey,
                        failedFileKeys = failed,
                    )
                }
                appendLog("[$addr] 导出写盘失败：${e.message ?: "未知错误"}")
                Log.e(TAG, "[$addr] export write failed: ${e.message}", e)
            }
        }
    }

    override fun onDotDataExported(address: String?, fileInfo: DotRecordingFileInfo?) {
        val addr = address ?: return
        val info = fileInfo ?: return
        val norm = normalizeAddress(addr)
        val writerKey = exportFileKey(addr, info)
        exportLastProgressAt[norm] = System.currentTimeMillis()
        exportWriters.remove(writerKey)?.close()
        publishExportProgress(norm, writerKey, force = true)
        synchronized(exportProgressPublishLock) {
            val task = _exportTaskProgress.value
            val completed = task.completedFileKeys + writerKey
            val finished = completed + task.failedFileKeys
            val targetBytes = task.targetBytesByFile[writerKey]
                ?: task.writtenBytesByFile[writerKey]
                ?: 1L
            val targetFrames = task.targetFramesByFile[writerKey]
                ?: task.framesByFile[writerKey]
                ?: 1
            _exportTaskProgress.value = task.copy(
                isExporting = task.totalFiles > 0 && finished.size < task.totalFiles,
                activeFileKeys = task.activeFileKeys - writerKey,
                framesByFile = task.framesByFile.toMutableMap().also {
                    it[writerKey] = targetFrames
                },
                writtenBytesByFile = task.writtenBytesByFile.toMutableMap().also {
                    it[writerKey] = targetBytes
                },
                completedFileKeys = completed,
            )
        }
        exportFrameCounts.remove(writerKey)
        exportWrittenBytes.remove(writerKey)
        appendLog("[$addr] 文件 ${info.fileName} 导出完成 ✓")
    }

    override fun onDotAllDataExported(address: String?) {
        val addr = address ?: return
        val norm = normalizeAddress(addr)
        if (norm !in exportStartedDevices) {
            appendLog("[$addr] 忽略导出启动前的旧完成回调")
            return
        }
        val currentTask = _exportTaskProgress.value
        val deviceFileKeys = currentTask.targetBytesByFile.keys
            .filter { it.startsWith("$norm-") }
            .toSet()
        val hasCompletedFile = deviceFileKeys.any { it in currentTask.completedFileKeys }
        if (norm !in exportDevicesWithData && !hasCompletedFile) {
            appendLog("[$addr] 忽略没有数据的旧完成回调")
            return
        }
        if (deviceFileKeys.isNotEmpty()) {
            synchronized(exportProgressPublishLock) {
                val task = _exportTaskProgress.value
                val completed = task.completedFileKeys + (deviceFileKeys - task.failedFileKeys)
                val finished = completed + task.failedFileKeys
                _exportTaskProgress.value = task.copy(
                    isExporting = task.totalFiles > 0 && finished.size < task.totalFiles,
                    activeFileKeys = task.activeFileKeys - deviceFileKeys,
                    framesByFile = task.framesByFile.toMutableMap().also { map ->
                        deviceFileKeys.forEach { key ->
                            map[key] = task.targetFramesByFile[key] ?: map[key] ?: 1
                        }
                    },
                    writtenBytesByFile = task.writtenBytesByFile.toMutableMap().also { map ->
                        deviceFileKeys.forEach { key ->
                            map[key] = task.targetBytesByFile[key] ?: map[key] ?: 1L
                        }
                    },
                    completedFileKeys = completed,
                )
            }
        }
        synchronized(exportProgressPublishLock) {
            _exportDone.value = _exportDone.value + norm
        }
        exportStartedDevices.remove(norm)
        exportDevicesWithData.remove(norm)
        exportResettingDevices.remove(norm)
        exportLastProgressAt.remove(norm)
        exportRetryCounts.remove(norm)
        exportRequests.remove(norm)
        setExportLinkPriority(norm, highPriority = false)
        val finishedAt = SystemClock.elapsedRealtime()
        val startedAt = exportStartedAt.remove(norm)
        val firstDataAt = exportFirstDataAt.remove(norm)
        val frames = exportDeviceFrameCounts.remove(norm)?.get() ?: 0L
        exportLastUiPublishAt.remove(norm)
        if (startedAt != null) {
            val elapsedMs = (finishedAt - startedAt).coerceAtLeast(1L)
            val firstDataDelayMs = firstDataAt?.let { (it - startedAt).coerceAtLeast(0L) }
            val framesPerSecond = frames * 1000.0 / elapsedMs.toDouble()
            Log.i(
                TAG,
                "[$norm] export timing: elapsed=${elapsedMs}ms, " +
                    "firstData=${firstDataDelayMs ?: -1L}ms, frames=$frames, " +
                    "throughput=${String.format(Locale.US, "%.1f", framesPerSecond)} frames/s"
            )
        }
        appendLog("[$addr] 全部文件导出完成 ✓")
        Log.i(TAG, "[$addr] export done")
        finishExportSessionIfDone()
    }

    override fun onDotStopExportingData(address: String?) {
        val addr = address ?: return
        val norm = normalizeAddress(addr)
        if (!exportStopRequested) {
            if (exportResettingDevices.remove(norm)) {
                appendLog("[$addr] SDK 导出状态已重置")
            } else if (exportRequests.containsKey(norm)) {
                retryDeviceExport(norm, "SDK 导出回调意外停止")
            }
            return
        }
        closeDeviceExportWriters(norm, deletePartial = false)
        val task = _exportTaskProgress.value
        val remainingActive = task.activeFileKeys.filterNot { it.startsWith(norm) }.toSet()
        _exportTaskProgress.value = task.copy(
            isExporting = remainingActive.isNotEmpty(),
            activeFileKeys = remainingActive,
        )
        appendLog("[$addr] 导出已停止")
    }

    // ── 内部 CSV 写入器 ──

    private class ExportCsvWriter private constructor(
        private val fileWriter: BufferedWriter,
        val filePath: String,
        private val fields: List<ExportDataField>,  // 按 ID 排序的选中字段
        private val clockAnchors: RecordingClockAnchors?,
        timestampAnchorUtcMs: Long,
    ) {
        private var headerWritten = false
        private val timestampCalculator = TimestampUtcCalculator(timestampAnchorUtcMs)
        private val rowBuilder = StringBuilder(256)
        var bytesWritten: Long = 0L
            private set

        companion object {
            fun create(
                address: String,
                fileInfo: DotRecordingFileInfo,
                selectedIds: Set<Byte>,
                clockAnchors: RecordingClockAnchors?
            ): ExportCsvWriter {
                val fields = ALL_EXPORT_FIELDS
                    .filter { selectedIds.contains(it.id) }
                    .sortedBy { it.id }

                val mac      = address.replace(":", "").replace("-", "").uppercase()
                val recordingDate = recordingDate(fileInfo)
                val ts       = recordingTimestamp(recordingDate)
                val stem     = sanitizeFileStem(fileInfo.fileName).ifBlank { "recording_${fileInfo.fileId}" }
                val baseName = "${mac}_${ts}_${stem}.csv"
                val timestampAnchorUtcMs = clockAnchors?.startAckUtcMs?.takeIf { it > 0L }
                    ?: recordingDate.time

                // 固定写到 Documents/XsensData/offline_export，避免静默落到 App 私有目录。
                val publicDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                    "XsensData/offline_export"
                )
                ensurePublicDirWritable(publicDir)

                var file = File(publicDir, baseName)
                if (file.exists()) {
                    file = File(publicDir, "${mac}_${ts}_${fileInfo.fileId}.csv")
                }
                val fw = BufferedWriter(
                    OutputStreamWriter(FileOutputStream(file, false), Charsets.UTF_8),
                    EXPORT_WRITE_BUFFER_SIZE,
                )
                return ExportCsvWriter(fw, file.absolutePath, fields, clockAnchors, timestampAnchorUtcMs)
            }

            private fun ensurePublicDirWritable(dir: File) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                    throw IOException("缺少所有文件访问权限，无法写入 ${dir.absolutePath}")
                }
                if (!dir.exists() && !dir.mkdirs()) {
                    throw IOException("无法创建目录 ${dir.absolutePath}")
                }
                if (!dir.canWrite()) {
                    throw IOException("目录不可写 ${dir.absolutePath}")
                }
            }

            private fun recordingDate(fileInfo: DotRecordingFileInfo): Date {
                val raw = fileInfo.startRecordingTimestamp
                return when {
                    raw > 10_000_000_000L -> Date(raw)
                    raw > 0L -> Date(raw * 1000L)
                    else -> parseTimestampFromName(fileInfo.fileName) ?: Date()
                }
            }

            private fun recordingTimestamp(date: Date): String {
                return SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(date)
            }

            private fun parseTimestampFromName(name: String?): Date? {
                if (name.isNullOrBlank()) return null
                val match = Regex("(20\\d{6})[_-]?(\\d{6})").find(name) ?: return null
                return runCatching {
                    SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).parse(
                        "${match.groupValues[1]}_${match.groupValues[2]}"
                    )
                }.getOrNull()
            }

            private fun sanitizeFileStem(name: String?): String =
                name
                    ?.substringBeforeLast('.')
                    ?.replace(Regex("[^A-Za-z0-9_-]+"), "_")
                    ?.trim('_')
                    .orEmpty()
        }

        private fun writeMetadataAndHeader() {
            if (headerWritten) return
            val rows = listOf(
                "record_start_command_utc_ms" to (clockAnchors?.startCommandUtcMs?.toString() ?: ""),
                "record_start_ack_utc_ms" to (clockAnchors?.startAckUtcMs?.toString() ?: ""),
                "recording_is_synced" to (clockAnchors?.isSyncedAtStart?.toString() ?: ""),
            )
            rows.forEach { (key, value) ->
                writeCsvText("$key,$value\n")
            }
            writeCsvText("\n")
            writeCsvText("PacketCounter,timestamp_utc_ms," + fields.joinToString(",") { it.columns } + "\n")
            headerWritten = true
        }

        private fun writeCsvText(text: String) {
            fileWriter.write(text)
            // 导出内容只包含 ASCII 列名、数字和分隔符，字符数等于 UTF-8 字节数。
            bytesWritten += text.length.toLong()
        }

        fun write(d: DotData): Long {
            writeMetadataAndHeader()
            val timestampUtcMs = timestampCalculator.timestampUtcMs(
                d.sampleTimeFine.toDouble(),
                d.packetCounter,
            )
            rowBuilder.setLength(0)
            rowBuilder.append(d.packetCounter)
                .append(',')
                .append(timestampUtcMs)
            fields.forEach { field ->
                rowBuilder.append(',').append(field.extract(d))
            }
            rowBuilder.append('\n')
            val rowLength = rowBuilder.length
            fileWriter.append(rowBuilder)
            bytesWritten += rowLength.toLong()
            return bytesWritten
        }

        fun close() {
            try {
                fileWriter.close()
            } catch (e: Exception) {
                Log.e(TAG, "离线 CSV 关闭失败: $filePath ${e.message}", e)
            }
        }
    }
}
