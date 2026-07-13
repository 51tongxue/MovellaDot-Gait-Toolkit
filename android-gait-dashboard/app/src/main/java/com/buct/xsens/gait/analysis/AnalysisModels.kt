package com.buct.xsens.gait.analysis

import com.buct.xsens.gait.data.AnalysisRecordEntity
import com.buct.xsens.gait.data.AthleteEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class AnalysisSection {
    Main,
    Advanced,
    History,
}

enum class AnalysisMode(val code: String, val label: String) {
    LongJump("long_jump", "跳远"),
    GeneralGait("general_gait", "通用");

    companion object {
        fun fromCode(raw: String?): AnalysisMode =
            values().firstOrNull { it.code.equals(raw, ignoreCase = true) } ?: LongJump
    }
}

enum class FootSide(val code: String, val label: String) {
    Left("L", "左脚"),
    Right("R", "右脚");

    companion object {
        fun fromCode(raw: String?): FootSide? =
            values().firstOrNull { it.code.equals(raw, ignoreCase = true) }
    }
}

data class AthleteProfile(
    val id: String,
    val code: String,
    val name: String,
    val gender: String,
    val birthDate: String,
    val heightCm: Double,
    val weightKg: Double,
    val groupName: String,
    val dominantLeg: FootSide?,
    val extraJson: String,
) {
    companion object {
        fun fromEntity(entity: AthleteEntity): AthleteProfile {
            val extra = runCatching { JSONObject(entity.extra) }.getOrElse { JSONObject() }
            return AthleteProfile(
                id = entity.athleteId,
                code = entity.athleteCode,
                name = entity.name,
                gender = entity.gender,
                birthDate = entity.birthDate,
                heightCm = entity.heightCm,
                weightKg = entity.weightKg,
                groupName = entity.groupName,
                dominantLeg = when (extra.optString("dominant_leg").lowercase(Locale.US)) {
                    "left", "l" -> FootSide.Left
                    "right", "r" -> FootSide.Right
                    else -> null
                },
                extraJson = entity.extra,
            )
        }
    }
}

data class CapturedAttempt(
    val key: String,
    val capturedAt: Date,
    val leftPath: String?,
    val rightPath: String?,
) {
    val dateKey: String
        get() = DATE_FORMAT.format(capturedAt)

    val dateLabel: String
        get() = DATE_LABEL_FORMAT.format(capturedAt)

    val timeLabel: String
        get() = TIME_FORMAT.format(capturedAt)

    fun preferredPath(dominantLeg: FootSide?): String? =
        when (dominantLeg) {
            FootSide.Right -> rightPath ?: leftPath
            FootSide.Left -> leftPath ?: rightPath
            null -> leftPath ?: rightPath
        }

    fun sourcePaths(): List<String> = listOfNotNull(leftPath, rightPath).distinct()

    companion object {
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        private val DATE_LABEL_FORMAT = SimpleDateFormat("yyyy年MM月dd日", Locale.CHINA)
        private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.US)
    }
}

data class GaitSummary(
    val strideCount: Int,
    val velocityMps: Double?,
    val strideLengthM: Double?,
    val stepFrequencySpm: Double?,
    val contactTimeMs: Double?,
    val doubleSupportTimeMs: Double?,
    val flightTimeMs: Double?,
    val swingTimeMs: Double?,
    val vgrfPeakBw: Double?,
    val durationS: Double?,
    val sampleRateHz: Double?,
    val takeoffStep: Int,
    val takeoffAutoApplied: Boolean,
    val takeoffMessage: String,
)

data class GaitStride(
    val side: FootSide?,
    val toTimestampMs: Double?,
    val velocityMps: Double?,
    val strideLengthM: Double?,
    val stepFrequencySpm: Double?,
    val contactTimeMs: Double?,
    val doubleSupportTimeMs: Double?,
    val flightTimeMs: Double?,
    val swingTimeMs: Double?,
    val vgrfPeakBw: Double?,
)

data class SignalSeries(
    val timestampsMs: List<Double>,
    val gyroY: List<Double>,
)

data class GaitEvents(
    val ic: List<Double>,
    val tc: List<Double>,
    val ms: List<Double>,
    val msw: List<Double>,
)

data class SideSignalResult(
    val side: FootSide?,
    val signal: SignalSeries?,
    val events: GaitEvents,
)

data class AnalysisResult(
    val mode: AnalysisMode,
    val summary: GaitSummary,
    val strides: List<GaitStride>,
    val primarySignal: SideSignalResult?,
    val secondarySignal: SideSignalResult?,
    val rawJson: String,
)

