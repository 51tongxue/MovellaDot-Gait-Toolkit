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
        if (_uiState.value.isAnalyzing) return
        _uiState.update {
            it.copy(
                analysisMode = mode,
                result = null,
                manifestPath = "",
                statusMessage = "",
                errorMessage = "",
                isAnalyzing = false,
                rangeStartS = "",
                rangeEndS = "",
                appliedRangeStartS = null,
                appliedRangeEndS = null,
                rangeErrorMessage = "",
            )
        }
    }

    fun selectAthlete(athleteId: String) {
        _uiState.update {
            val selectedAttemptKey = it.selectedAttemptKey?.takeIf { key ->
                val attempt = it.attempts.firstOrNull { attempt -> attempt.key == key }
                attempt != null && (attempt.athleteId == null || attempt.athleteId == athleteId)
            }
            it.copy(
                selectedAthleteId = athleteId,
                selectedAttemptKey = selectedAttemptKey,
                result = null,
                manifestPath = "",
                errorMessage = "",
                rangeStartS = "",
                rangeEndS = "",
                appliedRangeStartS = null,
                appliedRangeEndS = null,
                rangeErrorMessage = "",
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
                rangeStartS = "",
                rangeEndS = "",
                appliedRangeStartS = null,
                appliedRangeEndS = null,
                rangeErrorMessage = "",
            )
        }
        refreshAttemptNoForSelection()
    }

    fun refreshCapturedAttempts() {
        refreshAttempts()
    }

    fun updateRangeStart(value: String) {
        _uiState.update {
            it.copy(
                rangeStartS = numericInput(value),
                rangeErrorMessage = "",
            )
        }
    }

    fun updateRangeEnd(value: String) {
        _uiState.update {
            it.copy(
                rangeEndS = numericInput(value),
                rangeErrorMessage = "",
            )
        }
    }

    fun clearRange() {
        _uiState.update {
            it.copy(
                rangeStartS = "",
                rangeEndS = "",
                appliedRangeStartS = null,
                appliedRangeEndS = null,
                rangeErrorMessage = "",
            )
        }
    }

    fun applyRange() {
        val state = _uiState.value
        val result = state.result ?: run {
            _uiState.update {
                it.copy(rangeErrorMessage = "请先完成一次分析")
            }
            return
        }
        val start = state.rangeStartS.toDoubleOrNull()
        val end = state.rangeEndS.toDoubleOrNull()
        if (state.rangeStartS.isNotBlank() && start == null) {
            _uiState.update {
                it.copy(rangeErrorMessage = "开始时间格式不正确")
            }
            return
        }
        if (state.rangeEndS.isNotBlank() && end == null) {
            _uiState.update {
                it.copy(rangeErrorMessage = "结束时间格式不正确")
            }
            return
        }
        if (start != null && start < 0.0) {
            _uiState.update {
                it.copy(rangeErrorMessage = "开始时间不能小于 0")
            }
            return
        }
        if (start != null && end != null && start >= end) {
            _uiState.update {
                it.copy(rangeErrorMessage = "开始时间必须小于结束时间")
            }
            return
        }
        val duration = result.summary.durationS
        if (duration != null && start != null && start >= duration) {
            _uiState.update {
                it.copy(rangeErrorMessage = "开始时间超出数据时长")
            }
            return
        }
        if (duration != null && end != null && end > duration) {
            _uiState.update {
                it.copy(rangeErrorMessage = "结束时间超出数据时长")
            }
            return
        }
        _uiState.update {
            it.copy(
                appliedRangeStartS = start,
                appliedRangeEndS = end,
                rangeErrorMessage = "",
            )
        }
    }

    fun analyze() {
        historyLoadJob?.cancel()
        historyLoadJob = null
        val state = _uiState.value
        if (state.isAnalyzing) return
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
                statusMessage = "",
                errorMessage = "",
                section = AnalysisSection.Main,
                rangeStartS = "",
                rangeEndS = "",
                appliedRangeStartS = null,
                appliedRangeEndS = null,
                rangeErrorMessage = "",
            )
        }

        analysisJob?.cancel()
        analysisJob = viewModelScope.launch {
            val rawResult = withContext(Dispatchers.Default) {
                analysisManager.analyzeGait(
                    primaryPath,
                    athlete.weightKg.takeIf { it > 0.0 }?.toString() ?: "75",
                    "-1.0",
                    "-1.0",
                    takeoffStep.toString(),
                    takeoffSide,
                    "0",
                )
            }
            val parsed = withContext(Dispatchers.Default) {
                parseAnalysisResult(rawResult).map { result ->
                    val normalizedJson = JSONObject(result.rawJson)
                        .put("analysis_mode", state.analysisMode.code)
                    normalizedJson.optJSONObject("summary")
                        ?.put("analysis_mode", state.analysisMode.code)
                    result.copy(
                        mode = state.analysisMode,
                        rawJson = normalizedJson.toString(),
                    )
                }
            }
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

    fun selectHistoryDate(date: String?) {
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
                rangeStartS = "",
                rangeEndS = "",
                appliedRangeStartS = null,
                appliedRangeEndS = null,
                rangeErrorMessage = "",
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
                        rangeStartS = "",
                        rangeEndS = "",
                        appliedRangeStartS = null,
                        appliedRangeEndS = null,
                        rangeErrorMessage = "",
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

    fun refreshAthletes() {
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
            .sortedBy(::fileSourcePriority)
            .distinctBy { file ->
                "${extractDeviceId(file.name)}_${captureStamp(file.name)}"
            }

        data class Entry(
            val file: File,
            val side: FootSide,
            val capturedAt: Date,
            val athleteId: String?,
            val sessionKey: String,
        )

        val entries = files.mapNotNull { file ->
            val metadata = readCsvHeaderMetadata(file)
            val deviceId = extractDeviceId(file.name)
            val assignment = deviceId?.let(LongJumpDeviceRoles::assignmentForDevice)
            val side = FootSide.fromCode(metadata["foot_side"])
                ?: FootSide.fromCode(assignment?.sideCode)
                ?: return@mapNotNull null
            val capturedAt = parseCaptureDate(file.name) ?: return@mapNotNull null
            val athleteId = metadata["athlete_id"]
                ?.takeIf(String::isNotBlank)
                ?: assignment?.participant?.athleteId?.takeIf(String::isNotBlank)
            val sessionKey = metadata["capture_session_utc_ms"]
                ?.takeIf(String::isNotBlank)
                ?: captureStamp(file.name)
                ?: file.nameWithoutExtension
            Entry(file, side, capturedAt, athleteId, sessionKey)
        }
        val attempts = mutableListOf<CapturedAttempt>()
        entries
            .groupBy { "${it.athleteId.orEmpty()}_${it.sessionKey}" }
            .forEach { (groupKey, groupEntries) ->
                val left = groupEntries
                    .filter { it.side == FootSide.Left }
                    .minByOrNull(Entry::capturedAt)
                val right = groupEntries
                    .filter { it.side == FootSide.Right }
                    .minByOrNull(Entry::capturedAt)
                val capturedAt = listOfNotNull(left, right)
                    .minOfOrNull(Entry::capturedAt)
                    ?: return@forEach
                val athleteId = left?.athleteId ?: right?.athleteId
                attempts += CapturedAttempt(
                    key = "${athleteId.orEmpty()}_${groupKey}_${capturedAt.time}",
                    capturedAt = capturedAt,
                    leftPath = left?.file?.absolutePath,
                    rightPath = right?.file?.absolutePath,
                    athleteId = athleteId,
                )
            }
        return attempts.sortedByDescending { it.capturedAt }
    }

    private fun readCsvHeaderMetadata(file: File): Map<String, String> {
        val metadata = linkedMapOf<String, String>()
        runCatching {
            file.useLines(Charsets.UTF_8) { lines ->
                lines.take(40).forEach { line ->
                    val columns = line.split(',')
                    if (columns.any { it.trim() == "PacketCounter" }) return@useLines
                    if (columns.size >= 2 && columns[0].isNotBlank()) {
                        metadata[columns[0].trim().lowercase(Locale.US)] =
                            columns.drop(1).joinToString(",").trim()
                    }
                }
            }
        }
        return metadata
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
}
