package com.buct.xsens.dot.engine

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.xsens.dot.android.sdk.events.DotData
import com.xsens.dot.android.sdk.interfaces.DotRecordingCallback
import com.xsens.dot.android.sdk.models.DotDevice
import com.xsens.dot.android.sdk.models.DotRecordingFileInfo
import com.xsens.dot.android.sdk.models.DotRecordingState
import com.xsens.dot.android.sdk.recording.DotRecordingManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

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
        private fun fI(arr: IntArray?,    i: Int) = if (arr != null && arr.size > i) arr[i] else 0

        // 默认导出字段：Timestamp + Euler + Acc + Gyro（与 CUSTOM_MODE_1 流式字段对应）
        val DEFAULT_EXPORT_IDS: Set<Byte> = setOf(0, 4, 7, 8)
    }

    // ── 导出字段选择 ──
    private val _selectedExportIds = MutableStateFlow(DEFAULT_EXPORT_IDS)
    val selectedExportIds: StateFlow<Set<Byte>> = _selectedExportIds.asStateFlow()

    fun setSelectedExportIds(ids: Set<Byte>) {
        if (ids.isEmpty()) return
        _selectedExportIds.value = ids
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

    private val _fileList = MutableStateFlow<Map<String, List<DotRecordingFileInfo>>>(emptyMap())
    val fileList: StateFlow<Map<String, List<DotRecordingFileInfo>>> = _fileList.asStateFlow()

    private val _exportProgress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val exportProgress: StateFlow<Map<String, Int>> = _exportProgress.asStateFlow()

    private val _exportDone = MutableStateFlow<Set<String>>(emptySet())
    val exportDone: StateFlow<Set<String>> = _exportDone.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val recordingLog: StateFlow<List<String>> = _log.asStateFlow()

    // ── 导出 CSV 写入器 (writerKey = "$addr-$fileId") ──
    private val exportWriters = ConcurrentHashMap<String, ExportCsvWriter>()

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var totalDevices = 0

    // ── 工具 ──

    private fun normalizeAddress(addr: String): String =
        addr.replace(":", "").replace("-", "").uppercase()

    private fun appendLog(msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _log.value = (_log.value + "[$t] $msg").takeLast(200) // 防止无限增长
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

        devices.forEach { dev ->
            val addr = normalizeAddress(dev.address ?: return@forEach)
            val mgr = DotRecordingManager(context, dev, this)
            managers[addr] = mgr
            mgr.enableDataRecordingNotification()
            appendLog("[${dev.address}] 正在启用录制通知…")
        }
    }

    fun requestFlashInfo() {
        managers.values.forEach { it.requestFlashInfo() }
    }

    /**
     * 设备断线重连后重新启用录制通知，使后续的 stopRecording/requestFileInfo 能正常工作。
     * address 为 BLE 地址（含冒号格式或规范化格式均可）。
     */
    fun reenableNotification(address: String) {
        val norm = normalizeAddress(address)
        val mgr  = managers[norm] ?: return
        mgr.enableDataRecordingNotification()
        appendLog("[$address] 重新连接，恢复录制通知…")
    }

    fun eraseAll() {
        managers.values.forEach {
            appendLog("[${it.mDevice?.address}] 正在擦除 Flash…")
            it.eraseRecordingData()
        }
    }

    fun startRecording() {
        try {
            // 用安全访问替代 !! 防止 mDevice 在同步后被 SDK 内部修改
            val unsynced = managers.entries.mapNotNull { (key, mgr) ->
                val dev = mgr.mDevice ?: return@mapNotNull null
                if (!dev.isSynced) (dev.address ?: key) else null
            }
            if (unsynced.isNotEmpty()) {
                appendLog("⚠ 以下设备未同步，SampleTimeFine 可能无法跨设备对齐: $unsynced")
            }
        } catch (e: Exception) {
            Log.w(TAG, "startRecording sync check failed: ${e.message}")
        }
        managers.values.forEach { it.startRecording() }
        _recordingActive.value = true
        appendLog("开始录制（${managers.size} 台设备）")
    }

    fun stopRecording() {
        managers.values.forEach { it.stopRecording() }
        _recordingActive.value = false
        appendLog("停止录制")
    }

    fun requestFileInfo() {
        _fileList.value = emptyMap()
        managers.values.forEach { it.requestFileInfo() }
        appendLog("正在获取文件列表…")
    }

    /**
     * 对所有设备并行导出所有文件。
     *
     * 每台设备有各自独立的 BLE GATT 连接，可以并行导出。
     * 关键点：先 stopExporting() 清除 SDK 内部"已完成"残留状态，
     * 否则 startExporting() 会立刻触发空的 onDotAllDataExported 回调。
     * 各台之间错开 300ms 启动，避免瞬间多个 GATT 请求冲击系统 BLE 栈。
     */
    fun exportAll() {
        // 重置 SDK 内部导出状态，防止"秒完成"空回调
        managers.values.forEach { it.stopExporting() }

        _exportProgress.value = emptyMap()
        _exportDone.value = emptySet()

        val sortedIds = _selectedExportIds.value.sorted()
        val exportIds = ByteArray(sortedIds.size) { sortedIds[it] }
        val fieldLabels = sortedIds.mapNotNull { id ->
            ALL_EXPORT_FIELDS.find { it.id == id }?.label
        }.joinToString(", ")

        val targets = managers.keys.filter { addr -> !_fileList.value[addr].isNullOrEmpty() }
        val skipped = managers.keys.filter { addr -> _fileList.value[addr].isNullOrEmpty() }
        skipped.forEach { addr ->
            val rawAddr = managers[addr]?.mDevice?.address ?: addr
            appendLog("[$rawAddr] 无文件可导出，跳过")
        }
        appendLog("导出字段：$fieldLabels | 并行导出 ${targets.size} 台设备")

        targets.forEachIndexed { index, addr ->
            val mgr = managers[addr] ?: return@forEachIndexed
            val files = _fileList.value[addr] ?: return@forEachIndexed
            mainHandler.postDelayed({
                appendLog("[${mgr.mDevice?.address}] 开始导出 ${files.size} 个文件…")
                mgr.selectExportedData(exportIds)
                mgr.startExporting(ArrayList(files))
            }, index * 300L)
        }
    }

    /** 仅导出 selectedKeys 中指定的文件（key = "$normalizedAddr-$fileId"）*/
    fun exportSelected(selectedKeys: Set<String>) {
        managers.values.forEach { it.stopExporting() }

        _exportProgress.value = emptyMap()
        _exportDone.value = emptySet()

        val sortedIds = _selectedExportIds.value.sorted()
        val exportIds = ByteArray(sortedIds.size) { sortedIds[it] }
        val fieldLabels = sortedIds.mapNotNull { id ->
            ALL_EXPORT_FIELDS.find { it.id == id }?.label
        }.joinToString(", ")

        val targets = managers.keys.filter { addr ->
            val files = _fileList.value[addr]?.filter { f -> "$addr-${f.fileId}" in selectedKeys }
            !files.isNullOrEmpty()
        }
        appendLog("导出字段：$fieldLabels | 并行导出 ${targets.size} 台设备")

        targets.forEachIndexed { index, addr ->
            val mgr = managers[addr] ?: return@forEachIndexed
            val files = _fileList.value[addr]
                ?.filter { f -> "$addr-${f.fileId}" in selectedKeys }
                ?: return@forEachIndexed
            mainHandler.postDelayed({
                appendLog("[${mgr.mDevice?.address}] 开始导出 ${files.size} 个文件…")
                mgr.selectExportedData(exportIds)
                mgr.startExporting(ArrayList(files))
            }, index * 300L)
        }
    }

    fun stopExporting() {
        mainHandler.removeCallbacksAndMessages(null)
        managers.values.forEach { it.stopExporting() }
        exportWriters.values.forEach { it.close() }
        exportWriters.clear()
    }

    fun clear() {
        stopExporting()
        managers.values.forEach { it.clear() }
        managers.clear()
        totalDevices = 0
    }

    // ── DotRecordingCallback ──

    override fun onDotRecordingNotification(address: String?, isEnabled: Boolean) {
        val addr = address ?: return
        val norm = normalizeAddress(addr)
        if (isEnabled) {
            _notificationReady.value = _notificationReady.value + norm
            appendLog("[$addr] 录制通知已启用")
            managers[norm]?.requestFlashInfo()
        } else {
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
    }

    override fun onDotEraseDone(address: String?, isSuccess: Boolean) {
        val addr = address ?: return
        appendLog("[$addr] 擦除${if (isSuccess) "成功 ✓" else "失败 ✗"}")
        if (isSuccess) {
            val norm = normalizeAddress(addr)
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
        appendLog("[$address] $action ${if (isSuccess) "✓" else "✗"}  state=$recordingState")
        if (recordingId == DotRecordingManager.RECORDING_ID_STOP_RECORDING && isSuccess) {
            _recordingActive.value = false
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
        val writerKey = "$norm-${info.fileId}"
        val writer = exportWriters.getOrPut(writerKey) {
            ExportCsvWriter.create(context, addr, info, _selectedExportIds.value)
        }
        writer.write(data)
        val prev = _exportProgress.value[norm] ?: 0
        _exportProgress.value = _exportProgress.value.toMutableMap().also { it[norm] = prev + 1 }
    }

    override fun onDotDataExported(address: String?, fileInfo: DotRecordingFileInfo?) {
        val addr = address ?: return
        val info = fileInfo ?: return
        val norm = normalizeAddress(addr)
        exportWriters.remove("$norm-${info.fileId}")?.close()
        appendLog("[$addr] 文件 ${info.fileName} 导出完成 ✓")
    }

    override fun onDotAllDataExported(address: String?) {
        val addr = address ?: return
        val norm = normalizeAddress(addr)
        _exportDone.value = _exportDone.value + norm
        appendLog("[$addr] 全部文件导出完成 ✓")
        Log.i(TAG, "[$addr] export done")
    }

    override fun onDotStopExportingData(address: String?) {
        val addr = address ?: return
        val norm = normalizeAddress(addr)
        exportWriters.keys.filter { it.startsWith(norm) }.forEach {
            exportWriters.remove(it)?.close()
        }
        appendLog("[$addr] 导出已停止")
    }

    // ── 内部 CSV 写入器 ──

    private class ExportCsvWriter private constructor(
        private val fileWriter: FileWriter,
        val filePath: String,
        private val fields: List<ExportDataField>  // 按 ID 排序的选中字段
    ) {
        companion object {
            fun create(
                context: Context,
                address: String,
                fileInfo: DotRecordingFileInfo,
                selectedIds: Set<Byte>
            ): ExportCsvWriter {
                val fields = ALL_EXPORT_FIELDS
                    .filter { selectedIds.contains(it.id) }
                    .sortedBy { it.id }

                val header = "PacketCounter," + fields.joinToString(",") { it.columns }

                val mac      = address.replace(":", "").replace("-", "").uppercase()
                val ts       = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val baseName = "${mac}_${ts}.csv"

                // 优先存到 Documents/XsensData/offline_export（可被其他 App 读取）
                val publicDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                    "XsensData/offline_export"
                )
                publicDir.mkdirs()
                val privateDir = File(context.getExternalFilesDir(null), "offline_export")
                privateDir.mkdirs()

                val dir  = if (publicDir.canWrite()) publicDir else privateDir
                var file = File(dir, baseName)
                if (file.exists()) {
                    file = File(dir, "${mac}_${ts}_${fileInfo.fileId}.csv")
                }
                val fw   = FileWriter(file, false)
                fw.write("$header\n")
                fw.flush()
                return ExportCsvWriter(fw, file.absolutePath, fields)
            }
        }

        fun write(d: DotData) {
            try {
                val row = "${d.packetCounter}," + fields.joinToString(",") { it.extract(d) }
                fileWriter.write("$row\n")
            } catch (_: Exception) {}
        }

        fun close() {
            try { fileWriter.flush(); fileWriter.close() } catch (_: Exception) {}
        }
    }
}