data class AnalysisHistoryItem(
    val id: Long,
    val athleteId: String,
    val attemptNo: String,
    val sourcePath: String,
    val manifestPath: String,
    val trainingDate: String,
    val createdAt: String,
    val capturedAt: Date?,
    val hasLeft: Boolean,
    val hasRight: Boolean,
    val mode: AnalysisMode,
) {
    val timeLabel: String
        get() = capturedAt?.let { SimpleDateFormat("HH:mm:ss", Locale.US).format(it) }
            ?: createdAt.substringAfter('T').take(8).ifBlank { attemptNo }

    companion object {
        fun fromEntity(entity: AnalysisRecordEntity): AnalysisHistoryItem {
            val mainFile = File(entity.sourceFilePath)
            val directory = mainFile.parentFile
            val stamp = captureStamp(mainFile.name)
            val relatedNames = directory
                ?.listFiles { file -> file.isFile && file.name.endsWith(".csv", ignoreCase = true) }
                ?.filter { captureStamp(it.name) == stamp }
                ?.map { it.name.uppercase(Locale.US) }
                .orEmpty()
            val mainName = mainFile.name.uppercase(Locale.US)
            val allNames = relatedNames + mainName
            return AnalysisHistoryItem(
                id = entity.id,
                athleteId = entity.athleteId,
                attemptNo = entity.attemptNo,
                sourcePath = entity.sourceFilePath,
                manifestPath = entity.manifestPath,
                trainingDate = entity.trainingDate,
                createdAt = entity.createdAt,
                capturedAt = parseCaptureDate(mainFile.name),
                hasLeft = allNames.any { it.contains("D422CD007E6E") },
                hasRight = allNames.any { it.contains("D422CD00937F") },
                mode = AnalysisMode.fromCode(entity.analysisMode),
            )
        }
    }
}

data class LanShareUiConfig(
    val enabled: Boolean = false,
    val host: String = "",
    val shareName: String = "",
    val remoteDir: String = "",
    val username: String = "",
    val password: String = "",
    val domain: String = "",
    val uploadSourceCsv: Boolean = true,
)

data class AnalysisUiState(
    val section: AnalysisSection = AnalysisSection.Main,
    val analysisMode: AnalysisMode = AnalysisMode.LongJump,
    val athletes: List<AthleteProfile> = emptyList(),
    val selectedAthleteId: String? = null,
    val attemptNo: String = "R001",
    val attempts: List<CapturedAttempt> = emptyList(),
    val selectedAttemptKey: String? = null,
    val rangeStartS: String = "",
    val rangeEndS: String = "",
    val isEngineWarming: Boolean = true,
    val isAnalyzing: Boolean = false,
    val result: AnalysisResult? = null,
    val manifestPath: String = "",
    val statusMessage: String = "",
    val errorMessage: String = "",
    val history: List<AnalysisHistoryItem> = emptyList(),
    val historyAthleteId: String? = null,
    val selectedHistoryDate: String? = null,
    val lanConfig: LanShareUiConfig = LanShareUiConfig(),
    val isLanBusy: Boolean = false,
    val lanMessage: String = "",
) {
    val selectedAthlete: AthleteProfile?
        get() = athletes.firstOrNull { it.id == selectedAthleteId }

    val selectedAttempt: CapturedAttempt?
        get() = attempts.firstOrNull { it.key == selectedAttemptKey }

    val canAnalyze: Boolean
        get() = selectedAthlete != null && selectedAttempt != null && !isAnalyzing
}

fun parseAnalysisResult(rawJson: String): Result<AnalysisResult> = runCatching {
    val root = JSONObject(rawJson)
    if (!root.optBoolean("ok", false)) {
        error(root.optString("error", "分析失败"))
    }
    val summaryJson = root.optJSONObject("summary") ?: error("分析结果缺少 summary")
    val mode = AnalysisMode.fromCode(
        root.optString("analysis_mode").ifBlank { summaryJson.optString("analysis_mode") }
    )
    val contra = root.optJSONObject("contra_data")
    val primarySide = FootSide.fromCode(contra?.optString("side_main"))
    val secondarySide = FootSide.fromCode(contra?.optString("side_contra"))
    val primaryStrides = parseStrides(root.optJSONArray("strides"), primarySide)
    val secondaryStrides = parseStrides(contra?.optJSONArray("strides"), secondarySide)
    if (primaryStrides.isEmpty() && secondaryStrides.isEmpty()) {
        error(
            if (mode == AnalysisMode.LongJump) {
                "未检测到有效助跑步态，请检查设备佩戴、数据内容或重新选择时间范围"
            } else {
                "未检测到有效步态，请检查设备佩戴、数据内容或重新选择时间范围"
            }
        )
    }

    AnalysisResult(
        mode = mode,
        summary = GaitSummary(
            strideCount = summaryJson.optInt("n_strides", primaryStrides.size + secondaryStrides.size),
            velocityMps = summaryJson.finiteDouble("stride_velocity_mps"),
            strideLengthM = summaryJson.finiteDouble("stride_length_m"),
            stepFrequencySpm = summaryJson.metricDouble(
                canonicalKey = "step_frequency_spm",
                legacyKey = "step_frequency_hz",
                legacyScale = 60.0,
            ),
            contactTimeMs = summaryJson.metricDouble(
                canonicalKey = "contact_time_ms",
                legacyKey = "contact_time_s",
                legacyScale = 1000.0,
            ),
            doubleSupportTimeMs = summaryJson.metricDouble(
                canonicalKey = "double_support_time_ms",
                legacyKey = "double_support_time_s",
                legacyScale = 1000.0,
            ),
            flightTimeMs = summaryJson.finiteDouble("flight_time_ms"),
            swingTimeMs = summaryJson.metricDouble(
                canonicalKey = "swing_time_ms",
                legacyKey = "swing_time_s",
                legacyScale = 1000.0,
            ),
            vgrfPeakBw = summaryJson.finiteDouble("vGRF_peak_BW"),
            durationS = summaryJson.finiteDouble("duration_s"),
            sampleRateHz = summaryJson.finiteDouble("source_sample_rate_hz"),
            takeoffStep = summaryJson.optInt("long_jump_takeoff_step", -1),
            takeoffAutoApplied = summaryJson.optBoolean("long_jump_takeoff_auto_applied", false),
            takeoffMessage = summaryJson.optString("long_jump_takeoff_auto_message"),
        ),
        strides = (primaryStrides + secondaryStrides).sortedBy { it.toTimestampMs ?: Double.MAX_VALUE },
        primarySignal = parseSideSignal(
            root.optJSONObject("signals"),
            root.optJSONObject("events"),
            primarySide,
        ),
        secondarySignal = parseSideSignal(
            contra?.optJSONObject("signals"),
            contra?.optJSONObject("events"),
            secondarySide,
        ),
        rawJson = rawJson,
    )
}

