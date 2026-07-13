package com.buct.xsens.gait.engine

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import android.webkit.JavascriptInterface
import org.json.JSONObject
import java.io.File

class GaitAnalysisManager(private val context: Context) {

    @Synchronized
    fun warmUp() {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context))
        }
    }

    /**
     * 调用 Python 脚本进行步态分析
     * @param csvPath CSV 文件绝对路径
     * @param weight 体重 (kg)
     * @return 分析结果的 JSON 字符串
     */
    @JavascriptInterface
    fun analyzeGait(filePath: String, weightStr: String): String {
        return analyzeGait(filePath, weightStr, "-1.0", "-1.0", "-1", "1", "0")
    }

    @JavascriptInterface
    fun analyzeGait(filePath: String, weightStr: String, startTimeStr: String, endTimeStr: String): String {
        return analyzeGait(filePath, weightStr, startTimeStr, endTimeStr, "-1", "1", "0")
    }

    @JavascriptInterface
    fun analyzeGait(
        filePath: String,
        weightStr: String,
        startTimeStr: String,
        endTimeStr: String,
        takeoffStepStr: String,
    ): String {
        return analyzeGait(filePath, weightStr, startTimeStr, endTimeStr, takeoffStepStr, "1", "0")
    }

    @JavascriptInterface
    fun analyzeGait(
        filePath: String,
        weightStr: String,
        startTimeStr: String,
        endTimeStr: String,
        takeoffStepStr: String,
        takeoffFootStr: String,
    ): String {
        return analyzeGait(filePath, weightStr, startTimeStr, endTimeStr, takeoffStepStr, takeoffFootStr, "0")
    }

    @JavascriptInterface
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
        return try {
            warmUp()
            val py = Python.getInstance()
            val module = py.getModule("gait_analyzer")
            val result = module.callAttr(
                "process_gait_data",
                filePath,
                weight,
                startTimeS,
                endTimeS,
                takeoffStep,
                isTakeoffFoot,
                isTripleJump,
            )
            val jsonResult = result.toString()
            android.util.Log.d("GaitAnalysis", "Analyzed success, result length: ${jsonResult.length}")
            jsonResult
        } catch (e: Exception) {
            android.util.Log.e("GaitAnalysis", "Python error: ${e.message}", e)
            JSONObject().apply {
                put("ok", false)
                put("error", e.message ?: "Unknown Python error")
            }.toString()
        }
    }

    /**
     * 直接分析 CSV 字符串内容
     */
    @JavascriptInterface
    fun analyzeGaitContent(content: String, weightStr: String): String {
        return analyzeGaitContent(content, weightStr, "-1.0", "-1.0", "-1", "1", "0")
    }

    @JavascriptInterface
    fun analyzeGaitContent(content: String, weightStr: String, startTimeStr: String, endTimeStr: String): String {
        return analyzeGaitContent(content, weightStr, startTimeStr, endTimeStr, "-1", "1", "0")
    }

    @JavascriptInterface
    fun analyzeGaitContent(
        content: String,
        weightStr: String,
        startTimeStr: String,
        endTimeStr: String,
        takeoffStepStr: String,
    ): String {
        return analyzeGaitContent(content, weightStr, startTimeStr, endTimeStr, takeoffStepStr, "1", "0")
    }

    @JavascriptInterface
    fun analyzeGaitContent(
        content: String,
        weightStr: String,
        startTimeStr: String,
        endTimeStr: String,
        takeoffStepStr: String,
        takeoffFootStr: String,
    ): String {
        return analyzeGaitContent(content, weightStr, startTimeStr, endTimeStr, takeoffStepStr, takeoffFootStr, "0")
    }

    @JavascriptInterface
    fun analyzeGaitContent(
        content: String,
        weightStr: String,
        startTimeStr: String,
        endTimeStr: String,
        takeoffStepStr: String,
        takeoffFootStr: String,
        isTripleJumpStr: String,
    ): String {
        android.util.Log.d("GaitAnalysis", "Analyzing content, length: ${content.length}")
        return try {
            val tempFile = File(context.cacheDir, "temp_upload.csv")
            tempFile.writeText(content)
            analyzeGait(tempFile.absolutePath, weightStr, startTimeStr, endTimeStr, takeoffStepStr, takeoffFootStr, isTripleJumpStr)
        } catch (e: Exception) {
            android.util.Log.e("GaitAnalysis", "Content analysis error: ${e.message}", e)
            JSONObject().apply {
                put("ok", false)
                put("error", "写入临时文件失败: ${e.message}")
            }.toString()
        }
    }
}
