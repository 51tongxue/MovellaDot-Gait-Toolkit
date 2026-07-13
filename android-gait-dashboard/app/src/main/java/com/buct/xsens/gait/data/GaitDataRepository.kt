package com.buct.xsens.gait.data

import android.content.Context
import android.os.Environment
import com.buct.xsens.dot.data.LongJumpDeviceRoles
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GaitDataRepository(context: Context) {
    private val appContext = context.applicationContext
    private val dao = GaitDatabase.getInstance(appContext).gaitDataDao()
    private val lanShareUploader = LanShareUploader(appContext)
    private val docsXsens = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
        "XsensData",
    )

    private data class TimestampAnchor(
        val utcMs: Long,
        val source: String,
    )

    fun listAthletes(): List<AthleteEntity> = dao.getAthletes()

    fun saveAthlete(athlete: AthleteEntity) {
        dao.upsertAthletes(listOf(athlete))
    }

    fun listAnalysisRecords(athleteId: String): List<AnalysisRecordEntity> {
        return deduplicateAnalysisRecords(dao.getAnalysisRecords(athleteId))
    }

    fun cleanupDuplicateAnalysisRecords() {
        dao.getAllAnalysisRecords()
            .groupBy(AnalysisRecordEntity::athleteId)
            .values
            .forEach(::deduplicateAnalysisRecords)
    }

    fun deleteAnalysisRecord(recordId: Long) {
        dao.deleteAnalysisRecord(recordId)
    }

    fun loadSavedAnalysisJson(manifestPath: String): String {
        val manifestFile = File(manifestPath)
        val cacheFile = analysisCacheFile(manifestFile)
        if (cacheFile.isFile) {
            return cacheFile.readText(Charsets.UTF_8)
        }
        check(manifestFile.isFile) { "历史结果文件不存在" }
        return buildAnalysisJsonFromManifest(JSONObject(manifestFile.readText(Charsets.UTF_8))).toString()
    }

    fun importAthletesJson(content: String): String {
        return try {
            val root = JSONObject(content)
            val schemaVersion = root.optString("schema_version")
            if (schemaVersion != ATHLETE_SCHEMA) {
                return errorJson("不支持的运动员 JSON schema: $schemaVersion")
            }
            val athletesJson = root.optJSONArray("athletes")
                ?: return errorJson("运动员 JSON 缺少 athletes 数组")

            val orgJson = root.optJSONObject("organization")
            if (orgJson != null) {
                dao.upsertOrganization(
                    OrganizationEntity(
                        organizationId = orgJson.optString("organization_id", "org-local"),
                        name = orgJson.optString("name", "本地训练场"),
                        schemaVersion = schemaVersion,
                        generatedAt = root.optString("generated_at"),
                    )
                )
            }

            val athletes = mutableListOf<AthleteEntity>()
            for (i in 0 until athletesJson.length()) {
                val item = athletesJson.optJSONObject(i) ?: continue
                val athleteId = item.optString("athlete_id").trim()
                if (athleteId.isEmpty()) continue
                athletes += AthleteEntity(
                    athleteId = athleteId,
                    athleteCode = item.optString("athlete_code"),
                    name = item.optString("name"),
                    gender = item.optString("gender"),
                    birthDate = item.optString("birth_date"),
                    heightCm = item.optDouble("height_cm", 0.0),
                    weightKg = item.optDouble("weight_kg", 0.0),
                    groupName = item.optString("group_name"),
                    extra = item.optJSONObject("extra")?.toString() ?: "{}",
                )
            }
            if (athletes.isEmpty()) {
                return errorJson("运动员 JSON 中没有有效 athlete_id")
            }
            dao.upsertAthletes(athletes)
            JSONObject()
                .put("ok", true)
                .put("count", athletes.size)
                .toString()
        } catch (e: Exception) {
            errorJson(e.message ?: "导入运动员失败")
        }
    }

    fun getAthletesJson(): String {
        return try {
            val root = JSONObject()
                .put("ok", true)
                .put("schema_version", ATHLETE_SCHEMA)

            val org = dao.getOrganization()
            if (org != null) {
                root.put(
                    "organization",
                    JSONObject()
                        .put("organization_id", org.organizationId)
                        .put("name", org.name)
                )
            }

            val athletes = JSONArray()
            dao.getAthletes().forEach { athlete ->
                athletes.put(
                    JSONObject()
                        .put("athlete_id", athlete.athleteId)
                        .put("athlete_code", athlete.athleteCode)
                        .put("name", athlete.name)
                        .put("gender", athlete.gender)
                        .put("birth_date", athlete.birthDate)
                        .put("height_cm", athlete.heightCm)
                        .put("weight_kg", athlete.weightKg)
                        .put("group_name", athlete.groupName)
                        .put("extra", parseJsonObjectOrEmpty(athlete.extra))
                )
            }
            root.put("athletes", athletes).toString()
        } catch (e: Exception) {
            errorJson(e.message ?: "读取运动员失败")
        }
    }

    fun saveAthleteJson(content: String): String {
        return try {
            val item = JSONObject(content)
            val name = item.optString("name").trim()
            if (name.isEmpty()) {
                return errorJson("请填写运动员姓名")
            }
            val athleteId = item.optString("athlete_id").trim()
                .ifEmpty { "ath-local-${formatDate(Date(), "yyyyMMddHHmmssSSS")}" }
            val athlete = AthleteEntity(
                athleteId = athleteId,
                athleteCode = item.optString("athlete_code").trim().ifEmpty { athleteId },
                name = name,
                gender = item.optString("gender").trim(),
                birthDate = item.optString("birth_date").trim(),
                heightCm = item.optDouble("height_cm", 0.0),
                weightKg = item.optDouble("weight_kg", 0.0),
                groupName = item.optString("group_name").trim(),
                extra = item.optJSONObject("extra")?.toString() ?: "{}",
            )
            dao.upsertAthletes(listOf(athlete))
            JSONObject()
                .put("ok", true)
                .put("athlete_id", athlete.athleteId)
                .put("athlete", JSONObject()
                    .put("athlete_id", athlete.athleteId)
                    .put("athlete_code", athlete.athleteCode)
                    .put("name", athlete.name)
                    .put("gender", athlete.gender)
                    .put("birth_date", athlete.birthDate)
                    .put("height_cm", athlete.heightCm)
                    .put("weight_kg", athlete.weightKg)
                    .put("group_name", athlete.groupName)
                    .put("extra", parseJsonObjectOrEmpty(athlete.extra))
                )
                .toString()
        } catch (e: Exception) {
            errorJson(e.message ?: "保存运动员失败")
        }
    }

    fun getNextAttemptNo(athleteId: String): String {
        val date = formatDate(Date(), "yyyy-MM-dd")
        return nextAttemptNoForDate(athleteId, date)
    }

    fun getAttemptNoForCapture(athleteId: String, sourceFilePath: String): String {
        val records = dao.getAnalysisRecords(athleteId)
        return records
            .firstOrNull { isSameCapture(it.sourceFilePath, sourceFilePath) }
            ?.attemptNo
            ?: nextAttemptNoForDate(
                athleteId,
                formatDate(parseCaptureDate(sourceFilePath) ?: Date(), "yyyy-MM-dd"),
                records,
            )
    }

    fun saveImuManifest(
        athleteId: String,
        attemptNoRaw: String,
        sourceFilePath: String,
        analysisJson: String,
    ): String {
        return try {
            val athlete = dao.getAthlete(athleteId)
                ?: return errorJson("未找到运动员: $athleteId")
            val attemptNo = normalizeAttemptNo(attemptNoRaw)
            val capturedDate = parseCaptureDate(sourceFilePath) ?: Date()
            val trainingDate = formatDate(capturedDate, "yyyy-MM-dd")
            val attemptIndex = attemptNo.drop(1).toIntOrNull()
                ?: (uniqueCaptureRecords(dao.getAnalysisRecords(athleteId), trainingDate).size + 1)
            val analysis = JSONObject(analysisJson)
            if (!analysis.optBoolean("ok", false)) {
                return errorJson("分析结果无效，未保存 manifest")
            }
            val analysisMode = analysis.optString("analysis_mode", "long_jump")
                .takeIf { it == "long_jump" || it == "general_gait" }
                ?: "long_jump"

            val sourceFilePaths = resolveSourceFilePaths(sourceFilePath, analysis)
            val capturedStamp = formatDate(capturedDate, "yyyyMMdd_HHmmss")
            val capturedAt = formatDate(capturedDate, "yyyy-MM-dd'T'HH:mm:ssXXX")
            val sourceName = sourceFilePath.substringAfterLast('/').ifBlank { "uploaded.csv" }
            val timestampAnchor = resolveTimestampAnchor(sourceFilePath, capturedDate)
            val analysisWindow = buildAnalysisWindow(analysis, timestampAnchor)

            val records = JSONArray()
            val recordModeSuffix = if (analysisMode == "long_jump") "" else "_${analysisMode}"
            sourceFilePaths.forEach { rawPath ->
                val rawName = rawPath.substringAfterLast('/').ifBlank { "uploaded.csv" }
                val rawSide = if (sourceFilePaths.size > 1) inferSide(rawName) else null
                records.put(buildRawRecord(capturedStamp, athlete, attemptNo, attemptIndex, capturedAt, rawName, rawPath, analysisWindow, rawSide, recordModeSuffix))
            }

            val gaitRecords = mutableListOf<Pair<String, JSONObject>>()
            val mainSide = analysis.optJSONObject("contra_data")?.optString("side_main")?.ifBlank { null }
                ?: inferSide(sourceName)
            gaitRecords += mainSide to buildGaitRecord(capturedStamp, athlete, attemptNo, attemptIndex, capturedAt, mainSide, analysis.optJSONArray("strides"), analysisWindow, timestampAnchor, recordModeSuffix, analysisMode)

            val contra = analysis.optJSONObject("contra_data")
            if (contra != null) {
                val contraSide = contra.optString("side_contra").ifBlank { oppositeSide(mainSide) }
                gaitRecords += contraSide to buildGaitRecord(capturedStamp, athlete, attemptNo, attemptIndex, capturedAt, contraSide, contra.optJSONArray("strides"), analysisWindow, timestampAnchor, recordModeSuffix, analysisMode)
            }
            gaitRecords
                .distinctBy { it.first }
                .sortedBy { if (it.first == "L") 0 else 1 }
                .forEach { records.put(it.second) }

            val org = dao.getOrganization()
            val manifest = JSONObject()
                .put("schema_version", MANIFEST_SCHEMA)
                .put("analysis_mode", analysisMode)
                .put(
                    "system",
                    JSONObject()
                        .put("system_code", "imu")
                        .put("system_name", "IMU系统")
                )
                .put(
                    "session",
                    JSONObject()
                        .put("session_code", "S${formatDate(capturedDate, "yyyyMMdd")}-${if (formatDate(capturedDate, "HH").toInt() < 12) "AM" else "PM"}")
                        .put("training_date", trainingDate)
                        .put("venue", org?.name ?: "本地训练场")
                )
                .put("records", records)

            val outDir = File(docsXsens, "gait_manifest")
            outDir.mkdirs()
            val modeSuffix = if (analysisMode == "long_jump") "" else "_$analysisMode"
            val outFile = File(
                outDir,
                "${capturedStamp}_imu_manifest_${athlete.athleteId}_${attemptNo}${modeSuffix}.json",
            )
            outFile.writeText(manifest.toString(2), Charsets.UTF_8)
            val analysisCacheFile = analysisCacheFile(outFile)
            analysisCacheFile.parentFile?.mkdirs()
            analysisCacheFile.writeText(analysis.toString(), Charsets.UTF_8)

            dao.getAnalysisRecords(athlete.athleteId)
                .filter {
                    it.analysisMode == analysisMode &&
                        isSameCapture(it.sourceFilePath, sourceFilePath)
                }
                .forEach { existing ->
                    if (existing.manifestPath != outFile.absolutePath) {
                        File(existing.manifestPath).takeIf(File::isFile)?.delete()
                        analysisCacheFile(File(existing.manifestPath)).takeIf(File::isFile)?.delete()
                    }
                    dao.deleteAnalysisRecord(existing.id)
                }
            dao.insertAnalysisRecord(
                AnalysisRecordEntity(
                    athleteId = athlete.athleteId,
                    attemptNo = attemptNo,
                    athleteAttemptNo = attemptIndex,
                    sourceFilePath = sourceFilePath,
                    manifestPath = outFile.absolutePath,
                    createdAt = formatDate(Date(), "yyyy-MM-dd'T'HH:mm:ssXXX"),
                    trainingDate = trainingDate,
                    analysisMode = analysisMode,
                )
            )
            val lanUpload = JSONObject(lanShareUploader.uploadAnalysisFiles(outFile.absolutePath, sourceFilePaths))

            JSONObject()
                .put("ok", true)
                .put("path", outFile.absolutePath)
                .put("manifest", manifest)
                .put("lan_upload", lanUpload)
                .toString()
        } catch (e: Exception) {
            errorJson(e.message ?: "保存 manifest 失败")
        }
    }

    fun getLanUploadConfigJson(): String = lanShareUploader.getConfigJson()

    fun saveLanUploadConfigJson(content: String): String = lanShareUploader.saveConfigJson(content)

    fun testLanUploadConnection(): String = lanShareUploader.testConnection()

    fun uploadAnalysisToLan(manifestPath: String, sourceFilePath: String): String =
        lanShareUploader.uploadAnalysisFiles(manifestPath, parseSourcePathArgument(sourceFilePath))

    private fun buildRawRecord(
        capturedStamp: String,
        athlete: AthleteEntity,
        attemptNo: String,
        attemptIndex: Int,
        capturedAt: String,
        sourceName: String,
        sourceFilePath: String,
        analysisWindow: JSONObject?,
        side: String? = null,
        recordModeSuffix: String,
    ): JSONObject =
        JSONObject()
            .put("record_id", "imu_${capturedStamp}_${athlete.athleteId}_${attemptNo}${recordModeSuffix}_raw${side?.let { "_$it" } ?: ""}")
            .put("athlete_id", athlete.athleteId)
            .put("attempt_no", attemptNo)
            .put("athlete_attempt_no", attemptIndex)
            .put("captured_at", capturedAt)
            .put("data_type", "imu_raw_timeseries")
            .put("data_class", "type_1_csv")
            .put("device_code", extractDeviceCode(sourceName) ?: JSONObject.NULL)
            .apply {
                side?.let { put("side", it) }
            }
            .put(
                "file",
                JSONObject()
                    .put("file_name", sourceName)
                    .put("uri", toLocalUri(sourceFilePath))
                    .put("format", "csv")
            )
            .apply {
                analysisWindow?.let { put("analysis_window", JSONObject(it.toString())) }
            }

    private fun buildGaitRecord(
        capturedStamp: String,
        athlete: AthleteEntity,
        attemptNo: String,
        attemptIndex: Int,
        capturedAt: String,
        side: String,
        strides: JSONArray?,
        analysisWindow: JSONObject?,
        timestampAnchor: TimestampAnchor?,
        recordModeSuffix: String,
        analysisMode: String,
    ): JSONObject =
        JSONObject()
            .put("record_id", "imu_${capturedStamp}_${athlete.athleteId}_${attemptNo}${recordModeSuffix}_gait_${side}")
            .put("athlete_id", athlete.athleteId)
            .put("attempt_no", attemptNo)
            .put("athlete_attempt_no", attemptIndex)
            .put("captured_at", capturedAt)
            .put("data_type", if (side == "R") "gait_metrics_right" else "gait_metrics_left")
            .put("data_class", "type_2_json")
            .put("side", side)
            .put("metrics", mapMetrics(strides, timestampAnchor, analysisMode))
            .apply {
                analysisWindow?.let { put("analysis_window", JSONObject(it.toString())) }
            }

    private fun buildAnalysisWindow(analysis: JSONObject, timestampAnchor: TimestampAnchor?): JSONObject? {
        val range = analysis.optJSONObject("timeRange") ?: return null
        val hasStart = !range.isNull("start")
        val hasEnd = !range.isNull("end")
        if (!hasStart && !hasEnd) return null
        val startTimeS = range.optNullableDouble("start")
        val endTimeS = range.optNullableDouble("end")

        val window = JSONObject()
            .putNullableFinite("start_time_s", startTimeS)
            .putNullableFinite("end_time_s", endTimeS)
            .put("timestamp_unit", "ms")
            .put("timestamp_origin", "recording_start")

        if (timestampAnchor != null) {
            window
                .put("timestamp_anchor_utc_ms", timestampAnchor.utcMs)
                .put("timestamp_anchor_source", timestampAnchor.source)
            startTimeS?.let { window.put("start_timestamp", timestampAnchor.utcMs + (it * 1000.0).toLong()) }
            endTimeS?.let { window.put("end_timestamp", timestampAnchor.utcMs + (it * 1000.0).toLong()) }
        }

        val summary = analysis.optJSONObject("summary")
        if (summary != null) {
            window.putNullableFinite("duration_s", summary.optNullableDouble("duration_s"))
            window.putNullableFinite("source_sample_rate_hz", summary.optNullableDouble("source_sample_rate_hz"))
            if (!summary.isNull("long_jump_takeoff_step")) {
                window.put("long_jump_takeoff_step", summary.optInt("long_jump_takeoff_step"))
            }
            if (!summary.isNull("long_jump_to_filter_applied")) {
                window.put("long_jump_to_filter_applied", summary.optBoolean("long_jump_to_filter_applied"))
            }
        }
        window.put("analysis_mode", analysis.optString("analysis_mode", "long_jump"))
        return window
    }

    private fun mapMetrics(
        strides: JSONArray?,
        timestampAnchor: TimestampAnchor?,
        analysisMode: String,
    ): JSONArray {
        val metrics = JSONArray()
        if (strides == null) return metrics
        for (i in 0 until strides.length()) {
            val stride = strides.optJSONObject(i) ?: continue
            val toTimestampRelativeMs = stride.optLong("to_timestamp_ms")
            val toTimestamp = timestampAnchor?.let { it.utcMs + toTimestampRelativeMs } ?: toTimestampRelativeMs
            val metric = JSONObject()
                .put("to_timestamp", toTimestamp)
                .putNullableMetric("average_velocity_mps", stride.finiteDouble("stride_velocity_mps"))
                .putNullableMetric("stride_length_m", stride.finiteDouble("stride_length_m"))
                .putNullableMetric(
                    "step_frequency_spm",
                    stride.metricDouble(
                        canonicalKey = "step_frequency_spm",
                        legacyKey = "step_frequency_hz",
                        legacyScale = 60.0,
                    ),
                )
                .putNullableMetric("stride_time_s", stride.finiteDouble("stride_time_s"))
                .putNullableMetric(
                    "contact_time_s",
                    stride.metricDouble(
                        canonicalKey = "contact_time_ms",
                        legacyKey = "contact_time_s",
                        canonicalScale = 0.001,
                    ),
                )
                .putNullableMetric(
                    "flight_time_s",
                    stride.metricDouble(
                        canonicalKey = "flight_time_ms",
                        legacyKey = "flight_time_s",
                        canonicalScale = 0.001,
                    ),
                )
                .putNullableMetric(
                    "swing_time_s",
                    stride.metricDouble(
                        canonicalKey = "swing_time_ms",
                        legacyKey = "swing_time_s",
                        canonicalScale = 0.001,
                    ),
                )
                .putNullableMetric("vgrf_peak_bw", stride.finiteDouble("vGRF_peak_BW"))
            if (analysisMode == "general_gait") {
                metric.putNullableMetric(
                    "double_support_time_s",
                    stride.metricDouble(
                        canonicalKey = "double_support_time_ms",
                        legacyKey = "double_support_time_s",
                        canonicalScale = 0.001,
                    ),
                )
            }
            metrics.put(metric)
        }
        return metrics
    }

    private fun JSONObject.finiteDouble(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        return optDouble(key, Double.NaN).takeIf { it.isFinite() }
    }

    private fun JSONObject.metricDouble(
        canonicalKey: String,
        legacyKey: String,
        canonicalScale: Double = 1.0,
        legacyScale: Double = 1.0,
    ): Double? {
        return if (has(canonicalKey) && !isNull(canonicalKey)) {
            finiteDouble(canonicalKey)?.times(canonicalScale)
        } else {
            finiteDouble(legacyKey)?.times(legacyScale)
        }
    }

    private fun JSONObject.putNullableMetric(key: String, value: Double?): JSONObject =
        put(key, value ?: JSONObject.NULL)

    private fun analysisCacheFile(manifestFile: File): File =
        File(
            File(docsXsens, "gait_result_cache"),
            "${manifestFile.nameWithoutExtension}.analysis.json",
        )

    private fun buildAnalysisJsonFromManifest(manifest: JSONObject): JSONObject {
        val analysisMode = manifest.optString("analysis_mode", "long_jump")
        val records = manifest.optJSONArray("records") ?: JSONArray()
        val gaitRecords = buildList {
            for (index in 0 until records.length()) {
                val record = records.optJSONObject(index) ?: continue
                val side = record.optString("side").uppercase(Locale.US)
                if (side !in setOf("L", "R")) continue
                val metrics = record.optJSONArray("metrics") ?: continue
                add(side to metrics)
            }
        }.distinctBy { it.first }

        check(gaitRecords.isNotEmpty()) { "历史 manifest 中没有步态指标" }
        val primary = gaitRecords.first()
        val secondary = gaitRecords.getOrNull(1)
        val allMetrics = gaitRecords.flatMap { (_, metrics) ->
            buildList {
                for (index in 0 until metrics.length()) {
                    metrics.optJSONObject(index)?.let(::add)
                }
            }
        }

        fun average(key: String, scale: Double = 1.0): Double? {
            val values = allMetrics.mapNotNull { it.finiteDouble(key) }
            return values.takeIf(List<Double>::isNotEmpty)?.average()?.times(scale)
        }

        fun toStrides(metrics: JSONArray): JSONArray {
            val result = JSONArray()
            for (index in 0 until metrics.length()) {
                val metric = metrics.optJSONObject(index) ?: continue
                result.put(
                    JSONObject()
                        .putNullableMetric("to_timestamp_ms", metric.finiteDouble("to_timestamp"))
                        .putNullableMetric("stride_velocity_mps", metric.finiteDouble("average_velocity_mps"))
                        .putNullableMetric("stride_length_m", metric.finiteDouble("stride_length_m"))
                        .putNullableMetric("step_frequency_spm", metric.finiteDouble("step_frequency_spm"))
                        .putNullableMetric("stride_time_s", metric.finiteDouble("stride_time_s"))
                        .putNullableMetric("contact_time_ms", metric.finiteDouble("contact_time_s")?.times(1000.0))
                        .putNullableMetric(
                            "double_support_time_ms",
                            metric.finiteDouble("double_support_time_s")?.times(1000.0),
                        )
                        .putNullableMetric("flight_time_ms", metric.finiteDouble("flight_time_s")?.times(1000.0))
                        .putNullableMetric("swing_time_ms", metric.finiteDouble("swing_time_s")?.times(1000.0))
                        .putNullableMetric("vGRF_peak_BW", metric.finiteDouble("vgrf_peak_bw"))
                )
            }
            return result
        }

        val timestamps = allMetrics.mapNotNull { it.finiteDouble("to_timestamp") }
        val summary = JSONObject()
            .put("analysis_mode", analysisMode)
            .put("n_strides", allMetrics.size)
            .putNullableMetric("stride_velocity_mps", average("average_velocity_mps"))
            .putNullableMetric("stride_length_m", average("stride_length_m"))
            .putNullableMetric("step_frequency_spm", average("step_frequency_spm"))
            .putNullableMetric("contact_time_ms", average("contact_time_s", 1000.0))
            .putNullableMetric(
                "double_support_time_ms",
                average("double_support_time_s", 1000.0),
            )
            .putNullableMetric("flight_time_ms", average("flight_time_s", 1000.0))
            .putNullableMetric("swing_time_ms", average("swing_time_s", 1000.0))
            .putNullableMetric("vGRF_peak_BW", average("vgrf_peak_bw"))
            .putNullableMetric(
                "duration_s",
                if (timestamps.size >= 2) (timestamps.max() - timestamps.min()) / 1000.0 else null,
            )

        return JSONObject()
            .put("ok", true)
            .put("analysis_mode", analysisMode)
            .put("summary", summary)
            .put("strides", toStrides(primary.second))
            .apply {
                secondary?.let { (side, metrics) ->
                    put(
                        "contra_data",
                        JSONObject()
                            .put("side_main", primary.first)
                            .put("side_contra", side)
                            .put("strides", toStrides(metrics)),
                    )
                }
            }
    }

    private fun resolveTimestampAnchor(sourceFilePath: String, capturedDate: Date): TimestampAnchor? {
        val metadata = readCsvMetadata(sourceFilePath)
        metadata["record_start_ack_utc_ms"]?.toLongOrNull()?.let {
            return TimestampAnchor(it, "record_start_ack_utc_ms")
        }
        return TimestampAnchor(capturedDate.time, "filename_capture_time")
    }

    private fun resolveSourceFilePaths(primaryPath: String, analysis: JSONObject): List<String> {
        val paths = linkedSetOf<String>()
        primaryPath.trim().takeIf { it.isNotBlank() }?.let { paths += it }
        val array = analysis.optJSONArray("source_file_paths")
        if (array != null) {
            for (i in 0 until array.length()) {
                array.optString(i).trim().takeIf { it.isNotBlank() }?.let { paths += it }
            }
        }
        return paths.toList()
    }

    private fun parseSourcePathArgument(raw: String): List<String> {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return emptyList()
        if (!trimmed.startsWith("[")) return listOf(trimmed)
        return try {
            val array = JSONArray(trimmed)
            buildList {
                for (i in 0 until array.length()) {
                    array.optString(i).trim().takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
        } catch (_: Exception) {
            listOf(trimmed)
        }
    }

    private fun readCsvMetadata(sourceFilePath: String): Map<String, String> {
        val file = File(sourceFilePath)
        if (!file.exists() || !file.isFile) return emptyMap()
        val metadata = linkedMapOf<String, String>()
        return try {
            file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.take(50).forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) return@forEach
                    val parts = trimmed.split(',', limit = 2)
                    if (parts.any { it == "PacketCounter" || it == "SampleTimeFine" }) {
                        return@useLines metadata
                    }
                    if (parts.size == 2) {
                        metadata[parts[0].trim().lowercase(Locale.US)] = parts[1].trim()
                    }
                }
                metadata
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun normalizeAttemptNo(raw: String): String {
        val trimmed = raw.trim().uppercase(Locale.US)
        val digits = trimmed.filter { it.isDigit() }
        return if (digits.isNotEmpty()) "R%03d".format(digits.toInt()) else "R001"
    }

    private fun parseCaptureDate(source: String): Date? {
        val match = Regex("(20\\d{6})_(\\d{6})").find(source) ?: return null
        return SimpleDateFormat("yyyyMMddHHmmss", Locale.US).parse(match.groupValues[1] + match.groupValues[2])
    }

    private fun isSameCapture(firstPath: String, secondPath: String): Boolean {
        val first = parseCaptureDate(firstPath)?.time
        val second = parseCaptureDate(secondPath)?.time
        if (first != null && second != null) {
            return kotlin.math.abs(first - second) <= CAPTURE_MATCH_TOLERANCE_MS
        }
        return File(firstPath).canonicalPath == File(secondPath).canonicalPath
    }

    private fun deduplicateAnalysisRecords(
        records: List<AnalysisRecordEntity>,
    ): List<AnalysisRecordEntity> {
        val unique = mutableListOf<AnalysisRecordEntity>()
        records.forEach { record ->
            val retained = unique.firstOrNull {
                it.analysisMode == record.analysisMode &&
                    isSameCapture(it.sourceFilePath, record.sourceFilePath)
            }
            if (retained == null) {
                unique += record
            } else {
                if (record.manifestPath != retained.manifestPath) {
                    File(record.manifestPath).takeIf(File::isFile)?.delete()
                    analysisCacheFile(File(record.manifestPath)).takeIf(File::isFile)?.delete()
                }
                dao.deleteAnalysisRecord(record.id)
            }
        }
        return unique
    }

    private fun uniqueCaptureRecords(
        records: List<AnalysisRecordEntity>,
        trainingDate: String,
    ): List<AnalysisRecordEntity> {
        val unique = mutableListOf<AnalysisRecordEntity>()
        records
            .filter { it.trainingDate == trainingDate }
            .forEach { record ->
                if (unique.none { isSameCapture(it.sourceFilePath, record.sourceFilePath) }) {
                    unique += record
                }
            }
        return unique
    }

    private fun nextAttemptNoForDate(
        athleteId: String,
        trainingDate: String,
        records: List<AnalysisRecordEntity> = dao.getAnalysisRecords(athleteId),
    ): String {
        val count = uniqueCaptureRecords(records, trainingDate).size
        return "R%03d".format(count + 1)
    }

    private fun extractDeviceCode(name: String): String? {
        Regex("DOT_([0-9A-Fa-f]{12})").find(name)?.let { return it.groupValues[1].uppercase(Locale.US) }
        Regex("^([0-9A-Fa-f]{12})_20\\d{6}").find(name)?.let { return it.groupValues[1].uppercase(Locale.US) }
        return null
    }

    private fun inferSide(name: String): String =
        LongJumpDeviceRoles.sideCode(extractDeviceCode(name) ?: name)
            ?: when {
                name.contains("_L_", ignoreCase = true) || name.contains("LEFT", ignoreCase = true) -> "L"
                name.contains("_R_", ignoreCase = true) || name.contains("RIGHT", ignoreCase = true) -> "R"
                else -> "L"
            }

    private fun oppositeSide(side: String): String = if (side == "R") "L" else "R"

    private fun toLocalUri(path: String): String {
        if (path.isBlank()) return ""
        val file = File(path)
        return if (file.isAbsolute) file.toURI().toString() else path
    }

    private fun formatDate(date: Date, pattern: String): String =
        SimpleDateFormat(pattern, Locale.US).format(date)

    private fun parseJsonObjectOrEmpty(raw: String): JSONObject =
        try {
            JSONObject(raw)
        } catch (_: Exception) {
            JSONObject()
        }

    private fun JSONObject.optNullableDouble(key: String): Double? {
        if (isNull(key)) return null
        val value = optDouble(key, Double.NaN)
        return if (value.isFinite()) value else null
    }

    private fun JSONObject.putNullableFinite(key: String, value: Double?): JSONObject {
        if (value != null && value.isFinite()) {
            put(key, value)
        } else {
            put(key, JSONObject.NULL)
        }
        return this
    }

    private fun errorJson(message: String): String =
        JSONObject()
            .put("ok", false)
            .put("error", message)
            .toString()

    companion object {
        private const val CAPTURE_MATCH_TOLERANCE_MS = 5_000L
        private const val ATHLETE_SCHEMA = "longjump-athletes/v1"
        private const val MANIFEST_SCHEMA = "longjump-data-manifest/v1"
    }
}