private fun parseStrides(array: JSONArray?, side: FootSide?): List<GaitStride> {
    if (array == null) return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            add(
                GaitStride(
                    side = side,
                    toTimestampMs = item.finiteDouble("to_timestamp_ms"),
                    velocityMps = item.finiteDouble("stride_velocity_mps"),
                    strideLengthM = item.finiteDouble("stride_length_m"),
                    stepFrequencySpm = item.metricDouble(
                        canonicalKey = "step_frequency_spm",
                        legacyKey = "step_frequency_hz",
                        legacyScale = 60.0,
                    ),
                    contactTimeMs = item.metricDouble(
                        canonicalKey = "contact_time_ms",
                        legacyKey = "contact_time_s",
                        legacyScale = 1000.0,
                    ),
                    doubleSupportTimeMs = item.metricDouble(
                        canonicalKey = "double_support_time_ms",
                        legacyKey = "double_support_time_s",
                        legacyScale = 1000.0,
                    ),
                    flightTimeMs = item.metricDouble(
                        canonicalKey = "flight_time_ms",
                        legacyKey = "flight_time_s",
                        legacyScale = 1000.0,
                    ),
                    swingTimeMs = item.metricDouble(
                        canonicalKey = "swing_time_ms",
                        legacyKey = "swing_time_s",
                        legacyScale = 1000.0,
                    ),
                    vgrfPeakBw = item.finiteDouble("vGRF_peak_BW"),
                )
            )
        }
    }
}

private fun parseSideSignal(
    signals: JSONObject?,
    events: JSONObject?,
    side: FootSide?,
): SideSignalResult? {
    if (signals == null) return null
    val timestamps = signals.doubleList("timestamps")
    val gyroY = signals.doubleList("gyro_y")
    if (timestamps.isEmpty() || gyroY.isEmpty()) return null
    return SideSignalResult(
        side = side,
        signal = SignalSeries(timestamps, gyroY),
        events = GaitEvents(
            ic = events?.doubleList("hs").orEmpty(),
            tc = events?.doubleList("to").orEmpty(),
            ms = events?.doubleList("ms").orEmpty(),
            msw = events?.doubleList("msw").orEmpty(),
        ),
    )
}

private fun JSONObject.doubleList(key: String): List<Double> {
    val array = optJSONArray(key) ?: return emptyList()
    return buildList(array.length()) {
        for (index in 0 until array.length()) {
            val value = array.optDouble(index, Double.NaN)
            if (value.isFinite()) add(value)
        }
    }
}

private fun JSONObject.finiteDouble(key: String): Double? {
    if (isNull(key)) return null
    return optDouble(key, Double.NaN).takeIf { it.isFinite() }
}

private fun JSONObject.metricDouble(
    canonicalKey: String,
    legacyKey: String,
    legacyScale: Double,
): Double? = finiteDouble(canonicalKey) ?: finiteDouble(legacyKey)?.times(legacyScale)

fun captureStamp(name: String): String? =
    Regex("(20\\d{6})_(\\d{6})").find(name)?.let { "${it.groupValues[1]}_${it.groupValues[2]}" }

fun parseCaptureDate(name: String): Date? {
    val stamp = captureStamp(name) ?: return null
    return runCatching {
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(stamp)
    }.getOrNull()
}
