package com.buct.xsens.gait.engine

import android.content.Context
import com.buct.xsens.dot.data.LongJumpDeviceRoles
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import java.io.File

class GaitAnalysisManager(private val context: Context) {
    companion object {
        private val analysisLock = Any()
    }

    @Synchronized
    fun warmUp() {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context))
        }
    }

    fun analyzeGait(
        filePath: String,
        weightStr: String,
        startTimeStr: String,
        endTimeStr: String,
        takeoffStepStr: String,
        takeoffFootStr: String,
        isTripleJumpStr: String,
    ): String {
        val weight = weightStr.toDoubleOrNull() ?: 75.0
        val startTimeS = startTimeStr.toDoubleOrNull() ?: -1.0
        val endTimeS = endTimeStr.toDoubleOrNull() ?: -1.0
        val takeoffStep = takeoffStepStr.toIntOrNull() ?: -1
        val isTakeoffFoot: Any = if (takeoffFootStr == "L" || takeoffFootStr == "R") takeoffFootStr else (takeoffFootStr != "0" && takeoffFootStr.lowercase() != "false")
        val isTripleJump: Any = if (isTripleJumpStr == "L" || isTripleJumpStr == "R") isTripleJumpStr else (isTripleJumpStr == "1" || isTripleJumpStr.lowercase() == "true")

        android.util.Log.d(
            "GaitAnalysis",
            "Analyzing file: $filePath, weight: $weight, start: $startTimeS, end: $endTimeS, " +
                "takeoffStep: $takeoffStep, isTakeoffFoot: $isTakeoffFoot, isTripleJump: $isTripleJump",
        )
        return synchronized(analysisLock) {
            val startedAt = android.os.SystemClock.elapsedRealtime()
            try {
                warmUp()
                val py = Python.getInstance()
                val module = py.getModule("gait_analyzer")
                val roles = LongJumpDeviceRoles.currentConfig
                val sourceName = File(filePath).name.uppercase()
                val participant = roles.participants.firstOrNull {
                    sourceName.contains(it.leftDeviceId) || sourceName.contains(it.rightDeviceId)
                } ?: roles.participants.first()
                val result = module.callAttr(
                    "process_gait_data",
                    filePath,
                    weight,
                    startTimeS,
                    endTimeS,
                    takeoffStep,
                    isTakeoffFoot,
                    isTripleJump,
                    participant.leftDeviceId,
                    participant.rightDeviceId,
                )
                val jsonResult = result.toString()
                val elapsedMs =
                    android.os.SystemClock.elapsedRealtime() - startedAt
                android.util.Log.d(
                    "GaitAnalysis",
                    "Analyzed success in ${elapsedMs}ms, result length: ${jsonResult.length}",
                )
                jsonResult
            } catch (e: Exception) {
                android.util.Log.e("GaitAnalysis", "Python error: ${e.message}", e)
                JSONObject().apply {
                    put("ok", false)
                    put("error", e.message ?: "Unknown Python error")
                }.toString()
            }
        }
    }
}
