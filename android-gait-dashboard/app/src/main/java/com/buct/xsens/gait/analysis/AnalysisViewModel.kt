package com.buct.xsens.gait.analysis

import android.app.Application
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.buct.xsens.dot.data.LongJumpDeviceRoles
import com.buct.xsens.gait.data.AthleteEntity
import com.buct.xsens.gait.data.GaitDataRepository
import com.buct.xsens.gait.engine.GaitAnalysisManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class AnalysisViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = GaitDataRepository(application)
    private val analysisManager = GaitAnalysisManager(application)
    private val _uiState = MutableStateFlow(AnalysisUiState())
    val uiState: StateFlow<AnalysisUiState> = _uiState.asStateFlow()
    private var analysisJob: Job? = null
    private var historyLoadJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            repository.cleanupDuplicateAnalysisRecords()
            refreshAll()
        }
        viewModelScope.launch(Dispatchers.Default) {
            analysisManager.warmUp()
            _uiState.update { it.copy(isEngineWarming = false) }
        }
    }

    fun setSection(section: AnalysisSection) {
        _uiState.update { it.copy(section = section, errorMessage = "") }
        if (section == AnalysisSection.History) refreshHistory()
    }

    fun setAnalysisMode(mode: AnalysisMode) {
        analysisJob?.cancel()
        _uiState.update {
            it.copy(
                analysisMode = mode,
                result = null,
                manifestPath = "",
                statusMessage = "",
                errorMessage = "",
                isAnalyzing = false,
            )
        }
    }

    fun selectAthlete(athleteId: String) {
        _uiState.update {
            it.copy(
                selectedAthleteId = athleteId,
                result = null,
                manifestPath = "",
                errorMessage = "",
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val selectedPath = _uiState.value.selectedAttempt
                ?.preferredPath(_uiState.value.selectedAthlete?.dominantLeg)
            val next = selectedPath
                ?.let { repository.getAttemptNoForCapture(athleteId, it) }
                ?: repository.getNextAttemptNo(athleteId)
            _uiState.update { state ->
                state.copy(
                    attemptNo = next,
                    historyAthleteId = state.historyAthleteId ?: athleteId,
                )
            }
        }
    }

    fun updateAttemptNo(value: String) {
        val normalized = value.uppercase(Locale.US).filter { it.isLetterOrDigit() }.take(8)
        _uiState.update { it.copy(attemptNo = normalized) }
    }

    fun selectAttempt(key: String) {
        _uiState.update {
            it.copy(
                selectedAttemptKey = key,
                result = null,
                manifestPath = "",
                statusMessage = "",
                errorMessage = "",
            )
        }
        refreshAttemptNoForSelection()
    }

    fun refreshCapturedAttempts() {
        refreshAttempts()
    }

    fun updateRangeStart(value: String) {
        _uiState.update { it.copy(rangeStartS = numericInput(value), errorMessage = "") }
    }

    fun updateRangeEnd(value: String) {
        _uiState.update { it.copy(rangeEndS = numericInput(value), errorMessage = "") }
    }

    fun clearRange() {
        _uiState.update { it.copy(rangeStartS = "", rangeEndS = "", errorMessage = "") }
    }

    fun analyze(useRange: Boolean = false) {
        historyLoadJob?.cancel()
        historyLoadJob = null
        val state = _uiState.value
        val athlete = state.selectedAthlete ?: run {
            _uiState.update { it.copy(errorMessage = "请先选择运动员") }
            return
        }
        val attempt = state.selectedAttempt ?: run {
            _uiState.update { it.copy(errorMessage = "请先选择数据") }
            return
        }
        val primaryPath = attempt.preferredPath(athlete.dominantLeg) ?: run {
            _uiState.update { it.copy(errorMessage = "当前试跳没有可分析的数据") }
            return
        }
        val start = if (useRange) state.rangeStartS.toDoubleOrNull() else null
        val end = if (useRange) state.rangeEndS.toDoubleOrNull() else null
        if (start != null && end != null && start >= end) {
            _uiState.update { it.copy(errorMessage = "开始时间必须小于结束时间") }
            return
        }
        val takeoffStep = if (state.analysisMode == AnalysisMode.LongJump) 0 else -1
        val takeoffSide = if (state.analysisMode == AnalysisMode.LongJump) {
            athlete.dominantLeg?.code ?: run {
                _uiState.update { it.copy(errorMessage = "运动员信息缺少惯用腿") }
                return
            }
        } else {
            "0"
        }

        _uiState.update {
            it.copy(
                isAnalyzing = true,
                result = null,
                manifestPath = "",
                statusMessage = "正在分析",
                errorMessage = "",
                section = AnalysisSection.Main,
            )
        }

        analysisJob?.cancel()
        analysisJob = viewModelScope.launch {
            val rawResult = withContext(Dispatchers.Default) {
                analysisManager.analyzeGait(
                    primaryPath,
                    athlete.weightKg.takeIf { it > 0.0 }?.toString() ?: "75",
                    (start ?: -1.0).toString(),
                    (end ?: -1.0).toString(),
                    takeoffStep.toString(),
                    takeoffSide,
                    "0",
                )
            }
            val parsed = withContext(Dispatchers.Default) { parseAnalysisResult(rawResult) }
            parsed.fold(
                onSuccess = { result ->
                    runCatching {
                        val sourcePaths = JSONArray()
                        attempt.sourcePaths().forEach(sourcePaths::put)

                        val manifestJson = JSONObject(result.rawJson)
                        manifestJson.put("analysis_mode", state.analysisMode.code)
                        manifestJson.optJSONObject("summary")
                            ?.put("analysis_mode", state.analysisMode.code)
                        manifestJson.put("source_file_paths", sourcePaths)
                        if (useRange && (start != null || end != null)) {
                            val timeRangeJson = JSONObject()
                            timeRangeJson.put("start", start ?: JSONObject.NULL)
                            timeRangeJson.put("end", end ?: JSONObject.NULL)
                            manifestJson.put("timeRange", timeRangeJson)
                        }

                        val manifestPayload = manifestJson.toString()
                        val saveResult = withContext(Dispatchers.IO) {
                            repository.saveImuManifest(
                                athleteId = athlete.id,
                                attemptNoRaw = state.attemptNo,
                                sourceFilePath = primaryPath,
                                analysisJson = manifestPayload,
                            )
                        }
                        val saved = JSONObject(saveResult)
                        check(saved.optBoolean("ok", false)) {
                            saved.optString("error", "分析完成，但结果保存失败")
                        }
                        saved
                    }.onSuccess { saved ->
                        _uiState.update {
                            it.copy(
                                isAnalyzing = false,
                                result = result,
                                manifestPath = saved.optString("path"),
                                statusMessage = "",
                                errorMessage = "",
                            )
                        }
                        refreshHistory()
                    }.onFailure { error ->
                        _uiState.update {
                            it.copy(
                                isAnalyzing = false,
                                result = result,
                                statusMessage = "",
                                errorMessage = error.message ?: "分析完成，但结果保存失败",
                            )
                        }
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isAnalyzing = false,
                            statusMessage = "",
                            errorMessage = error.message ?: "分析失败",
                        )
                    }
                },
            )
        }
    }

    fun saveAthlete(profile: AthleteProfile) {
        viewModelScope.launch(Dispatchers.IO) {
            val extra = runCatching { JSONObject(profile.extraJson) }.getOrElse { JSONObject() }
            profile.dominantLeg?.let {
                extra.put("dominant_leg", if (it == FootSide.Left) "left" else "right")
            }
            val entity = AthleteEntity(
                athleteId = profile.id.ifBlank { "ath-local-${System.currentTimeMillis()}" },
                athleteCode = profile.code.ifBlank { profile.id.ifBlank { "LOCAL" } },
                name = profile.name.trim(),
                gender = profile.gender.trim(),
                birthDate = profile.birthDate.trim(),
                heightCm = profile.heightCm,
                weightKg = profile.weightKg,
                groupName = profile.groupName.trim(),
                extra = extra.toString(),
            )
            repository.saveAthlete(entity)
            val athletes = repository.listAthletes().map(AthleteProfile::fromEntity)
            _uiState.update {
                it.copy(
                    athletes = athletes,
                    selectedAthleteId = entity.athleteId,
                    statusMessage = "运动员信息已保存",
                    errorMessage = "",
                )
            }
            refreshNextAttempt()
        }
    }

    fun importAthletes(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val content = getApplication<Application>().contentResolver
                    .openInputStream(uri)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?: error("无法读取运动员名单")
                repository.importAthletesJson(content)
            }
            val json = runCatching { JSONObject(result) }.getOrElse { JSONObject() }
            if (json.optBoolean("ok", false)) {
                refreshAthletes()
                _uiState.update {
                    it.copy(
                        statusMessage = "已导入 ${json.optInt("count")} 名运动员",
                        errorMessage = "",
                    )
                }
            } else {
                _uiState.update {
                    it.copy(errorMessage = json.optString("error", "导入运动员失败"))
                }
            }
        }
    }

    fun selectHistoryAthlete(athleteId: String) {
        _uiState.update {
            it.copy(
                historyAthleteId = athleteId,
                selectedHistoryDate = null,
            )
        }
        refreshHistory()
    }

    fun selectHistoryDate(date: String) {
        _uiState.update { it.copy(selectedHistoryDate = date) }
    }

    fun openHistory(item: AnalysisHistoryItem) {
        analysisJob?.cancel()
        analysisJob = null
        historyLoadJob?.cancel()
        val attempt = findAttemptForPath(item.sourcePath)
        _uiState.update {
            it.copy(
                selectedAthleteId = item.athleteId,
                attemptNo = item.attemptNo,
                selectedAttemptKey = attempt?.key,
                analysisMode = item.mode,
                section = AnalysisSection.Main,
                result = null,
                manifestPath = item.manifestPath,
                isAnalyzing = false,
                statusMessage = "",
                errorMessage = "",
            )
        }
        historyLoadJob = viewModelScope.launch {
            val parsed = runCatching {
                val rawJson = withContext(Dispatchers.IO) {
                    repository.loadSavedAnalysisJson(item.manifestPath)
                }
                withContext(Dispatchers.Default) {
                    parseAnalysisResult(rawJson).getOrThrow()
                }
            }
            parsed.onSuccess { result ->
                _uiState.update {
                    it.copy(
                        result = result,
                        isAnalyzing = false,
                        statusMessage = "",
                        errorMessage = "",
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        result = null,
                        isAnalyzing = false,
                        statusMessage = "",
                        errorMessage = error.message ?: "历史结果读取失败",
                    )
                }
            }
        }
    }

    fun deleteHistory(itemId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteAnalysisRecord(itemId)
            refreshHistory()
        }
    }

    fun updateLanConfig(transform: (LanShareUiConfig) -> LanShareUiConfig) {
        _uiState.update { it.copy(lanConfig = transform(it.lanConfig), lanMessage = "") }
    }

    fun saveLanConfig() {
        val config = _uiState.value.lanConfig
        viewModelScope.launch(Dispatchers.IO) {
            val result = repository.saveLanUploadConfigJson(config.toJson().toString())
            val json = runCatching { JSONObject(result) }.getOrElse { JSONObject() }
            _uiState.update {
                it.copy(
                    lanMessage = if (json.optBoolean("ok", false)) "上传设置已保存"
                    else json.optString("error", "保存失败"),
                )
            }
        }
    }

    fun testLanConnection() {
        _uiState.update { it.copy(isLanBusy = true, lanMessage = "正在测试连接") }
        viewModelScope.launch(Dispatchers.IO) {
            val result = repository.testLanUploadConnection()
            val json = runCatching { JSONObject(result) }.getOrElse { JSONObject() }
            _uiState.update {
                it.copy(
                    isLanBusy = false,
                    lanMessage = if (json.optBoolean("ok", false)) "共享文件夹连接正常"
                    else json.optString("error", "连接失败"),
                )
            }
        }
    }

    fun uploadCurrentResult() {
        val state = _uiState.value
        val attempt = state.selectedAttempt ?: return
        if (state.manifestPath.isBlank()) {
            _uiState.update { it.copy(lanMessage = "请先完成一次分析") }
            return
        }
        _uiState.update { it.copy(isLanBusy = true, lanMessage = "正在上传") }
        viewModelScope.launch(Dispatchers.IO) {
            val result = repository.uploadAnalysisToLan(
                state.manifestPath,
                JSONArray(attempt.sourcePaths()).toString(),
            )
            val json = runCatching { JSONObject(result) }.getOrElse { JSONObject() }
            _uiState.update {
                it.copy(
                    isLanBusy = false,
                    lanMessage = if (json.optBoolean("ok", false)) {
                        if (json.optBoolean("skipped", false)) "未启用局域网上传" else "本次结果已上传"
                    } else {
                        json.optString("error", "上传失败")
                    },
                )
            }
        }
    }

    private fun refreshAll() {
        refreshAthletes()
        refreshAttempts()
        loadLanConfig()
    }

    private fun refreshAthletes() {
        viewModelScope.launch(Dispatchers.IO) {
            val athletes = repository.listAthletes().map(AthleteProfile::fromEntity)
            _uiState.update { state ->
                val selected = state.selectedAthleteId?.takeIf { id -> athletes.any { it.id == id } }
                    ?: athletes.firstOrNull()?.id
                state.copy(
                    athletes = athletes,
                    selectedAthleteId = selected,
                    historyAthleteId = state.historyAthleteId ?: selected,
                )
            }
            refreshNextAttempt()
            refreshHistory()
        }
    }

    private fun refreshAttempts() {
        viewModelScope.launch(Dispatchers.IO) {
            val attempts = loadCapturedAttempts()
            _uiState.update { state ->
                state.copy(
                    attempts = attempts,
                    selectedAttemptKey = state.selectedAttemptKey
                        ?.takeIf { key -> attempts.any { it.key == key } },
                )
            }
        }
    }

    private fun refreshNextAttempt() {
        val athleteId = _uiState.value.selectedAthleteId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val next = repository.getNextAttemptNo(athleteId)
            _uiState.update { it.copy(attemptNo = next) }
        }
    }

    private fun refreshAttemptNoForSelection() {
        val state = _uiState.value
        val athleteId = state.selectedAthleteId ?: return
        val sourcePath = state.selectedAttempt
            ?.preferredPath(state.selectedAthlete?.dominantLeg)
            ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val attemptNo = repository.getAttemptNoForCapture(athleteId, sourcePath)
            _uiState.update { current ->
                if (current.selectedAthleteId == athleteId &&
                    current.selectedAttempt?.sourcePaths()?.contains(sourcePath) == true
                ) {
                    current.copy(attemptNo = attemptNo)
                } else {
                    current
                }
            }
        }
    }

    private fun refreshHistory() {
        val athleteId = _uiState.value.historyAthleteId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val items = repository.listAnalysisRecords(athleteId).map(AnalysisHistoryItem::fromEntity)
            _uiState.update { state ->
                val selectedDate = state.selectedHistoryDate
                    ?.takeIf { date -> items.any { it.trainingDate == date } }
                    ?: items.firstOrNull()?.trainingDate
                state.copy(history = items, selectedHistoryDate = selectedDate)
            }
        }
    }

    private fun loadLanConfig() {
        viewModelScope.launch(Dispatchers.IO) {
            val root = runCatching { JSONObject(repository.getLanUploadConfigJson()) }.getOrElse { JSONObject() }
            val config = root.optJSONObject("config") ?: JSONObject()
            _uiState.update {
                it.copy(
                    lanConfig = LanShareUiConfig(
                        enabled = config.optBoolean("enabled", false),
                        host = config.optString("host"),
                        shareName = config.optString("share_name"),
                        remoteDir = config.optString("remote_dir"),
                        username = config.optString("username"),
                        password = config.optString("password"),
                        domain = config.optString("domain"),
                        uploadSourceCsv = config.optBoolean("upload_source_csv", true),
                    )
                )
            }
        }
    }

    private fun loadCapturedAttempts(): List<CapturedAttempt> {
        val docs = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "XsensData",
        )
        val app = getApplication<Application>()
        val files = listOfNotNull(
            File(docs, "offline_export"),
            File(docs, "data_logging"),
            app.getExternalFilesDir(null)?.let { File(it, "offline_export") },
            app.getExternalFilesDir(null)?.let { File(it, "data_logging") },
        )
            .filter(File::exists)
            .flatMap { dir ->
                dir.listFiles { file -> file.isFile && file.name.endsWith(".csv", ignoreCase = true) }
                    ?.toList()
                    .orEmpty()
            }
            .filter { file ->
                val device = extractDeviceId(file.name)
                device != null && LongJumpDeviceRoles.isTargetDevice(device)
            }
            .sortedBy(::fileSourcePriority)
            .distinctBy { file ->
                "${extractDeviceId(file.name)}_${captureStamp(file.name)}"
            }

        data class Entry(val file: File, val side: FootSide, val capturedAt: Date)

        val entries = files.mapNotNull { file ->
            val side = FootSide.fromCode(LongJumpDeviceRoles.sideCode(extractDeviceId(file.name) ?: ""))
                ?: return@mapNotNull null
            val capturedAt = parseCaptureDate(file.name) ?: return@mapNotNull null
            Entry(file, side, capturedAt)
        }
        val left = entries.filter { it.side == FootSide.Left }.sortedByDescending { it.capturedAt }
        val rightRemaining = entries.filter { it.side == FootSide.Right }.toMutableList()
        val attempts = mutableListOf<CapturedAttempt>()

        left.forEach { leftEntry ->
            val right = rightRemaining
                .filter { sameDay(it.capturedAt, leftEntry.capturedAt) }
                .minByOrNull { abs(it.capturedAt.time - leftEntry.capturedAt.time) }
                ?.takeIf { abs(it.capturedAt.time - leftEntry.capturedAt.time) <= PAIR_TOLERANCE_MS }
            if (right != null) rightRemaining.remove(right)
            val capturedAt = if (right == null) leftEntry.capturedAt
            else Date(minOf(leftEntry.capturedAt.time, right.capturedAt.time))
            attempts += CapturedAttempt(
                key = "${capturedAt.time}_${leftEntry.file.name}_${right?.file?.name.orEmpty()}",
                capturedAt = capturedAt,
                leftPath = leftEntry.file.absolutePath,
                rightPath = right?.file?.absolutePath,
            )
        }
        rightRemaining.forEach { entry ->
            attempts += CapturedAttempt(
                key = "${entry.capturedAt.time}_${entry.file.name}",
                capturedAt = entry.capturedAt,
                leftPath = null,
                rightPath = entry.file.absolutePath,
            )
        }
        return attempts.sortedByDescending { it.capturedAt }
    }

    private fun findAttemptForPath(path: String): CapturedAttempt? =
        _uiState.value.attempts.firstOrNull {
            it.leftPath == path || it.rightPath == path ||
                captureStamp(File(path).name) == captureStamp(File(it.leftPath ?: it.rightPath.orEmpty()).name)
        }

    private fun extractDeviceId(name: String): String? {
        Regex("DOT_([0-9A-Fa-f]{12})").find(name)?.let { return it.groupValues[1] }
        Regex("^([0-9A-Fa-f]{12})_20\\d{6}").find(name)?.let { return it.groupValues[1] }
        return null
    }

    private fun sameDay(first: Date, second: Date): Boolean {
        val formatter = java.text.SimpleDateFormat("yyyyMMdd", Locale.US)
        return formatter.format(first) == formatter.format(second)
    }

    private fun fileSourcePriority(file: File): Int {
        val path = file.absolutePath.replace('\\', '/')
        return when {
            path.contains("/Documents/XsensData/offline_export/") -> 0
            path.contains("/Documents/XsensData/data_logging/") -> 1
            path.contains("/offline_export/") -> 2
            else -> 3
        }
    }

    private fun numericInput(value: String): String =
        value.filter { it.isDigit() || it == '.' }.take(8)

    private fun LanShareUiConfig.toJson(): JSONObject =
        JSONObject()
            .put("enabled", enabled)
            .put("host", host)
            .put("share_name", shareName)
            .put("remote_dir", remoteDir)
            .put("username", username)
            .put("password", password)
            .put("domain", domain)
            .put("upload_source_csv", uploadSourceCsv)

    private companion object {
        private const val PAIR_TOLERANCE_MS = 5_000L
    }
}
