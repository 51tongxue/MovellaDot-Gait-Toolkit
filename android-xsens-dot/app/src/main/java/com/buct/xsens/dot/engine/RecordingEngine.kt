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
import com.buct.xsens.dot.data.DeviceRoleAssignment
import com.buct.xsens.dot.data.LongJumpDeviceRoles
import com.buct.xsens.dot.data.RecordingSessionPreferences
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
import java.util.ArrayDeque
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
    val targetAddresses: Set<String> = emptySet(),
    val activeFileKeys: Set<String> = emptySet(),
    val framesByFile: Map<String, Int> = emptyMap(),
    val targetFramesByFile: Map<String, Int> = emptyMap(),
    val targetBytesByFile: Map<String, Long> = emptyMap(),
    val writtenBytesByFile: Map<String, Long> = emptyMap(),
    val completedFileKeys: Set<String> = emptySet(),
    val failedFileKeys: Set<String> = emptySet(),
) {
    val targetFileKeys: Set<String>
        get() = when {
            targetBytesByFile.isNotEmpty() -> targetBytesByFile.keys
            targetFramesByFile.isNotEmpty() -> targetFramesByFile.keys
            else -> emptySet()
        }

    val finishedFileKeys: Set<String>
        get() = completedFileKeys + failedFileKeys

    val completedTargetFileKeys: Set<String>
        get() = completedFileKeys.intersect(targetFileKeys)

    val failedTargetFileKeys: Set<String>
        get() = failedFileKeys.intersect(targetFileKeys)

    val hasPendingFiles: Boolean
        get() = targetFileKeys.any { it !in finishedFileKeys }
}

enum class FileInfoReadPhase {
    Idle,
    Reading,
    Ready,
    Empty,
    Failed,
}

data class FileInfoReadStatus(
    val phase: FileInfoReadPhase = FileInfoReadPhase.Idle,
    val message: String? = null,
    val requestId: Int = 0,
    val startedAtElapsedMs: Long? = null,
    val completedAtElapsedMs: Long? = null,
)

internal val FileInfoReadStatus.hasFreshFiles: Boolean
    get() = phase == FileInfoReadPhase.Ready

internal fun areFileInfoReadTargetsTerminal(
    statuses: Map<String, FileInfoReadStatus>,
    targets: Set<String>,
): Boolean =
    targets.isNotEmpty() &&
        targets.all { target ->
            statuses[target]?.phase in setOf(
                FileInfoReadPhase.Ready,
                FileInfoReadPhase.Empty,
                FileInfoReadPhase.Failed,
            )
        }

internal fun canImplicitlyExportFileInfo(
    statuses: Map<String, FileInfoReadStatus>,
    targets: Set<String>,
): Boolean =
    targets.isNotEmpty() &&
        targets.all { target ->
            statuses[target]?.phase in setOf(
                FileInfoReadPhase.Ready,
                FileInfoReadPhase.Empty,
            )
        }

data class RecordingExportDecision(
    val id: String,
    val sessionKey: String,
    val targetAddresses: Set<String>,
    val fileKeys: Set<String> = emptySet(),
    val targetFramesByFile: Map<String, Int> = emptyMap(),
    val isPreparing: Boolean = true,
    val errorMessage: String? = null,
) {
    val fileCount: Int
        get() = fileKeys.size

    val isReady: Boolean
        get() = !isPreparing && errorMessage == null && fileKeys.isNotEmpty()
}

internal fun canStartNextRecordingExportPreparation(
    hasActivePreparation: Boolean,
    hasExportTransfer: Boolean,
    queuedSessionCount: Int,
): Boolean =
    !hasActivePreparation &&
        !hasExportTransfer &&
        queuedSessionCount > 0

internal fun estimatedFlashSampleCount(dataSizeBytes: Int): Int =
    ((dataSizeBytes.toLong().coerceAtLeast(1L) + 61L) / 62L)
        .coerceIn(1L, Int.MAX_VALUE.toLong())
        .toInt()

internal fun estimatedRecordedSampleCount(
    startUtcMs: Long,
    stopUtcMs: Long,
    outputRateHz: Int,
): Int {
    val durationMs = (stopUtcMs - startUtcMs).coerceAtLeast(1L)
    val rate = outputRateHz.coerceAtLeast(1)
    return ((durationMs * rate + 500L) / 1_000L)
        .coerceIn(1L, Int.MAX_VALUE.toLong())
        .toInt()
}

internal enum class ExportWatchdogAction {
    Wait,
    Retry,
}

internal fun resolveExportWatchdogAction(
    nowMs: Long,
    attemptStartedAtMs: Long,
    lastProgressAtMs: Long,
    hasReceivedData: Boolean,
    isConnected: Boolean,
    isResetting: Boolean,
    isRestartScheduled: Boolean,
    firstDataTimeoutMs: Long,
    streamingStallTimeoutMs: Long,
): ExportWatchdogAction {
    if (!isConnected || isResetting || isRestartScheduled) {
        return ExportWatchdogAction.Wait
    }
    val referenceTime = if (hasReceivedData) lastProgressAtMs else attemptStartedAtMs
    val timeout = if (hasReceivedData) streamingStallTimeoutMs else firstDataTimeoutMs
    return if (nowMs - referenceTime >= timeout) {
        ExportWatchdogAction.Retry
    } else {
        ExportWatchdogAction.Wait
    }
}

internal class ExportSampleProgressTracker {
    private var previousPacketCounter: Int? = null
    private var samplePosition = 0

    @Synchronized
    fun observe(packetCounter: Int): Int {
        val current = packetCounter and 0xFF
        val previous = previousPacketCounter
        if (previous == null) {
            samplePosition = 1
        } else {
            val delta = (current - previous + 256) % 256
            if (delta > 0) {
                samplePosition += delta
            }
        }
        previousPacketCounter = current
        return samplePosition
    }
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

internal fun aggregateFlashRecordingPhase(
    devicePhases: Collection<FlashRecordingPhase>,
    hasActiveRecordingDevices: Boolean,
): FlashRecordingPhase =
    when {
        devicePhases.any { it == FlashRecordingPhase.Starting } -> FlashRecordingPhase.Starting
        devicePhases.any { it == FlashRecordingPhase.Stopping } -> FlashRecordingPhase.Stopping
        hasActiveRecordingDevices ||
            devicePhases.any { it == FlashRecordingPhase.Recording } -> FlashRecordingPhase.Recording
        else -> FlashRecordingPhase.Idle
    }

internal fun participantHasActiveRecordingOperation(
    devicePhases: Map<String, FlashRecordingPhase>,
    targetAddresses: Set<String>,
): Boolean =
    targetAddresses.any { address ->
        devicePhases[address] in setOf(
            FlashRecordingPhase.Starting,
            FlashRecordingPhase.Recording,
            FlashRecordingPhase.Stopping,
        )
    }

internal fun shouldDeferRecordingNotification(
    phase: FlashRecordingPhase?,
    isKnownActiveRecording: Boolean,
): Boolean =
    isKnownActiveRecording ||
        phase in setOf(
            FlashRecordingPhase.Recording,
            FlashRecordingPhase.Stopping,
        )

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
    private val recordingSessionPreferences = RecordingSessionPreferences(context)

    companion object {
        private const val TAG = "RecordingEngine"
        private const val LINK_DIAG_TAG = "DOT_LINK_DIAG"
        private const val RECORDING_STATE_RECOVERY_ATTEMPTS = 6
        private const val START_AFTER_STATE_QUERY_SETTLE_MS = 500L
        private const val EXPORT_WATCHDOG_INTERVAL_MS = 5_000L
        private const val EXPORT_FIRST_DATA_TIMEOUT_MS = 45_000L
        private const val EXPORT_STREAMING_STALL_TIMEOUT_MS = 60_000L
        private const val EXPORT_RESTART_BASE_DELAY_MS = 900L
        private const val EXPORT_RESTART_STAGGER_MS = 500L
        private const val EXPORT_MAX_RETRIES = 2
        private const val EXPORT_UI_PUBLISH_INTERVAL_MS = 250L
        private const val EXPORT_WRITE_BUFFER_SIZE = 64 * 1024
        private const val FILE_INFO_SINGLE_REQUEST_TIMEOUT_MS = 10_000L
        private const val FILE_INFO_NEXT_DEVICE_DELAY_MS = 250L
        private const val FILE_INFO_BATCH_TIMEOUT_PADDING_MS = 3_000L
        private const val FILE_INFO_CALLBACK_DRAIN_MS = 2_000L
        private const val AUTO_FILE_INFO_REQUEST_ID = -1
        private const val FLASH_USAGE_REFRESH_INTERVAL_MS = 1_000L
        private const val FLASH_STORAGE_BLOCK_BYTES = 4_096L
        private const val STOP_RETRY_TICK_MS = 1_000L
        private const val STOP_RETRY_SLOW_TICK_MS = 5_000L
        private const val STOP_ACK_WAIT_MS = 3_000L
        private const val STOP_STATE_SETTLE_MS = 800L
        private const val STOP_CONFIRMATION_DELAY_WARNING_MS = 20_000L
        private const val SYNC_GATT_QUIET_MS = 2_000L
        // DOT 120 Hz Flash files grow at about 7.44 KB/s: 62 bytes per stored sample.
        private const val FLASH_RECORDING_BYTES_PER_SAMPLE = 62L
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
        val targetFramesByFile: Map<String, Int> = emptyMap(),
        val epoch: Int,
    )

    private data class CompletedRecordingSession(
        val sessionKey: String,
        val targets: Set<String>,
        val startUtcMs: Long,
        val stopUtcMs: Long,
        val outputRatesByTarget: Map<String, Int>,
        val baselineKeys: Set<String>,
        val baselineKnown: Boolean,
    ) {
        val exportDecisionId: String
            get() = "$sessionKey@$stopUtcMs"
    }

    private data class ActiveRecordingSession(
        val sessionKey: String,
        val targets: Set<String>,
        val startUtcMs: Long,
        val outputRatesByTarget: Map<String, Int>,
        val baselineKeys: Set<String>,
    )

    private data class DeviceExportRequest(
        val address: String,
        val files: List<DotRecordingFileInfo>,
        val exportIds: ByteArray,
    )

    private data class ResolvedExportFile(
        val key: String,
        val fileInfo: DotRecordingFileInfo,
    )

    private data class SerialFileInfoRequest(
        val address: String,
        val requestId: Int,
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

    private val _deviceRecordingPhases =
        MutableStateFlow<Map<String, FlashRecordingPhase>>(emptyMap())
    val deviceRecordingPhases: StateFlow<Map<String, FlashRecordingPhase>> =
        _deviceRecordingPhases.asStateFlow()

    private val _recordingStates = MutableStateFlow<Map<String, DotRecordingState>>(emptyMap())
    val recordingStates: StateFlow<Map<String, DotRecordingState>> = _recordingStates.asStateFlow()

    private val _delayedStopConfirmations = MutableStateFlow<Set<String>>(emptySet())
    val delayedStopConfirmations: StateFlow<Set<String>> =
        _delayedStopConfirmations.asStateFlow()

    private val _fileList = MutableStateFlow<Map<String, List<DotRecordingFileInfo>>>(emptyMap())
    val fileList: StateFlow<Map<String, List<DotRecordingFileInfo>>> = _fileList.asStateFlow()
    private val _fileInfoReadStatuses =
        MutableStateFlow<Map<String, FileInfoReadStatus>>(emptyMap())
    val fileInfoReadStatuses: StateFlow<Map<String, FileInfoReadStatus>> =
        _fileInfoReadStatuses.asStateFlow()
    private val _fileInfoReadActiveTargets = MutableStateFlow<Set<String>>(emptySet())
    val fileInfoReadActiveTargets: StateFlow<Set<String>> =
        _fileInfoReadActiveTargets.asStateFlow()
    private val fileInfoReadEpochs = ConcurrentHashMap<String, Int>()
    private val fileInfoCallbackLedger = FileInfoCallbackLedger(::normalizeAddress)
    private val serialFileInfoLock = Any()
    private val serialFileInfoQueue = ArrayDeque<SerialFileInfoRequest>()
    @Volatile private var activeSerialFileInfoRequest: SerialFileInfoRequest? = null
    private var fileInfoReadEpoch = 0

    private val _exportProgress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val exportProgress: StateFlow<Map<String, Int>> = _exportProgress.asStateFlow()

    private val _exportDone = MutableStateFlow<Set<String>>(emptySet())
    val exportDone: StateFlow<Set<String>> = _exportDone.asStateFlow()

    private val _exportTaskProgress = MutableStateFlow(ExportTaskProgress())
    val exportTaskProgress: StateFlow<ExportTaskProgress> = _exportTaskProgress.asStateFlow()

    private val _recordingExportDecisions =
        MutableStateFlow<Map<String, RecordingExportDecision>>(emptyMap())
    val recordingExportDecisions: StateFlow<Map<String, RecordingExportDecision>> =
        _recordingExportDecisions.asStateFlow()

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
    private val exportRestartScheduled = ConcurrentHashMap.newKeySet<String>()
    private val exportDeferredRetryReasons = ConcurrentHashMap<String, String>()
    private val exportLastProgressAt = ConcurrentHashMap<String, Long>()
    private val exportRetryCounts = ConcurrentHashMap<String, Int>()
    private val exportFrameCounts = ConcurrentHashMap<String, AtomicLong>()
    private val exportSampleProgress = ConcurrentHashMap<String, ExportSampleProgressTracker>()
    private val exportDeviceFrameCounts = ConcurrentHashMap<String, AtomicLong>()
    private val exportWrittenBytes = ConcurrentHashMap<String, Long>()
    private val exportStartedAt = ConcurrentHashMap<String, Long>()
    private val exportFirstDataAt = ConcurrentHashMap<String, Long>()
    private val exportLastUiPublishAt = ConcurrentHashMap<String, Long>()
    private val exportRejectedCallbackKeys = ConcurrentHashMap.newKeySet<String>()
    private val exportProgressPublishLock = Any()
    private val exportRestartSequence = AtomicLong(0L)
    @Volatile private var exportSessionStartedAt = 0L
    @Volatile private var exportStopRequested = false

    private val exportWatchdogRunnable = object : Runnable {
        override fun run() {
            if (exportStopRequested || exportRequests.isEmpty()) return
            val now = SystemClock.elapsedRealtime()
            val retryTarget = exportRequests.keys
                .toList()
                .sortedBy { exportLastProgressAt[it] ?: Long.MAX_VALUE }
                .firstOrNull { norm ->
                    val manager = managers[norm]
                    resolveExportWatchdogAction(
                        nowMs = now,
                        attemptStartedAtMs = exportStartedAt[norm]
                            ?: exportLastProgressAt[norm]
                            ?: now,
                        lastProgressAtMs = exportLastProgressAt[norm] ?: now,
                        hasReceivedData = norm in exportDevicesWithData,
                        isConnected =
                            manager?.mDevice?.connectionState == DotDevice.CONN_STATE_CONNECTED,
                        isResetting = exportDeferredRetryReasons.containsKey(norm),
                        isRestartScheduled = norm in exportRestartScheduled,
                        firstDataTimeoutMs = EXPORT_FIRST_DATA_TIMEOUT_MS,
                        streamingStallTimeoutMs = EXPORT_STREAMING_STALL_TIMEOUT_MS,
                    ) == ExportWatchdogAction.Retry
                }
            if (retryTarget != null) {
                val reason = if (retryTarget in exportDevicesWithData) {
                    "导出数据长时间未继续"
                } else {
                    "启动后长时间未收到首帧"
                }
                retryDeviceExport(retryTarget, reason)
            }
            if (!exportStopRequested && exportRequests.isNotEmpty()) {
                mainHandler.postDelayed(this, EXPORT_WATCHDOG_INTERVAL_MS)
            }
        }
    }

    // addr -> latest recording clock anchors captured by this app session.
    private val recordingAnchors = ConcurrentHashMap<String, RecordingClockAnchors>()
    private val recordingSessionUtcByDevice = ConcurrentHashMap<String, Long>()
    private val activeRecordingSessions = ConcurrentHashMap<String, ActiveRecordingSession>()
    private val activeRecordingDevices = ConcurrentHashMap.newKeySet<String>()
    private val pendingStartAcks = ConcurrentHashMap.newKeySet<String>()
    private val pendingStopAcks = ConcurrentHashMap.newKeySet<String>()
    private val reliableStopTargets = ConcurrentHashMap.newKeySet<String>()
    private val reliableStopGroups = ConcurrentHashMap<String, Set<String>>()
    private val stopRequestedAt = ConcurrentHashMap<String, Long>()
    private val stopCommandSentAt = ConcurrentHashMap<String, Long>()
    private val stopStateCheckAt = ConcurrentHashMap<String, Long>()
    private val latestRecordingStates = ConcurrentHashMap<String, DotRecordingState>()
    private val pendingStateChecks = ConcurrentHashMap<String, DotRecordingState>()
    private val lastNotificationEnableRequestAt = ConcurrentHashMap<String, Long>()
    private val recordingFlashBaselines = ConcurrentHashMap<String, Int>()
    private val recordingStartedAtElapsedMs = ConcurrentHashMap<String, Long>()
    private val pendingAutoFileInfoTargets = ConcurrentHashMap.newKeySet<String>()
    private val pendingAutoFileInfoRequests = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var flashUsageMonitoring = false
    @Volatile private var startAckTargets: Set<String> = emptySet()
    @Volatile private var stopAckTargets: Set<String> = emptySet()
    @Volatile private var autoExportAfterStop: CompletedRecordingSession? = null
    private val queuedAutoExportSessions = ArrayDeque<CompletedRecordingSession>()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val reliableStopRunnable = object : Runnable {
        override fun run() {
            if (reliableStopTargets.isEmpty()) return
            attemptReliableStops()
            if (reliableStopTargets.isNotEmpty()) {
                val nextDelay = if (_delayedStopConfirmations.value.isEmpty()) {
                    STOP_RETRY_TICK_MS
                } else {
                    STOP_RETRY_SLOW_TICK_MS
                }
                mainHandler.postDelayed(this, nextDelay)
            }
        }
    }

    @Volatile private var totalDevices = 0
    @Volatile private var operationEpoch = 0
    @Volatile private var pendingOperation: PendingRecordingOperation? = null

    private val flashUsageRefreshRunnable = object : Runnable {
        override fun run() {
            if (!flashUsageMonitoring || activeRecordingDevices.isEmpty()) {
                return
            }

            val now = SystemClock.elapsedRealtime()
            val targets = activeRecordingDevices.toSet()
            val updatedFlashInfo = _flashInfo.value.toMutableMap()
            targets.forEach { norm ->
                val mgr = managers[norm] ?: return@forEach
                val current = updatedFlashInfo[norm] ?: return@forEach
                val baseline = recordingFlashBaselines[norm] ?: current.first
                val startedAt = recordingStartedAtElapsedMs[norm] ?: return@forEach
                val outputRate = mgr.mDevice?.currentOutputRate
                    ?.takeIf { it > 0 }
                    ?: 120
                val elapsedMs = (now - startedAt).coerceAtLeast(0L)
                val payloadBytes =
                    elapsedMs * outputRate * FLASH_RECORDING_BYTES_PER_SAMPLE / 1_000L
                val allocatedBytes = if (payloadBytes > 0L) {
                    ((payloadBytes + FLASH_STORAGE_BLOCK_BYTES - 1L) /
                        FLASH_STORAGE_BLOCK_BYTES) * FLASH_STORAGE_BLOCK_BYTES +
                        FLASH_STORAGE_BLOCK_BYTES
                } else {
                    0L
                }
                val estimatedUsed = (baseline.toLong() + allocatedBytes)
                    .coerceAtMost(current.second.toLong())
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
                updatedFlashInfo[norm] = estimatedUsed to current.second
            }
            _flashInfo.value = updatedFlashInfo
            mainHandler.postDelayed(this, FLASH_USAGE_REFRESH_INTERVAL_MS)
        }
    }

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

    private fun estimatedExportFrameCount(fileInfo: DotRecordingFileInfo): Int =
        estimatedFlashSampleCount(fileInfo.dataSize)

    private fun resolveExportFile(
        norm: String,
        callbackInfo: DotRecordingFileInfo,
    ): ResolvedExportFile? {
        val request = exportRequests[norm] ?: return null
        val requestedFile = request.files.firstOrNull { it.fileId == callbackInfo.fileId }
            ?: callbackInfo.fileName
                .takeIf { it.isNotBlank() }
                ?.let { callbackName ->
                    request.files.firstOrNull { it.fileName == callbackName }
                }
            ?: callbackInfo.startRecordingTimestamp
                .takeIf { it > 0L }
                ?.let { callbackTimestamp ->
                    request.files.firstOrNull {
                        it.startRecordingTimestamp == callbackTimestamp
                    }
                }
            ?: return null
        val key = exportFileKey(norm, requestedFile)
        return key
            .takeIf { it in _exportTaskProgress.value.targetFileKeys }
            ?.let { ResolvedExportFile(it, requestedFile) }
    }

    private fun logRejectedExportCallbackOnce(
        norm: String,
        callbackInfo: DotRecordingFileInfo,
    ) {
        val callbackKey = "$norm-${callbackInfo.fileId}-${callbackInfo.fileName}"
        if (exportRejectedCallbackKeys.add(callbackKey)) {
            Log.w(
                TAG,
                "[$norm] ignored unmatched export callback: " +
                    "fileId=${callbackInfo.fileId}, fileName=${callbackInfo.fileName}",
            )
        }
    }

    private fun resetExportTracking(
        targetAddresses: Set<String>,
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
                targetAddresses = targetAddresses.map(::normalizeAddress).toSet(),
                targetFramesByFile = targetFrames,
                targetBytesByFile = targetFiles,
            )
        }
        exportWriterFailures.clear()
    }

    private fun clearExportRuntimeTracking() {
        exportFrameCounts.clear()
        exportSampleProgress.clear()
        exportDeviceFrameCounts.clear()
        exportWrittenBytes.clear()
        exportStartedAt.clear()
        exportFirstDataAt.clear()
        exportLastUiPublishAt.clear()
        exportRejectedCallbackKeys.clear()
        exportSessionStartedAt = 0L
    }

    private fun resetDeviceExportRuntime(address: String, resetPublishedProgress: Boolean) {
        val norm = normalizeAddress(address)
        val prefix = "$norm-"
        exportFrameCounts.keys.removeIf { it.startsWith(prefix) }
        exportSampleProgress.keys.removeIf { it.startsWith(prefix) }
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
            if (writerKey !in task.targetFileKeys) return
            if (writerKey in task.finishedFileKeys && !force) return
            _exportTaskProgress.value = task.copy(
                isExporting = task.hasPendingFiles,
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

    private fun currentOutputRate(address: String): Int =
        managers[normalizeAddress(address)]
            ?.mDevice
            ?.currentOutputRate
            ?.takeIf { it > 0 }
            ?: 120

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

    private fun startFlashUsageMonitoring(targets: Set<String> = activeRecordingDevices.toSet()) {
        val now = SystemClock.elapsedRealtime()
        targets.forEach { norm ->
            _flashInfo.value[norm]?.first?.let { used ->
                recordingFlashBaselines.putIfAbsent(norm, used)
            }
            recordingStartedAtElapsedMs.putIfAbsent(norm, now)
        }
        flashUsageMonitoring = true
        mainHandler.removeCallbacks(flashUsageRefreshRunnable)
        mainHandler.postDelayed(flashUsageRefreshRunnable, FLASH_USAGE_REFRESH_INTERVAL_MS)
        Log.i(TAG, "live Flash usage estimation started")
    }

    private fun stopFlashUsageMonitoring() {
        flashUsageMonitoring = false
        mainHandler.removeCallbacks(flashUsageRefreshRunnable)
    }

    private fun clearFlashUsageMonitoring(
        targets: Set<String> = recordingFlashBaselines.keys.toSet(),
        restoreBaseline: Boolean = false,
    ) {
        if (restoreBaseline && targets.isNotEmpty()) {
            _flashInfo.value = _flashInfo.value.toMutableMap().also { current ->
                targets.forEach { norm ->
                    val baseline = recordingFlashBaselines[norm] ?: return@forEach
                    val total = current[norm]?.second ?: return@forEach
                    current[norm] = baseline to total
                }
            }
        }
        targets.forEach { norm ->
            recordingFlashBaselines.remove(norm)
            recordingStartedAtElapsedMs.remove(norm)
        }
        if (activeRecordingDevices.isEmpty()) {
            stopFlashUsageMonitoring()
        } else {
            flashUsageMonitoring = true
            mainHandler.removeCallbacks(flashUsageRefreshRunnable)
            mainHandler.postDelayed(flashUsageRefreshRunnable, FLASH_USAGE_REFRESH_INTERVAL_MS)
        }
    }

    private fun setDeviceRecordingPhase(
        targets: Set<String>,
        phase: FlashRecordingPhase,
    ) {
        _deviceRecordingPhases.value = _deviceRecordingPhases.value.toMutableMap().also { phases ->
            targets.forEach { phases[it] = phase }
        }
        updateAggregateRecordingPhase()
    }

    private fun updateAggregateRecordingPhase() {
        _recordingPhase.value = aggregateFlashRecordingPhase(
            devicePhases = _deviceRecordingPhases.value.values,
            hasActiveRecordingDevices = activeRecordingDevices.isNotEmpty(),
        )
    }

    private fun recordingSessionKey(targets: Set<String>): String {
        val slotIds = targets.mapNotNull { target ->
            LongJumpDeviceRoles.assignmentForDevice(target)?.participant?.slotId
        }.distinct()
        return slotIds.singleOrNull() ?: targets.sorted().joinToString("+")
    }

    private fun updateRecordingPhaseFromState() {
        if (activeRecordingDevices.isNotEmpty()) {
            val stoppingTargets = activeRecordingDevices.filter {
                it in reliableStopTargets
            }.toSet()
            val recordingTargets = activeRecordingDevices - stoppingTargets
            if (recordingTargets.isNotEmpty()) {
                setDeviceRecordingPhase(recordingTargets, FlashRecordingPhase.Recording)
            }
            if (stoppingTargets.isNotEmpty()) {
                setDeviceRecordingPhase(stoppingTargets, FlashRecordingPhase.Stopping)
            }
            startFlashUsageMonitoring()
        } else {
            _deviceRecordingPhases.value = _deviceRecordingPhases.value.mapValues {
                FlashRecordingPhase.Idle
            }
            updateAggregateRecordingPhase()
            clearFlashUsageMonitoring()
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
            _deviceRecordingPhases.value[norm] == FlashRecordingPhase.Recording &&
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
            DotRecordingState.onRecording -> {
                activeRecordingDevices.add(norm)
                restoreActiveRecordingSession(norm)
                setDeviceRecordingPhase(
                    setOf(norm),
                    if (norm in reliableStopTargets) {
                        FlashRecordingPhase.Stopping
                    } else {
                        FlashRecordingPhase.Recording
                    }
                )
            }
            DotRecordingState.idle,
            DotRecordingState.success,
            DotRecordingState.fail,
            DotRecordingState.invalidCmd -> {
                activeRecordingDevices.remove(norm)
                setDeviceRecordingPhase(setOf(norm), FlashRecordingPhase.Idle)
            }
            else -> Unit
        }
        updateRecordingActive()
        updateRecordingPhaseFromStateIfStable()
    }

    private fun restoreActiveRecordingSession(deviceId: String) {
        val stored = recordingSessionPreferences.findAssignment(
            deviceId = deviceId,
            recordingUtcMs = 0L,
        ) ?: return
        val participant = stored.assignment.participant
        val targets = participant.normalizedDeviceIds
        if (targets.isEmpty()) return
        val sessionKey = participant.slotId.ifBlank { recordingSessionKey(targets) }
        activeRecordingSessions.putIfAbsent(
            sessionKey,
            ActiveRecordingSession(
                sessionKey = sessionKey,
                targets = targets,
                startUtcMs = stored.sessionUtcMs,
                outputRatesByTarget = targets.associateWith(::currentOutputRate),
                baselineKeys = emptySet(),
            ),
        )
        targets.forEach { target ->
            recordingSessionUtcByDevice.putIfAbsent(target, stored.sessionUtcMs)
        }
    }

    @Synchronized
    private fun finishStartAcksIfComplete() {
        if (pendingStartAcks.isNotEmpty()) return
        val targets = startAckTargets
        if (targets.isEmpty()) return
        startAckTargets = emptySet()
        val sessionKey = recordingSessionKey(targets)
        val recordingTargets = targets.filter { it in activeRecordingDevices }.toSet()
        if (targets.isNotEmpty() && recordingTargets.containsAll(targets)) {
            setDeviceRecordingPhase(targets, FlashRecordingPhase.Recording)
            startFlashUsageMonitoring(targets)
            appendLog("开始录制 ACK 已全部返回")
            return
        }
        clearFlashUsageMonitoring(targets, restoreBaseline = true)
        activeRecordingSessions.remove(sessionKey)
        targets.forEach {
            recordingSessionUtcByDevice.remove(it)
            recordingAnchors.remove(it)
        }
        val rollbackTargets = targets.filter { norm ->
            managers[norm]?.mDevice?.connectionState == DotDevice.CONN_STATE_CONNECTED
        }.toSet()
        if (rollbackTargets.isNotEmpty()) {
            appendLog("开始录制未全部确认，正在回滚")
            pendingStopAcks.clear()
            pendingStopAcks.addAll(rollbackTargets)
            stopAckTargets = rollbackTargets
            setDeviceRecordingPhase(rollbackTargets, FlashRecordingPhase.Stopping)
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

    @Synchronized
    private fun beginReliableStop(
        requestedTargets: Set<String>,
        force: Boolean = false,
    ) {
        val normalizedTargets = requestedTargets.map(::normalizeAddress).toSet()
        val targets = if (force) {
            normalizedTargets.filter { it in managers.keys }.toSet()
        } else {
            normalizedTargets.filter { target ->
                target in activeRecordingDevices ||
                    _deviceRecordingPhases.value[target] in setOf(
                        FlashRecordingPhase.Recording,
                        FlashRecordingPhase.Stopping,
                    )
            }.toSet()
        }
        if (targets.isEmpty()) {
            appendLog("该设备组当前没有正在录制的设备")
            return
        }

        val sessionKey = recordingSessionKey(targets)
        val fullSessionTargets = activeRecordingSessions[sessionKey]?.targets ?: targets
        reliableStopGroups[sessionKey] = fullSessionTargets
        reliableStopTargets.addAll(targets)
        val now = SystemClock.elapsedRealtime()
        targets.forEach { stopRequestedAt.putIfAbsent(it, now) }
        _delayedStopConfirmations.value -= targets
        setDeviceRecordingPhase(targets, FlashRecordingPhase.Stopping)
        appendLog(
            "停止请求已接管：${targets.size} 台设备；弱信号或断联时将自动等待回连并继续"
        )
        scheduleReliableStopAttempt(0L)
    }

    private fun scheduleReliableStopAttempt(delayMs: Long = STOP_RETRY_TICK_MS) {
        mainHandler.removeCallbacks(reliableStopRunnable)
        mainHandler.postDelayed(reliableStopRunnable, delayMs)
    }

    @Synchronized
    private fun attemptReliableStops() {
        val now = SystemClock.elapsedRealtime()
        reliableStopTargets.toSet().forEach { norm ->
            val manager = managers[norm] ?: return@forEach
            val requestedAt = stopRequestedAt[norm] ?: now.also {
                stopRequestedAt[norm] = it
            }
            if (
                now - requestedAt >= STOP_CONFIRMATION_DELAY_WARNING_MS &&
                norm !in _delayedStopConfirmations.value
            ) {
                _delayedStopConfirmations.value += norm
                appendLog(
                    "[${manager.mDevice?.address ?: norm}] 停止超过 20 秒仍未确认，" +
                        "将降低轮询频率并继续自动重试"
                )
            }
            val connected =
                manager.mDevice?.connectionState == DotDevice.CONN_STATE_CONNECTED
            if (!connected) return@forEach

            val sentAt = stopCommandSentAt[norm] ?: 0L
            if (norm in pendingStopAcks) {
                if (now - sentAt < STOP_ACK_WAIT_MS) return@forEach
                pendingStopAcks.remove(norm)
                val stateRequested = manager.requestRecordingState()
                stopStateCheckAt[norm] = now
                appendLog(
                    "[${manager.mDevice?.address ?: norm}] 停止 ACK 未返回，" +
                        if (stateRequested) "正在复查设备状态" else "状态查询忙，稍后重试"
                )
                return@forEach
            }

            val checkedAt = stopStateCheckAt[norm] ?: 0L
            if (now - checkedAt < STOP_STATE_SETTLE_MS) return@forEach

            pendingStopAcks.add(norm)
            stopCommandSentAt[norm] = now
            val accepted = manager.stopRecording()
            appendLog(
                "[${manager.mDevice?.address ?: norm}] 已发送停止录制，等待 ACK" +
                    if (accepted) "" else "（SDK 队列繁忙，将自动复查并重试）"
            )
        }
    }

    @Synchronized
    private fun confirmReliableStop(
        norm: String,
        rawAddress: String,
        source: String,
    ) {
        if (!reliableStopTargets.remove(norm)) return
        pendingStopAcks.remove(norm)
        stopRequestedAt.remove(norm)
        stopCommandSentAt.remove(norm)
        stopStateCheckAt.remove(norm)
        _delayedStopConfirmations.value -= norm
        latestRecordingStates[norm] = DotRecordingState.idle
        _recordingStates.value = _recordingStates.value.toMutableMap().also {
            it[norm] = DotRecordingState.idle
        }
        activeRecordingDevices.remove(norm)
        setDeviceRecordingPhase(setOf(norm), FlashRecordingPhase.Idle)
        updateRecordingActive()
        appendLog("[$rawAddress] 已确认停止（$source）")
        finishReliableStopGroups()
        if (reliableStopTargets.isEmpty()) {
            mainHandler.removeCallbacks(reliableStopRunnable)
        } else {
            scheduleReliableStopAttempt(0L)
        }
    }

    @Synchronized
    private fun finishReliableStopGroups() {
        val completed = reliableStopGroups.entries.filter { (_, targets) ->
            targets.none { it in reliableStopTargets }
        }
        completed.forEach { (sessionKey, requestedTargets) ->
            if (!reliableStopGroups.remove(sessionKey, requestedTargets)) return@forEach
            val activeSession = activeRecordingSessions.remove(sessionKey)
            val stoppedTargets = activeSession?.targets ?: requestedTargets
            val sessionStartUtcMs = activeSession?.startUtcMs
            val baselineKeys = activeSession?.baselineKeys.orEmpty()
            val outputRatesByTarget = activeSession?.outputRatesByTarget
                ?: stoppedTargets.associateWith(::currentOutputRate)
            val baselineKnown = stoppedTargets.all { it in _fileList.value.keys }
            val stopUtcMs = System.currentTimeMillis()
            clearFlashUsageMonitoring(stoppedTargets)
            setDeviceRecordingPhase(stoppedTargets, FlashRecordingPhase.Idle)
            updateRecordingActive()
            appendLog("该运动员左右脚停止 ACK 已全部确认")
            if (stoppedTargets.isNotEmpty() && sessionStartUtcMs != null) {
                enqueueAutoExportSession(
                    CompletedRecordingSession(
                        sessionKey = sessionKey,
                        targets = stoppedTargets,
                        startUtcMs = sessionStartUtcMs,
                        stopUtcMs = stopUtcMs,
                        outputRatesByTarget = outputRatesByTarget,
                        baselineKeys = baselineKeys,
                        baselineKnown = baselineKnown,
                    )
                )
            } else {
                refreshFlashAfterStop(stoppedTargets)
            }
        }
    }

    @Synchronized
    private fun finishStopAcksIfComplete() {
        if (pendingStopAcks.isNotEmpty()) return
        val requestedTargets = stopAckTargets
        if (requestedTargets.isEmpty()) return
        val stillRecordingTargets = requestedTargets.filter {
            it in activeRecordingDevices
        }.toSet()
        if (stillRecordingTargets.isNotEmpty()) {
            stopAckTargets = emptySet()
            setDeviceRecordingPhase(stillRecordingTargets, FlashRecordingPhase.Recording)
            startFlashUsageMonitoring(stillRecordingTargets)
            updateRecordingActive()
            appendLog(
                "部分设备未确认停止，仍保持录制状态：${stillRecordingTargets.joinToString()}"
            )
            return
        }
        val sessionKey = recordingSessionKey(requestedTargets)
        val activeSession = activeRecordingSessions.remove(sessionKey)
        val stoppedTargets = activeSession?.targets ?: requestedTargets
        val sessionStartUtcMs = activeSession?.startUtcMs
        val baselineKeys = activeSession?.baselineKeys.orEmpty()
        val outputRatesByTarget = activeSession?.outputRatesByTarget
            ?: stoppedTargets.associateWith(::currentOutputRate)
        val baselineKnown = stoppedTargets.all { it in _fileList.value.keys }
        val stopUtcMs = System.currentTimeMillis()
        stopAckTargets = emptySet()
        clearFlashUsageMonitoring(stoppedTargets)
        setDeviceRecordingPhase(stoppedTargets, FlashRecordingPhase.Idle)
        updateRecordingActive()
        appendLog("停止录制 ACK 已全部返回")
        if (stoppedTargets.isNotEmpty() && sessionStartUtcMs != null) {
            val session = CompletedRecordingSession(
                sessionKey = sessionKey,
                targets = stoppedTargets,
                startUtcMs = sessionStartUtcMs,
                stopUtcMs = stopUtcMs,
                outputRatesByTarget = outputRatesByTarget,
                baselineKeys = baselineKeys,
                baselineKnown = baselineKnown,
            )
            enqueueAutoExportSession(session)
        } else {
            refreshFlashAfterStop(stoppedTargets)
        }
    }

    private fun refreshFlashAfterStop(targets: Set<String>) {
        mainHandler.postDelayed({
            targets.forEach { managers[it]?.requestFlashInfo() }
        }, 700L)
    }

    private fun enqueueAutoExportSession(session: CompletedRecordingSession) {
        val decision = RecordingExportDecision(
            id = session.exportDecisionId,
            sessionKey = session.sessionKey,
            targetAddresses = session.targets,
        )
        _recordingExportDecisions.value = _recordingExportDecisions.value.toMutableMap().also {
            it[decision.id] = decision
        }
        queuedAutoExportSessions.addLast(session)
        startNextAutoExportSession()
    }

    private fun startNextAutoExportSession() {
        if (!canStartNextRecordingExportPreparation(
                hasActivePreparation = autoExportAfterStop != null,
                hasExportTransfer = _exportTaskProgress.value.hasPendingFiles,
                queuedSessionCount = queuedAutoExportSessions.size,
            )
        ) return
        var session: CompletedRecordingSession? = null
        while (queuedAutoExportSessions.isNotEmpty() && session == null) {
            val candidate = queuedAutoExportSessions.removeFirst()
            if (_recordingExportDecisions.value[candidate.exportDecisionId]?.isPreparing == true) {
                session = candidate
            }
        }
        val nextSession = session ?: return
        autoExportAfterStop = nextSession
        mainHandler.postDelayed({
            if (autoExportAfterStop === nextSession) {
                requestAutoExportFileInfo(nextSession)
            }
        }, 1_200L)
    }

    private fun updateRecordingExportDecision(
        id: String,
        transform: (RecordingExportDecision) -> RecordingExportDecision,
    ) {
        _recordingExportDecisions.value = _recordingExportDecisions.value.toMutableMap().also {
            val current = it[id] ?: return
            it[id] = transform(current)
        }
    }

    private fun requestAutoExportFileInfo(session: CompletedRecordingSession) {
        pendingAutoFileInfoTargets.clear()
        pendingAutoFileInfoTargets.addAll(session.targets)
        pendingAutoFileInfoRequests.clear()
        _fileList.value = _fileList.value.toMutableMap().also { current ->
            session.targets.forEach { current.remove(it) }
        }
        appendLog("正在读取该运动员刚才录制的文件…")
        session.targets.forEach { addr ->
            managers[addr]?.requestFlashInfo()
        }
        mainHandler.postDelayed({
            if (autoExportAfterStop !== session) return@postDelayed
            finishAutoExportFileInfoIfReady(force = true)
        }, fileInfoBatchTimeoutMs(session.targets.size))
    }

    private fun finishAutoExportFileInfoIfReady(force: Boolean = false) {
        val session = autoExportAfterStop ?: return
        val decisionId = session.exportDecisionId
        if (!force && pendingAutoFileInfoTargets.isNotEmpty()) return
        if (force && pendingAutoFileInfoTargets.isNotEmpty()) {
            val missing = pendingAutoFileInfoTargets.toSet()
            missing.forEach { address ->
                removeOutstandingFileInfoRequest(
                    address,
                    AUTO_FILE_INFO_REQUEST_ID,
                )
            }
            pendingAutoFileInfoTargets.clear()
            pendingAutoFileInfoRequests.clear()
            autoExportAfterStop = null
            updateRecordingExportDecision(decisionId) {
                it.copy(
                    isPreparing = false,
                    errorMessage = "文件确认超时，请保持左右脚设备连接后从文件列表导出",
                )
            }
            appendLog("该运动员部分设备文件列表读取超时：${missing.joinToString()}")
            startNextAutoExportSession()
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
            appendLog("未完整找到该运动员本次录制文件：${missingTargets.joinToString()}")
            autoExportAfterStop = null
            updateRecordingExportDecision(decisionId) {
                it.copy(
                    isPreparing = false,
                    errorMessage = "未完整找到左右脚文件，请从文件列表手动选择",
                )
            }
            startNextAutoExportSession()
            return
        }
        val selectedKeys = selectedKeysByTarget.values.flatten().toSet()

        if (selectedKeys.isEmpty()) {
            appendLog("未找到该运动员刚才录制的文件，请手动读取文件")
            autoExportAfterStop = null
            updateRecordingExportDecision(decisionId) {
                it.copy(
                    isPreparing = false,
                    errorMessage = "未找到本次录制文件，请从文件列表手动选择",
                )
            }
            startNextAutoExportSession()
            return
        }

        autoExportAfterStop = null
        val targetFramesByFile = buildMap {
            selectedKeysByTarget.forEach { (target, keys) ->
                if (keys.size != 1) return@forEach
                val outputRate = session.outputRatesByTarget[target]
                    ?: currentOutputRate(target)
                put(
                    keys.single(),
                    estimatedRecordedSampleCount(
                        startUtcMs = session.startUtcMs,
                        stopUtcMs = session.stopUtcMs,
                        outputRateHz = outputRate,
                    ),
                )
            }
        }
        updateRecordingExportDecision(decisionId) {
            it.copy(
                fileKeys = selectedKeys,
                targetFramesByFile = targetFramesByFile,
                isPreparing = false,
                errorMessage = null,
            )
        }
        appendLog("该运动员本次录制的 ${selectedKeys.size} 个文件已就绪")
        startNextAutoExportSession()
    }

    fun exportRecordingDecision(decisionId: String): Boolean {
        val decision = _recordingExportDecisions.value[decisionId] ?: return false
        if (!decision.isReady || _exportTaskProgress.value.hasPendingFiles) return false
        val accepted = requestStateThenRun(
            command = RecordingCommand.ExportSelected,
            selectedKeys = decision.fileKeys,
            targetFramesByFile = decision.targetFramesByFile,
        )
        if (!accepted) return false
        _recordingExportDecisions.value = _recordingExportDecisions.value - decisionId
        appendLog("导出该运动员本次录制的 ${decision.fileCount} 个文件")
        return true
    }

    fun dismissRecordingExportDecision(decisionId: String) {
        val decision = _recordingExportDecisions.value[decisionId] ?: return
        _recordingExportDecisions.value = _recordingExportDecisions.value - decisionId
        queuedAutoExportSessions.removeAll { it.exportDecisionId == decisionId }
        if (autoExportAfterStop?.exportDecisionId == decisionId) {
            autoExportAfterStop = null
            pendingAutoFileInfoTargets.clear()
            pendingAutoFileInfoRequests.clear()
        }
        appendLog(
            if (decision.errorMessage == null) {
                "该运动员本次录制暂不导出，可从文件列表选择其他数据"
            } else {
                "已关闭该运动员的文件确认提示"
            }
        )
        startNextAutoExportSession()
    }

    private fun scheduleStopAckTimeout(label: String) {
        val timeoutMs = 7_000L + (pendingStopAcks.size - 2).coerceAtLeast(0) * 1_000L
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
                            setDeviceRecordingPhase(unresolved, FlashRecordingPhase.Recording)
                            startFlashUsageMonitoring(unresolved)
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
        }, timeoutMs)
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

    private fun requestStateThenRun(
        command: RecordingCommand,
        selectedKeys: Set<String> = emptySet(),
        targetFramesByFile: Map<String, Int> = emptyMap(),
        explicitTargets: Set<String>? = null,
    ): Boolean {
        if (pendingOperation != null || pendingStartAcks.isNotEmpty() || pendingStopAcks.isNotEmpty()) {
            appendLog("已有录制操作等待 ACK，请稍候")
            return false
        }
        if (managers.isEmpty()) {
            appendLog("没有可操作的设备")
            return false
        }

        val connectedKeys = connectedManagerKeys()
        val targets = explicitTargets
            ?.map(::normalizeAddress)
            ?.toSet()
            ?: when (command) {
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

        val activeFileReads = _fileInfoReadActiveTargets.value
        if (targets.any { it in activeFileReads }) {
            appendLog("目标设备正在读取文件列表，请稍候")
            return false
        }
        val activeExportTargets = _exportTaskProgress.value.targetAddresses
        if (
            _exportTaskProgress.value.hasPendingFiles &&
            targets.any { it in activeExportTargets }
        ) {
            appendLog("目标设备正在导出文件，请稍候")
            return false
        }
        if (
            _eraseTaskProgress.value.isErasing &&
            command != RecordingCommand.Erase
        ) {
            appendLog("Flash 擦除尚未完成，请稍候")
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

        val epoch = ++operationEpoch
        pendingStateChecks.clear()
        pendingOperation = PendingRecordingOperation(
            command = command,
            targets = targets,
            selectedKeys = selectedKeys,
            targetFramesByFile = targetFramesByFile,
            epoch = epoch,
        )

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
                when (op.command) {
                    RecordingCommand.Start ->
                        setDeviceRecordingPhase(op.targets, FlashRecordingPhase.Idle)
                    RecordingCommand.Stop -> {
                        val stillRecording = op.targets.filter {
                            it in activeRecordingDevices
                        }.toSet()
                        setDeviceRecordingPhase(
                            op.targets - stillRecording,
                            FlashRecordingPhase.Idle,
                        )
                        setDeviceRecordingPhase(
                            stillRecording,
                            FlashRecordingPhase.Recording,
                        )
                    }
                    RecordingCommand.RequestFiles ->
                        markFileInfoReadFailed(op.targets, "设备状态查询超时")
                    else -> Unit
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
            when (op.command) {
                RecordingCommand.Start ->
                    setDeviceRecordingPhase(op.targets, FlashRecordingPhase.Idle)
                RecordingCommand.Stop ->
                    setDeviceRecordingPhase(op.targets, FlashRecordingPhase.Recording)
                RecordingCommand.RequestFiles ->
                    markFileInfoReadFailed(op.targets, "设备当前状态不允许读取文件")
                else -> Unit
            }
            return
        }

        when (op.command) {
            RecordingCommand.Start -> {
                // DotRecordingManager reports the state callback before the underlying GATT
                // transaction is fully released. Starting immediately can make one device reject
                // startRecording() as busy, especially in synchronized pairs.
                mainHandler.postDelayed(
                    { doStartRecording(op.targets) },
                    START_AFTER_STATE_QUERY_SETTLE_MS,
                )
            }
            RecordingCommand.Stop -> doStopRecording(op.targets)
            RecordingCommand.RequestFiles -> doRequestFileInfo(op.targets)
            RecordingCommand.Erase -> doEraseAll(op.targets)
            RecordingCommand.ExportAll -> doExportAll(op.targets)
            RecordingCommand.ExportSelected -> doExportSelected(
                checkedTargets = op.targets,
                selectedKeys = op.selectedKeys,
                targetFramesByFile = op.targetFramesByFile,
            )
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
        _notificationReady.value = emptySet()
        _flashInfo.value = emptyMap()
        _fileList.value = emptyMap()
        _fileInfoReadStatuses.value = emptyMap()
        _fileInfoReadActiveTargets.value = emptySet()
        _exportProgress.value = emptyMap()
        _exportDone.value = emptySet()
        _log.value = emptyList()
        _recordingActive.value = false
        _recordingStates.value = emptyMap()
        _deviceRecordingPhases.value = emptyMap()
        recordingAnchors.clear()
        recordingSessionUtcByDevice.clear()
        activeRecordingSessions.clear()
        activeRecordingDevices.clear()
        pendingStartAcks.clear()
        pendingStopAcks.clear()
        reliableStopTargets.clear()
        reliableStopGroups.clear()
        stopRequestedAt.clear()
        stopCommandSentAt.clear()
        stopStateCheckAt.clear()
        _delayedStopConfirmations.value = emptySet()
        mainHandler.removeCallbacks(reliableStopRunnable)
        latestRecordingStates.clear()
        pendingStateChecks.clear()
        clearSerialFileInfoRequests()
        fileInfoCallbackLedger.clear()
        lastNotificationEnableRequestAt.clear()
        startAckTargets = emptySet()
        stopAckTargets = emptySet()
        pendingOperation = null
        queuedAutoExportSessions.clear()
        _recordingStates.value = emptyMap()
        _recordingPhase.value = FlashRecordingPhase.Idle
        ensureSetup(devices)
    }

    fun ensureSetup(devices: List<DotDevice>) {
        devices.forEach { dev ->
            val addr = normalizeAddress(dev.address ?: return@forEach)
            val existing = managers[addr]
            val manager = if (existing == null || existing.mDevice !== dev) {
                existing?.clear()
                _notificationReady.value = _notificationReady.value - addr
                _flashInfo.value = _flashInfo.value - addr
                _recordingStates.value = _recordingStates.value - addr
                latestRecordingStates.remove(addr)
                DotRecordingManager(context, dev, this).also { managers[addr] = it }
            } else {
                existing
            }

            if (addr !in _deviceRecordingPhases.value) {
                _deviceRecordingPhases.value = _deviceRecordingPhases.value.toMutableMap().also {
                    it[addr] = FlashRecordingPhase.Idle
                }
            }
            val deferNotification = shouldDeferRecordingNotification(
                phase = _deviceRecordingPhases.value[addr],
                isKnownActiveRecording = addr in activeRecordingDevices,
            )
            if (
                addr !in _notificationReady.value &&
                !deferNotification &&
                manager.enableDataRecordingNotification()
            ) {
                lastNotificationEnableRequestAt[addr] = System.currentTimeMillis()
                appendLog("[${dev.address}] 正在启用录制通知…")
            } else if (addr !in _notificationReady.value && deferNotification) {
                appendLog("[${dev.address}] 录制中回连，先确认录制状态，不重写录制通知")
            } else if (addr !in _notificationReady.value) {
                appendLog("[${dev.address}] 发送启用录制通知失败")
            }
            scheduleRecordingStateRecovery(addr, manager)
            mainHandler.postDelayed({
                if (managers[addr] === manager) {
                    manager.requestFlashInfo()
                }
            }, 1_500L)
            mainHandler.postDelayed({
                if (managers[addr] === manager && addr !in latestRecordingStates.keys) {
                    appendLog("[${dev.address}] 录制状态无返回，可先尝试强制停止")
                }
            }, 3_000L)
        }
        totalDevices = managers.size
    }

    /**
     * 同步前释放当前分组的 RecordingManager。
     *
     * DOT 的录制通知、状态查询、Flash 查询与 DotSyncManager 共用设备 GATT 队列。
     * 同步开始前必须让当前两台设备只由 DotSyncManager 操作，否则 root 命令或
     * 回连后的同步 ACK 会被录制命令抢占。其他运动员的 manager 和录制状态保持不变。
     *
     * @return GATT 排空等待时间；-1 表示当前分组仍有录制操作，不能同步。
     */
    fun releaseDevicesForSync(targetAddresses: Set<String>): Long {
        val targets = targetAddresses.map(::normalizeAddress).filter(String::isNotBlank).toSet()
        if (targets.isEmpty()) return 0L

        val activeTargets = targets.filter { target ->
            target in activeRecordingDevices ||
                _deviceRecordingPhases.value[target] !in setOf(
                    null,
                    FlashRecordingPhase.Idle,
                )
        }
        val operation = pendingOperation
        if (
            activeTargets.isNotEmpty() ||
            operation?.targets?.any { it in targets } == true
        ) {
            appendLog("当前分组仍有录制操作，不能开始同步")
            return -1L
        }

        var releasedCount = 0
        targets.forEach { target ->
            managers.remove(target)?.let { manager ->
                releasedCount++
                manager.clear()
            }
        }
        if (releasedCount == 0) return 0L

        _notificationReady.value -= targets
        _flashInfo.value = _flashInfo.value - targets
        _recordingStates.value = _recordingStates.value - targets
        _deviceRecordingPhases.value = _deviceRecordingPhases.value - targets
        latestRecordingStates.keys.removeAll(targets)
        pendingStateChecks.keys.removeAll(targets)
        lastNotificationEnableRequestAt.keys.removeAll(targets)
        recordingFlashBaselines.keys.removeAll(targets)
        recordingStartedAtElapsedMs.keys.removeAll(targets)
        totalDevices = managers.size
        updateAggregateRecordingPhase()
        appendLog("同步前已暂停当前分组录制通道，等待蓝牙命令完成")
        return SYNC_GATT_QUIET_MS
    }

    fun requestFlashInfo() {
        managers.values.forEach { it.requestFlashInfo() }
    }

    fun refreshSetupState(targetAddresses: Set<String> = managers.keys) {
        val targets = targetAddresses.map(::normalizeAddress).toSet()
        managers.forEach { (norm, mgr) ->
            if (norm !in targets) return@forEach
            val rawAddr = mgr.mDevice?.address ?: norm
            val now = System.currentTimeMillis()
            val lastRequestAt = lastNotificationEnableRequestAt[norm] ?: 0L
            if (norm !in _notificationReady.value && now - lastRequestAt >= 2_000L) {
                if (mgr.enableDataRecordingNotification()) {
                    lastNotificationEnableRequestAt[norm] = now
                    appendLog("[$rawAddr] 补发录制通知启用…")
                }
            }
            scheduleRecordingStateRecovery(norm, mgr)
            mainHandler.postDelayed({
                if (managers[norm] === mgr) {
                    mgr.requestFlashInfo()
                }
            }, 700L)
        }
    }

    /**
     * 设备断线重连后重新启用录制通知，使后续的 stopRecording/requestFileInfo 能正常工作。
     * address 为 BLE 地址（含冒号格式或规范化格式均可）。
     */
    fun reenableNotification(address: String) {
        val norm = normalizeAddress(address)
        val mgr  = managers[norm] ?: return
        if (
            shouldDeferRecordingNotification(
                phase = _deviceRecordingPhases.value[norm],
                isKnownActiveRecording = norm in activeRecordingDevices,
            )
        ) {
            Log.i(
                LINK_DIAG_TAG,
                "[${norm.takeLast(4)}] recording-notification deferred during active recording"
            )
            appendLog("[$address] 已回连，先确认录制状态")
            scheduleRecordingStateRecovery(norm, mgr)
            if (norm in reliableStopTargets) {
                scheduleReliableStopAttempt(STOP_STATE_SETTLE_MS)
            }
            return
        }
        val now = System.currentTimeMillis()
        val lastRequestAt = lastNotificationEnableRequestAt[norm] ?: 0L
        if (now - lastRequestAt < 5_000L) {
            Log.i(LINK_DIAG_TAG, "[${norm.takeLast(4)}] recording-notification skipped; requestStateOnly")
            scheduleRecordingStateRecovery(norm, mgr)
            if (norm in reliableStopTargets) {
                appendLog("[$address] 已回连，继续确认停止录制")
                scheduleReliableStopAttempt(STOP_STATE_SETTLE_MS)
            }
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
        scheduleRecordingStateRecovery(norm, mgr)
        if (norm in reliableStopTargets) {
            appendLog("[$address] 已回连，继续确认停止录制")
            scheduleReliableStopAttempt(STOP_STATE_SETTLE_MS)
        }
    }

    private fun scheduleRecordingStateRecovery(
        norm: String,
        manager: DotRecordingManager,
        attempt: Int = 0,
    ) {
        val delayMs = if (attempt == 0) 250L else 850L
        mainHandler.postDelayed({
            if (
                managers[norm] !== manager ||
                latestRecordingStates.containsKey(norm)
            ) return@postDelayed
            val sent = manager.requestRecordingState()
            if (sent) {
                appendLog("[${manager.mDevice?.address ?: norm}] 已发送录制状态查询")
            } else {
                appendLog(
                    "[${manager.mDevice?.address ?: norm}] 录制状态查询忙，正在重试 " +
                        "(${attempt + 1}/$RECORDING_STATE_RECOVERY_ATTEMPTS)"
                )
            }
            if (attempt + 1 < RECORDING_STATE_RECOVERY_ATTEMPTS) {
                scheduleRecordingStateRecovery(norm, manager, attempt + 1)
            }
        }, delayMs)
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
        startRecording(managers.keys.toSet())
    }

    fun startRecording(targetAddresses: Set<String>) {
        val targets = targetAddresses.map(::normalizeAddress).toSet()
        if (targets.isEmpty()) return
        val invalid = targets.filter { target ->
            _deviceRecordingPhases.value[target] !in setOf(
                FlashRecordingPhase.Idle,
                FlashRecordingPhase.Starting,
            )
        }
        if (invalid.isNotEmpty()) {
            appendLog("设备组当前不能开始录制：${invalid.joinToString()}")
            return
        }
        setDeviceRecordingPhase(targets, FlashRecordingPhase.Starting)
        if (!requestStateThenRun(RecordingCommand.Start, explicitTargets = targets)) {
            setDeviceRecordingPhase(targets, FlashRecordingPhase.Idle)
            updateRecordingPhaseFromState()
        }
    }

    fun prepareStartRecording(): Boolean {
        return prepareStartRecording(managers.keys.toSet())
    }

    fun prepareStartRecording(targetAddresses: Set<String>): Boolean {
        val targets = targetAddresses.map(::normalizeAddress).toSet()
        if (targets.isEmpty()) return false
        if (
            pendingOperation != null ||
            pendingStartAcks.isNotEmpty() ||
            pendingStopAcks.isNotEmpty()
        ) {
            appendLog("已有录制操作等待确认，请稍候")
            return false
        }
        val invalid = targets.filter {
            _deviceRecordingPhases.value[it] != FlashRecordingPhase.Idle
        }
        if (invalid.isNotEmpty()) {
            appendLog("设备组当前不能开始录制：${invalid.joinToString()}")
            return false
        }
        setDeviceRecordingPhase(targets, FlashRecordingPhase.Starting)
        return true
    }

    private fun doStartRecording(targets: Set<String>) {
        invalidateFileInfoResults(targets, "录制后需重新读取文件列表")
        val sessionKey = recordingSessionKey(targets)
        val baselineKeys = fileKeysForTargets(targets)
        val sessionStartUtcMs = System.currentTimeMillis()
        val outputRatesByTarget = targets.associateWith(::currentOutputRate)
        activeRecordingSessions[sessionKey] = ActiveRecordingSession(
            sessionKey = sessionKey,
            targets = targets,
            startUtcMs = sessionStartUtcMs,
            outputRatesByTarget = outputRatesByTarget,
            baselineKeys = baselineKeys,
        )
        targets.forEach { recordingSessionUtcByDevice[it] = sessionStartUtcMs }
        recordingSessionPreferences.saveSession(
            sessionStartUtcMs,
            LongJumpDeviceRoles.currentConfig,
        )
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
        setDeviceRecordingPhase(targets, FlashRecordingPhase.Starting)
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
        val startAckTimeoutMs =
            6_000L + (startAckTargets.size - 2).coerceAtLeast(0) * 1_000L
        mainHandler.postDelayed({
            if (pendingStartAcks.isNotEmpty()) {
                val timedOut = pendingStartAcks.toSet()
                pendingStartAcks.removeAll(timedOut)
                timedOut.forEach { activeRecordingDevices.remove(it) }
                updateRecordingActive()
                appendLog("开始录制 ACK 超时：${timedOut.joinToString()}")
                finishStartAcksIfComplete()
            }
        }, startAckTimeoutMs)
    }

    fun stopRecording() {
        stopRecording(activeRecordingDevices.toSet())
    }

    fun stopRecording(targetAddresses: Set<String>) {
        val targets = targetAddresses
            .map(::normalizeAddress)
            .filter {
                it in activeRecordingDevices ||
                    _deviceRecordingPhases.value[it] in setOf(
                        FlashRecordingPhase.Recording,
                        FlashRecordingPhase.Stopping,
                    )
            }
            .toSet()
        beginReliableStop(targets)
    }

    fun forceStopRecording() {
        forceStopRecording(connectedManagerKeys().ifEmpty { managers.keys })
    }

    fun forceStopRecording(targetAddresses: Set<String>) {
        if (managers.isEmpty()) {
            appendLog("没有可操作的设备")
            return
        }
        val targets = targetAddresses.map(::normalizeAddress).toSet()
        if (targets.isEmpty()) {
            appendLog("没有已连接设备可停止录制")
            return
        }
        beginReliableStop(targets, force = true)
    }

    private fun doForceStopRecording(targets: Set<String>) {
        if (targets.none {
                _deviceRecordingPhases.value[it] == FlashRecordingPhase.Stopping
            }
        ) return
        pendingOperation = null
        pendingStateChecks.clear()
        pendingStopAcks.clear()
        pendingStopAcks.addAll(targets)
        stopAckTargets = targets
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
                        setDeviceRecordingPhase(stillRecording, FlashRecordingPhase.Recording)
                        startFlashUsageMonitoring(stillRecording)
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
        setDeviceRecordingPhase(targets, FlashRecordingPhase.Stopping)
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
        requestFileInfo(connectedManagerKeys())
    }

    fun requestFileInfo(targetAddresses: Set<String>): Boolean {
        val targets = targetAddresses.map(::normalizeAddress).toSet()
        if (targets.isEmpty()) return false
        if (targets.any(::hasOutstandingFileInfoRequest)) {
            appendLog("正在等待上一轮文件列表回调释放，请稍候")
            return false
        }
        val accepted = requestStateThenRun(
            command = RecordingCommand.RequestFiles,
            explicitTargets = targets,
        )
        if (!accepted) return false

        val epoch = ++fileInfoReadEpoch
        val startedAt = SystemClock.elapsedRealtime()
        targets.forEach { address -> fileInfoReadEpochs[address] = epoch }
        _fileInfoReadActiveTargets.value += targets
        _fileInfoReadStatuses.value = _fileInfoReadStatuses.value.toMutableMap().also { statuses ->
            targets.forEach { address ->
                statuses[address] = FileInfoReadStatus(
                    phase = FileInfoReadPhase.Reading,
                    requestId = epoch,
                    startedAtElapsedMs = startedAt,
                )
            }
        }
        mainHandler.postDelayed({
            val timedOut = targets.filter { address ->
                fileInfoReadEpochs[address] == epoch &&
                    _fileInfoReadStatuses.value[address]?.phase == FileInfoReadPhase.Reading
            }.toSet()
            if (timedOut.isNotEmpty()) {
                markFileInfoReadFailed(
                    targets = timedOut,
                    message = "读取文件列表超时",
                    requestId = epoch,
                )
                appendLog("文件列表读取超时：${timedOut.joinToString()}")
                mainHandler.postDelayed({
                    timedOut.forEach { address ->
                        removeOutstandingFileInfoRequest(address, epoch)
                    }
                }, FILE_INFO_CALLBACK_DRAIN_MS)
            }
        }, fileInfoBatchTimeoutMs(targets.size))
        return true
    }

    fun expireFileInfoRead(
        targets: Set<String>,
        message: String,
    ) {
        markFileInfoReadFailed(
            targets = targets.map(::normalizeAddress).toSet(),
            message = message,
        )
    }

    private fun markFileInfoReadFailed(
        targets: Set<String>,
        message: String,
        requestId: Int? = null,
    ) {
        val completedAt = SystemClock.elapsedRealtime()
        val failedTargets = mutableSetOf<String>()
        _fileInfoReadStatuses.value = _fileInfoReadStatuses.value.toMutableMap().also { statuses ->
            targets.forEach { address ->
                val current = statuses[address]
                if (
                    requestId != null &&
                    current?.requestId != requestId
                ) {
                    return@forEach
                }
                statuses[address] = FileInfoReadStatus(
                    phase = FileInfoReadPhase.Failed,
                    message = message,
                    requestId = current?.requestId ?: requestId ?: 0,
                    startedAtElapsedMs = current?.startedAtElapsedMs,
                    completedAtElapsedMs = completedAt,
                )
                failedTargets += address
            }
        }
        _fileInfoReadActiveTargets.value -= failedTargets
    }

    private fun doRequestFileInfo(targets: Set<String>) {
        val queuedTargets = mutableListOf<String>()
        targets.sorted().forEach { addr ->
            val requestId = _fileInfoReadStatuses.value[addr]?.requestId ?: return@forEach
            val manager = managers[addr]
            if (manager == null) {
                markFileInfoReadFailed(
                    targets = setOf(addr),
                    message = "设备文件管理器不存在",
                    requestId = requestId,
                )
                return@forEach
            }
            queuedTargets += addr
            enqueueSerialFileInfoRequest(addr, requestId)
        }
        appendLog("正在依次读取 ${queuedTargets.size} 台设备的文件列表…")
    }

    private fun fileInfoBatchTimeoutMs(targetCount: Int): Long =
        FILE_INFO_SINGLE_REQUEST_TIMEOUT_MS * targetCount.coerceAtLeast(1) +
            FILE_INFO_NEXT_DEVICE_DELAY_MS * (targetCount - 1).coerceAtLeast(0) +
            FILE_INFO_BATCH_TIMEOUT_PADDING_MS

    private fun enqueueSerialFileInfoRequest(
        address: String,
        requestId: Int,
    ) {
        val request = SerialFileInfoRequest(
            address = normalizeAddress(address),
            requestId = requestId,
        )
        val shouldStart = synchronized(serialFileInfoLock) {
            val duplicate =
                activeSerialFileInfoRequest == request ||
                    serialFileInfoQueue.any { it == request }
            if (!duplicate) serialFileInfoQueue.addLast(request)
            activeSerialFileInfoRequest == null
        }
        if (shouldStart) startNextSerialFileInfoRequest()
    }

    private fun isSerialFileInfoRequestStillNeeded(
        request: SerialFileInfoRequest,
    ): Boolean =
        if (request.requestId == AUTO_FILE_INFO_REQUEST_ID) {
            autoExportAfterStop != null &&
                request.address in pendingAutoFileInfoTargets
        } else {
            request.address in _fileInfoReadActiveTargets.value &&
                _fileInfoReadStatuses.value[request.address]?.let { status ->
                    status.phase == FileInfoReadPhase.Reading &&
                        status.requestId == request.requestId
                } == true
        }

    private fun startNextSerialFileInfoRequest() {
        var next: SerialFileInfoRequest? = null
        synchronized(serialFileInfoLock) {
            if (activeSerialFileInfoRequest != null) return
            while (serialFileInfoQueue.isNotEmpty() && next == null) {
                val candidate = serialFileInfoQueue.removeFirst()
                if (isSerialFileInfoRequestStillNeeded(candidate)) {
                    activeSerialFileInfoRequest = candidate
                    next = candidate
                }
            }
        }
        val request = next ?: return
        val manager = managers[request.address]
        if (manager == null) {
            failSerialFileInfoRequest(request, "设备文件管理器不存在")
            releaseSerialFileInfoRequest(request)
            return
        }

        enqueueOutstandingFileInfoRequest(request.address, request.requestId)
        appendLog("[${manager.mDevice?.address ?: request.address}] 正在读取文件信息")
        runCatching {
            manager.requestFileInfo()
        }.onFailure { error ->
            removeOutstandingFileInfoRequest(request.address, request.requestId)
            failSerialFileInfoRequest(
                request,
                "启动文件列表读取失败：${error.message ?: "未知错误"}",
            )
            releaseSerialFileInfoRequest(request)
            return
        }
        mainHandler.postDelayed({
            val timedOut = synchronized(serialFileInfoLock) {
                if (activeSerialFileInfoRequest == request) {
                    activeSerialFileInfoRequest = null
                    true
                } else {
                    false
                }
            }
            if (!timedOut) return@postDelayed
            removeOutstandingFileInfoRequest(request.address, request.requestId)
            failSerialFileInfoRequest(request, "读取文件列表超时")
            mainHandler.postDelayed(
                ::startNextSerialFileInfoRequest,
                FILE_INFO_CALLBACK_DRAIN_MS,
            )
        }, FILE_INFO_SINGLE_REQUEST_TIMEOUT_MS)
    }

    private fun failSerialFileInfoRequest(
        request: SerialFileInfoRequest,
        message: String,
    ) {
        if (request.requestId == AUTO_FILE_INFO_REQUEST_ID) {
            pendingAutoFileInfoTargets.remove(request.address)
            pendingAutoFileInfoRequests.remove(request.address)
            appendLog("[${request.address}] $message")
            finishAutoExportFileInfoIfReady()
        } else {
            markFileInfoReadFailed(
                targets = setOf(request.address),
                message = message,
                requestId = request.requestId,
            )
        }
    }

    private fun releaseSerialFileInfoRequest(
        request: SerialFileInfoRequest,
    ) {
        val released = synchronized(serialFileInfoLock) {
            if (activeSerialFileInfoRequest == request) {
                activeSerialFileInfoRequest = null
                true
            } else {
                false
            }
        }
        if (released) {
            mainHandler.postDelayed(
                ::startNextSerialFileInfoRequest,
                FILE_INFO_NEXT_DEVICE_DELAY_MS,
            )
        }
    }

    private fun clearSerialFileInfoRequests() {
        synchronized(serialFileInfoLock) {
            serialFileInfoQueue.clear()
            activeSerialFileInfoRequest = null
        }
    }

    private fun hasOutstandingFileInfoRequest(address: String): Boolean {
        return fileInfoCallbackLedger.hasOutstanding(address)
    }

    private fun enqueueOutstandingFileInfoRequest(
        address: String,
        requestId: Int,
    ) {
        fileInfoCallbackLedger.enqueue(address, requestId)
    }

    private fun popOutstandingFileInfoRequest(address: String): Int? {
        return fileInfoCallbackLedger.pop(address)
    }

    private fun removeOutstandingFileInfoRequest(
        address: String,
        requestId: Int,
    ) {
        fileInfoCallbackLedger.remove(address, requestId)
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
                    exportFileKey(addr, file) to estimatedExportFrameCount(file)
                }
            }
            .toMap()
        resetExportTracking(exportTargets.toSet(), targetFiles, targetFrames)
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

    private fun doExportSelected(
        checkedTargets: Set<String>,
        selectedKeys: Set<String>,
        targetFramesByFile: Map<String, Int> = emptyMap(),
    ) {
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
                        val key = exportFileKey(addr, file)
                        key to (
                            targetFramesByFile[key]?.takeIf { it > 0 }
                                ?: estimatedExportFrameCount(file)
                            )
                    }
            }
            .toMap()
        resetExportTracking(targets.toSet(), targetFiles, targetFrames)
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
        exportRestartScheduled.clear()
        exportDeferredRetryReasons.clear()
        exportLastProgressAt.clear()
        exportRetryCounts.clear()
        exportRestartSequence.set(0L)
        clearExportRuntimeTracking()
        exportStopRequested = false
        if (requests.isEmpty()) {
            val task = _exportTaskProgress.value
            _exportTaskProgress.value = task.copy(
                isExporting = false,
                failedFileKeys = task.targetFileKeys,
            )
            appendLog("没有可启动的设备导出任务")
            return
        }
        requests.forEachIndexed { index, request ->
            val norm = normalizeAddress(request.address)
            exportRequests[norm] = request.copy(address = norm)
            exportLastProgressAt[norm] = SystemClock.elapsedRealtime()
            setExportLinkPriority(norm, highPriority = true)
            managers[norm]?.stopExporting()
            scheduleDeviceExportStart(norm, preferredOrder = index)
        }
        mainHandler.postDelayed(exportWatchdogRunnable, EXPORT_WATCHDOG_INTERVAL_MS)
    }

    private fun scheduleDeviceExportStart(address: String, preferredOrder: Int? = null) {
        val norm = normalizeAddress(address)
        if (!exportRequests.containsKey(norm) || !exportRestartScheduled.add(norm)) return
        val order = preferredOrder
            ?: (exportRestartSequence.getAndIncrement() % 6L).toInt()
        val delayMs = EXPORT_RESTART_BASE_DELAY_MS + order * EXPORT_RESTART_STAGGER_MS
        mainHandler.postDelayed(
            {
                exportRestartScheduled.remove(norm)
                startDeviceExport(norm)
            },
            delayMs,
        )
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
        if (manager.mDevice?.connectionState != DotDevice.CONN_STATE_CONNECTED) {
            exportLastProgressAt[norm] = SystemClock.elapsedRealtime()
            scheduleDeviceExportStart(norm)
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
        exportLastProgressAt[norm] = SystemClock.elapsedRealtime()
        val task = _exportTaskProgress.value
        _exportTaskProgress.value = task.copy(isExporting = true)
    }

    private fun failDeviceExport(address: String, reason: String) {
        val norm = normalizeAddress(address)
        synchronized(exportProgressPublishLock) {
            val task = _exportTaskProgress.value
            val deviceFileKeys = task.targetFileKeys
                .filter { it.startsWith("$norm-") }
                .toSet()
            val failed = task.failedFileKeys + deviceFileKeys
            val updated = task.copy(
                activeFileKeys = task.activeFileKeys - deviceFileKeys,
                failedFileKeys = failed,
            )
            _exportTaskProgress.value = updated.copy(isExporting = updated.hasPendingFiles)
        }
        exportStartedDevices.remove(norm)
        exportDevicesWithData.remove(norm)
        exportRestartScheduled.remove(norm)
        exportDeferredRetryReasons.remove(norm)
        exportLastProgressAt.remove(norm)
        exportRetryCounts.remove(norm)
        exportRequests.remove(norm)
        setExportLinkPriority(norm, highPriority = false)
        closeDeviceExportWriters(norm, deletePartial = true)
        resetDeviceExportRuntime(norm, resetPublishedProgress = false)
        appendLog("[${managers[norm]?.mDevice?.address ?: norm}] $reason")
        Log.e(TAG, "[$norm] $reason")
        startDeferredRetryIfPossible()
        finishExportSessionIfDone()
    }

    private fun retryDeviceExport(address: String, reason: String) {
        val norm = normalizeAddress(address)
        if (exportStopRequested || !exportRequests.containsKey(norm)) return
        if (exportDeferredRetryReasons.containsKey(norm) || norm in exportRestartScheduled) return
        val manager = managers[norm]
        if (manager?.mDevice?.connectionState != DotDevice.CONN_STATE_CONNECTED) {
            exportLastProgressAt[norm] = SystemClock.elapsedRealtime()
            return
        }
        val retries = exportRetryCounts.getOrDefault(norm, 0)
        if (retries >= EXPORT_MAX_RETRIES) {
            failDeviceExport(norm, "$reason，重试 $retries 次后仍失败")
            return
        }
        exportRetryCounts[norm] = retries + 1
        exportStartedDevices.remove(norm)
        exportDevicesWithData.remove(norm)
        exportLastProgressAt[norm] = SystemClock.elapsedRealtime()
        closeDeviceExportWriters(norm, deletePartial = true)
        resetDeviceExportRuntime(norm, resetPublishedProgress = true)
        appendLog("[${managers[norm]?.mDevice?.address ?: norm}] $reason，正在重试 ${retries + 1}/$EXPORT_MAX_RETRIES")
        Log.w(TAG, "[$norm] retry export ${retries + 1}/$EXPORT_MAX_RETRIES: $reason")
        setExportLinkPriority(norm, highPriority = true)
        val hasOtherActiveExport = exportStartedDevices.any { other ->
            other != norm &&
                exportRequests.containsKey(other) &&
                !exportDeferredRetryReasons.containsKey(other) &&
                other !in exportRestartScheduled
        }
        managers[norm]?.stopExporting()
        if (hasOtherActiveExport) {
            exportDeferredRetryReasons[norm] = reason
            appendLog(
                "[${managers[norm]?.mDevice?.address ?: norm}] 暂停该设备，" +
                    "等待另一台完成后单独恢复"
            )
        } else {
            scheduleDeviceExportStart(norm)
        }
    }

    private fun startDeferredRetryIfPossible() {
        if (exportStopRequested || exportDeferredRetryReasons.isEmpty()) return
        val hasRunningTransfer =
            exportStartedDevices.any { address ->
                exportRequests.containsKey(address) &&
                    !exportDeferredRetryReasons.containsKey(address)
            } ||
                exportRestartScheduled.any { address ->
                    exportRequests.containsKey(address) &&
                        !exportDeferredRetryReasons.containsKey(address)
                }
        if (hasRunningTransfer) return
        val next = exportDeferredRetryReasons.keys
            .sortedBy { exportRetryCounts[it] ?: 0 }
            .firstOrNull { address ->
                address !in exportRestartScheduled &&
                    exportRequests.containsKey(address)
            }
            ?: return
        exportDeferredRetryReasons.remove(next)
        appendLog("[${managers[next]?.mDevice?.address ?: next}] 开始单独恢复导出")
        scheduleDeviceExportStart(next)
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
            val sessionDone = !task.hasPendingFiles && exportRequests.isEmpty()
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
            startNextAutoExportSession()
        }
    }

    private fun finishDeviceExport(address: String, rawAddress: String) {
        val norm = normalizeAddress(address)
        if (!exportRequests.containsKey(norm) && norm !in exportStartedDevices) return
        closeDeviceExportWriters(norm, deletePartial = false)
        synchronized(exportProgressPublishLock) {
            _exportDone.value = _exportDone.value + norm
        }
        exportStartedDevices.remove(norm)
        exportDevicesWithData.remove(norm)
        exportRestartScheduled.remove(norm)
        exportDeferredRetryReasons.remove(norm)
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
        appendLog("[$rawAddress] 全部文件导出完成 ✓")
        Log.i(TAG, "[$rawAddress] export done")
        startDeferredRetryIfPossible()
        finishExportSessionIfDone()
    }

    private fun finishDeviceExportIfFilesComplete(address: String, rawAddress: String) {
        val norm = normalizeAddress(address)
        val task = _exportTaskProgress.value
        val deviceFileKeys = task.targetFileKeys
            .filter { it.startsWith("$norm-") }
            .toSet()
        if (
            deviceFileKeys.isNotEmpty() &&
            deviceFileKeys.all { it in task.finishedFileKeys }
        ) {
            finishDeviceExport(norm, rawAddress)
        }
    }

    fun stopExporting() {
        mainHandler.removeCallbacks(exportWatchdogRunnable)
        exportStopRequested = true
        exportRequests.clear()
        exportStartedDevices.clear()
        exportDevicesWithData.clear()
        exportRestartScheduled.clear()
        exportDeferredRetryReasons.clear()
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
        val unfinished = task.targetFileKeys - task.finishedFileKeys
        _exportTaskProgress.value = task.copy(
            isExporting = false,
            activeFileKeys = emptySet(),
            failedFileKeys = task.failedFileKeys + unfinished,
        )
        if (unfinished.isNotEmpty()) {
            appendLog("已停止导出，${unfinished.size} 个文件未完成")
        }
        startNextAutoExportSession()
    }

    fun clear() {
        clearFlashUsageMonitoring()
        stopExporting()
        managers.values.forEach { it.clear() }
        managers.clear()
        totalDevices = 0
        activeRecordingDevices.clear()
        pendingStartAcks.clear()
        pendingStopAcks.clear()
        reliableStopTargets.clear()
        reliableStopGroups.clear()
        stopRequestedAt.clear()
        stopCommandSentAt.clear()
        stopStateCheckAt.clear()
        _delayedStopConfirmations.value = emptySet()
        mainHandler.removeCallbacks(reliableStopRunnable)
        latestRecordingStates.clear()
        pendingStateChecks.clear()
        lastNotificationEnableRequestAt.clear()
        recordingFlashBaselines.clear()
        recordingStartedAtElapsedMs.clear()
        pendingAutoFileInfoTargets.clear()
        pendingAutoFileInfoRequests.clear()
        clearSerialFileInfoRequests()
        fileInfoCallbackLedger.clear()
        startAckTargets = emptySet()
        stopAckTargets = emptySet()
        recordingSessionUtcByDevice.clear()
        activeRecordingSessions.clear()
        autoExportAfterStop = null
        queuedAutoExportSessions.clear()
        _recordingExportDecisions.value = emptyMap()
        pendingOperation = null
        val resetAt = SystemClock.elapsedRealtime()
        _fileInfoReadStatuses.value = _fileInfoReadStatuses.value.mapValues { (_, status) ->
            if (status.phase == FileInfoReadPhase.Reading) {
                status.copy(
                    phase = FileInfoReadPhase.Failed,
                    message = "设备引擎已重置",
                    completedAtElapsedMs = resetAt,
                )
            } else {
                status
            }
        }
        _fileInfoReadActiveTargets.value = emptySet()
        _notificationReady.value = emptySet()
        _flashInfo.value = emptyMap()
        _recordingStates.value = emptyMap()
        _exportProgress.value = emptyMap()
        _exportDone.value = emptySet()
        _exportTaskProgress.value = ExportTaskProgress()
        _eraseTaskProgress.value = EraseTaskProgress()
        updateRecordingActive()
        _deviceRecordingPhases.value = emptyMap()
        _recordingPhase.value = FlashRecordingPhase.Idle
    }

    // ── DotRecordingCallback ──

    override fun onDotRecordingNotification(address: String?, isEnabled: Boolean) {
        val addr = address ?: return
        val norm = normalizeAddress(addr)
        if (!managers.containsKey(norm)) return
        if (isEnabled) {
            val wasReady = norm in _notificationReady.value
            _notificationReady.value = _notificationReady.value + norm
            if (!wasReady) {
                appendLog("[$addr] 录制通知已启用")
            }
            managers[norm]?.let { manager ->
                scheduleRecordingStateRecovery(norm, manager)
                mainHandler.postDelayed({
                    if (managers[norm] === manager) {
                        manager.requestFlashInfo()
                    }
                }, 700L)
            }
        } else {
            _notificationReady.value = _notificationReady.value - norm
            appendLog("[$addr] 录制通知未能启用")
        }
    }

    override fun onDotRequestFlashInfoDone(address: String?, usedFlashSpace: Int, totalFlashSpace: Int) {
        val addr = address ?: return
        val norm = normalizeAddress(addr)
        if (!managers.containsKey(norm)) return
        _flashInfo.value = _flashInfo.value.toMutableMap().also {
            it[norm] = Pair(usedFlashSpace, totalFlashSpace)
        }
        appendLog(
            "[$addr] Flash: ${usedFlashSpace / 1024}KB / " +
                "${totalFlashSpace / 1024}KB 已用"
        )
        if (
            norm in pendingAutoFileInfoTargets &&
            autoExportAfterStop != null &&
            pendingAutoFileInfoRequests.add(norm)
        ) {
            mainHandler.postDelayed({
                if (norm in pendingAutoFileInfoTargets && autoExportAfterStop != null) {
                    enqueueSerialFileInfoRequest(
                        norm,
                        AUTO_FILE_INFO_REQUEST_ID,
                    )
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
            val completedAt = SystemClock.elapsedRealtime()
            _fileList.value = _fileList.value.toMutableMap().also { it[norm] = emptyList() }
            _fileInfoReadStatuses.value = _fileInfoReadStatuses.value.toMutableMap().also {
                val current = it[norm]
                it[norm] = FileInfoReadStatus(
                    phase = FileInfoReadPhase.Empty,
                    requestId = current?.requestId ?: 0,
                    startedAtElapsedMs = current?.startedAtElapsedMs,
                    completedAtElapsedMs = completedAt,
                )
            }
            managers[norm]?.requestFlashInfo()
        }
    }

    @Synchronized
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
            !pendingStopAcks.contains(norm) &&
            norm !in reliableStopTargets
        ) {
            return
        }
        appendLog("[$address] $action ${if (isSuccess) "✓" else "✗"}  state=$recordingState")
        val nowUtcMs = System.currentTimeMillis()
        if (recordingId == DotRecordingManager.RECORDING_ID_GET_STATE) {
            if (norm in reliableStopTargets) {
                latestRecordingStates[norm] = recordingState
                _recordingStates.value = _recordingStates.value.toMutableMap().also {
                    it[norm] = recordingState
                }
                pendingStopAcks.remove(norm)
                if (
                    recordingState == DotRecordingState.idle ||
                    recordingState == DotRecordingState.success
                ) {
                    confirmReliableStop(norm, address, "状态复查")
                } else {
                    if (recordingState == DotRecordingState.onRecording) {
                        activeRecordingDevices.add(norm)
                    }
                    setDeviceRecordingPhase(setOf(norm), FlashRecordingPhase.Stopping)
                    updateRecordingActive()
                    scheduleReliableStopAttempt(STOP_STATE_SETTLE_MS)
                }
                return
            }
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
                    setDeviceRecordingPhase(setOf(norm), FlashRecordingPhase.Idle)
                }
                updateRecordingActive()
                if (pendingStartAcks.isEmpty()) {
                    finishStartAcksIfComplete()
                }
            }
            DotRecordingManager.RECORDING_ID_STOP_RECORDING -> {
                if (norm in reliableStopTargets) {
                    pendingStopAcks.remove(norm)
                    if (
                        isSuccess ||
                        recordingState == DotRecordingState.idle ||
                        recordingState == DotRecordingState.success
                    ) {
                        confirmReliableStop(
                            norm,
                            address,
                            if (isSuccess) "停止 ACK" else "ACK 状态",
                        )
                    } else {
                        setDeviceRecordingPhase(setOf(norm), FlashRecordingPhase.Stopping)
                        appendLog("[$address] 停止未确认，将自动复查并重试")
                        scheduleReliableStopAttempt(STOP_STATE_SETTLE_MS)
                    }
                    return
                }
                if (!pendingStopAcks.remove(norm)) return
                if (isSuccess) {
                    syncLocalRecordingState(address, DotRecordingState.idle, applyActiveState = true)
                    activeRecordingDevices.remove(norm)
                } else {
                    setDeviceRecordingPhase(setOf(norm), FlashRecordingPhase.Recording)
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
        val callbackRequestId = popOutstandingFileInfoRequest(norm)
        val manualReadActive =
            norm in _fileInfoReadActiveTargets.value &&
                _fileInfoReadStatuses.value[norm]?.phase == FileInfoReadPhase.Reading &&
                callbackRequestId == _fileInfoReadStatuses.value[norm]?.requestId
        val autoReadActive =
            norm in pendingAutoFileInfoTargets &&
                callbackRequestId == AUTO_FILE_INFO_REQUEST_ID
        if (!manualReadActive && !autoReadActive) {
            callbackRequestId?.let { requestId ->
                releaseSerialFileInfoRequest(
                    SerialFileInfoRequest(norm, requestId),
                )
            }
            appendLog("[$addr] 忽略已结束请求的文件列表回调")
            return
        }
        val currentStatus = _fileInfoReadStatuses.value[norm]
        val completedAt = SystemClock.elapsedRealtime()
        val fileSnapshots = list?.let(::snapshotRecordingFileInfos).orEmpty()
        if (isSuccess && fileSnapshots.isNotEmpty()) {
            _fileList.value = _fileList.value.toMutableMap().also {
                it[norm] = fileSnapshots
            }
            if (manualReadActive) {
                _fileInfoReadStatuses.value = _fileInfoReadStatuses.value.toMutableMap().also {
                    it[norm] = FileInfoReadStatus(
                        phase = FileInfoReadPhase.Ready,
                        requestId = currentStatus?.requestId ?: 0,
                        startedAtElapsedMs = currentStatus?.startedAtElapsedMs,
                        completedAtElapsedMs = completedAt,
                    )
                }
            }
            appendLog("[$addr] 共 ${fileSnapshots.size} 个录制文件")
            fileSnapshots.forEach { f ->
                appendLog(
                    "  ↳ id=${f.fileId} ${f.fileName} " +
                        "${f.dataSize / 1024}KB ts=${f.startRecordingTimestamp}",
                )
            }
        } else {
            appendLog("[$addr] ${if (isSuccess) "无录制文件" else "获取文件列表失败"}")
            if (isSuccess) {
                _fileList.value = _fileList.value.toMutableMap().also { it[norm] = emptyList() }
                if (manualReadActive) {
                    _fileInfoReadStatuses.value = _fileInfoReadStatuses.value.toMutableMap().also {
                        it[norm] = FileInfoReadStatus(
                            phase = FileInfoReadPhase.Empty,
                            requestId = currentStatus?.requestId ?: 0,
                            startedAtElapsedMs = currentStatus?.startedAtElapsedMs,
                            completedAtElapsedMs = completedAt,
                        )
                    }
                }
            } else if (manualReadActive) {
                markFileInfoReadFailed(
                    targets = setOf(norm),
                    message = "设备返回文件列表失败",
                    requestId = currentStatus?.requestId,
                )
            }
        }
        if (manualReadActive) {
            _fileInfoReadActiveTargets.value -= norm
        }
        callbackRequestId?.let { requestId ->
            releaseSerialFileInfoRequest(
                SerialFileInfoRequest(norm, requestId),
            )
        }
        if (norm in pendingAutoFileInfoTargets) {
            pendingAutoFileInfoTargets.remove(norm)
            pendingAutoFileInfoRequests.remove(norm)
            finishAutoExportFileInfoIfReady()
        }
    }

    private fun invalidateFileInfoResults(
        targets: Set<String>,
        message: String,
    ) {
        val normalizedTargets = targets.map(::normalizeAddress).toSet()
        _fileInfoReadStatuses.value = _fileInfoReadStatuses.value.toMutableMap().also { statuses ->
            normalizedTargets.forEach { address ->
                val current = statuses[address]
                statuses[address] = FileInfoReadStatus(
                    phase = FileInfoReadPhase.Idle,
                    message = message,
                    requestId = current?.requestId ?: 0,
                    startedAtElapsedMs = current?.startedAtElapsedMs,
                    completedAtElapsedMs = current?.completedAtElapsedMs,
                )
            }
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
        val resolvedFile = resolveExportFile(norm, info)
        if (resolvedFile == null) {
            logRejectedExportCallbackOnce(norm, info)
            return
        }
        val writerKey = resolvedFile.key
        val targetInfo = resolvedFile.fileInfo
        val task = _exportTaskProgress.value
        if (
            norm !in exportStartedDevices ||
            writerKey !in task.targetFileKeys ||
            writerKey in task.finishedFileKeys ||
            writerKey in exportWriterFailures
        ) {
            return
        }
        exportDevicesWithData.add(norm)
        exportLastProgressAt[norm] = SystemClock.elapsedRealtime()
        exportFirstDataAt.putIfAbsent(norm, SystemClock.elapsedRealtime())
        try {
            val writer = exportWriters.getOrPut(writerKey) {
                val storedAssignment = recordingSessionPreferences.findAssignment(
                    deviceId = norm,
                    recordingUtcMs = recordingDateMs(targetInfo),
                )
                ExportCsvWriter.create(
                    address = addr,
                    fileInfo = targetInfo,
                    selectedIds = _selectedExportIds.value,
                    clockAnchors = recordingAnchors[norm],
                    assignment = storedAssignment?.assignment
                        ?: LongJumpDeviceRoles.assignmentForDevice(norm),
                    captureSessionUtcMs = storedAssignment?.sessionUtcMs
                        ?: recordingSessionUtcByDevice[norm],
                )
            }
            val bytesWritten = writer.write(data)
            val samplePosition = exportSampleProgress
                .computeIfAbsent(writerKey) { ExportSampleProgressTracker() }
                .observe(data.packetCounter)
            exportFrameCounts
                .computeIfAbsent(writerKey) { AtomicLong() }
                .set(samplePosition.toLong())
            exportDeviceFrameCounts.computeIfAbsent(norm) { AtomicLong() }.incrementAndGet()
            exportWrittenBytes[writerKey] = bytesWritten
            publishExportProgress(norm, writerKey)
        } catch (e: Exception) {
            if (exportWriterFailures.add(writerKey)) {
                exportWriters.remove(writerKey)?.close()
                synchronized(exportProgressPublishLock) {
                    val currentProgress = _exportTaskProgress.value
                    val failed = currentProgress.failedFileKeys + writerKey
                    val updated = currentProgress.copy(
                        activeFileKeys = currentProgress.activeFileKeys - writerKey,
                        failedFileKeys = failed,
                    )
                    _exportTaskProgress.value =
                        updated.copy(isExporting = updated.hasPendingFiles)
                }
                appendLog("[$addr] 导出写盘失败：${e.message ?: "未知错误"}")
                Log.e(TAG, "[$addr] export write failed: ${e.message}", e)
                failDeviceExport(norm, "导出写盘失败")
            }
        }
    }

    override fun onDotDataExported(address: String?, fileInfo: DotRecordingFileInfo?) {
        val addr = address ?: return
        val info = fileInfo ?: return
        val norm = normalizeAddress(addr)
        val resolvedFile = resolveExportFile(norm, info)
        if (resolvedFile == null) {
            logRejectedExportCallbackOnce(norm, info)
            return
        }
        val writerKey = resolvedFile.key
        val targetInfo = resolvedFile.fileInfo
        val currentTask = _exportTaskProgress.value
        if (
            norm !in exportStartedDevices ||
            writerKey !in currentTask.targetFileKeys ||
            writerKey in currentTask.finishedFileKeys
        ) {
            return
        }
        exportLastProgressAt[norm] = SystemClock.elapsedRealtime()
        exportWriters.remove(writerKey)?.close()
        publishExportProgress(norm, writerKey, force = true)
        synchronized(exportProgressPublishLock) {
            val task = _exportTaskProgress.value
            val completed = task.completedFileKeys + writerKey
            val targetBytes = task.targetBytesByFile[writerKey]
                ?: task.writtenBytesByFile[writerKey]
                ?: 1L
            val targetFrames = task.targetFramesByFile[writerKey]
                ?: task.framesByFile[writerKey]
                ?: 1
            val updated = task.copy(
                activeFileKeys = task.activeFileKeys - writerKey,
                framesByFile = task.framesByFile.toMutableMap().also {
                    it[writerKey] = targetFrames
                },
                writtenBytesByFile = task.writtenBytesByFile.toMutableMap().also {
                    it[writerKey] = targetBytes
                },
                completedFileKeys = completed,
            )
            _exportTaskProgress.value = updated.copy(isExporting = updated.hasPendingFiles)
        }
        exportFrameCounts.remove(writerKey)
        exportSampleProgress.remove(writerKey)
        exportWrittenBytes.remove(writerKey)
        appendLog("[$addr] 文件 ${targetInfo.fileName} 导出完成 ✓")
        finishDeviceExportIfFilesComplete(norm, addr)
    }

    override fun onDotAllDataExported(address: String?) {
        val addr = address ?: return
        val norm = normalizeAddress(addr)
        if (norm !in exportStartedDevices) {
            appendLog("[$addr] 忽略导出启动前的旧完成回调")
            return
        }
        val currentTask = _exportTaskProgress.value
        val deviceFileKeys = currentTask.targetFileKeys
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
                val updated = task.copy(
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
                _exportTaskProgress.value =
                    updated.copy(isExporting = updated.hasPendingFiles)
            }
        }
        finishDeviceExport(norm, addr)
    }

    override fun onDotStopExportingData(address: String?) {
        val addr = address ?: return
        val norm = normalizeAddress(addr)
        if (!exportStopRequested) {
            if (exportRequests.containsKey(norm)) {
                appendLog(
                    if (
                        exportDeferredRetryReasons.containsKey(norm) ||
                        norm in exportRestartScheduled
                    ) {
                        "[$addr] 当前导出已暂停"
                    } else {
                        "[$addr] SDK 导出已停止，等待自动恢复"
                    }
                )
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

    fun retryFailedExports() {
        val failedKeys = _exportTaskProgress.value.failedTargetFileKeys
        if (failedKeys.isEmpty() || _exportTaskProgress.value.hasPendingFiles) return
        appendLog("重新导出 ${failedKeys.size} 个失败文件")
        exportSelected(failedKeys)
    }

    // ── 内部 CSV 写入器 ──

    private class ExportCsvWriter private constructor(
        private val fileWriter: BufferedWriter,
        val filePath: String,
        private val fields: List<ExportDataField>,  // 按 ID 排序的选中字段
        private val clockAnchors: RecordingClockAnchors?,
        private val assignment: DeviceRoleAssignment?,
        private val captureSessionUtcMs: Long?,
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
                clockAnchors: RecordingClockAnchors?,
                assignment: DeviceRoleAssignment?,
                captureSessionUtcMs: Long?,
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
                return ExportCsvWriter(
                    fileWriter = fw,
                    filePath = file.absolutePath,
                    fields = fields,
                    clockAnchors = clockAnchors,
                    assignment = assignment,
                    captureSessionUtcMs = captureSessionUtcMs,
                    timestampAnchorUtcMs = timestampAnchorUtcMs,
                )
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
                "capture_session_utc_ms" to (captureSessionUtcMs?.toString() ?: ""),
                "athlete_id" to (assignment?.participant?.athleteId ?: ""),
                "athlete_name" to (assignment?.participant?.athleteName ?: ""),
                "participant_slot_id" to (assignment?.participant?.slotId ?: ""),
                "foot_side" to (assignment?.sideCode ?: ""),
                "left_device_id" to (assignment?.participant?.leftDeviceId ?: ""),
                "right_device_id" to (assignment?.participant?.rightDeviceId ?: ""),
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
