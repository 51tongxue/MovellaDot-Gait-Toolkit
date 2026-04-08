package com.buct.xsens.dot.data

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CSV 录制器（实时流写入）
 * 列：SampleTimeFine,roll,pitch,yaw,freeAccX,freeAccY,freeAccZ,gyroX,gyroY,gyroZ
 *
 * 内置丢包检测：通过 packetCounter（0-65535 循环）检测跳变，
 * 停止录制时用 Log.w 输出统计，可通过 `adb logcat -s CsvRecorder:W` 监控。
 */
class CsvRecorder(
    private val context: Context,
    private val sensorId: Int,
    private val address: String
) {
    private var writer: FileWriter? = null
    private var filePath: String? = null

    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    private var lastPacketCounter: Int = -1
    private var rowCount: Int = 0
    private var droppedCount: Int = 0

    fun start(): String {
        val macStr = address.replace(":", "").replace("-", "").uppercase()
        val timestamp = dateFormat.format(Date())
        val fileName = "Xsens DOT_${macStr}_${timestamp}.csv"
        val header = "SampleTimeFine,roll,pitch,yaw,freeAccX,freeAccY,freeAccZ,gyroX,gyroY,gyroZ\n"

        // 主存储：Documents/XsensData/data_logging（可被其他 App 读取）
        val publicDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "XsensData/data_logging"
        )
        publicDir.mkdirs()
        val publicFile = File(publicDir, fileName)

        // 备用存储：App 私有目录（总是可写）
        val privateDir = File(context.getExternalFilesDir(null), "data_logging")
        privateDir.mkdirs()

        val targetFile = if (publicDir.canWrite()) publicFile else File(privateDir, fileName)
        filePath = targetFile.absolutePath
        rowCount = 0
        droppedCount = 0
        lastPacketCounter = -1
        writer = FileWriter(targetFile, true)
        writer!!.write(header)
        writer!!.flush()
        return filePath!!
    }

    fun write(data: SensorData) {
        try {
            val w = writer ?: return
            val pc = data.packetCounter

            // 丢包检测：packetCounter 是 0-65535 循环计数器
            if (pc != -1 && lastPacketCounter != -1) {
                val expected = (lastPacketCounter + 1) and 0xFFFF
                if (pc != expected) {
                    val lost = ((pc - lastPacketCounter - 1) and 0xFFFF).coerceAtMost(200)
                    droppedCount += lost
                    Log.w("CsvRecorder", "[$address] 丢包 $lost 帧  pktCnt $lastPacketCounter → $pc")
                }
            }
            if (pc != -1) lastPacketCounter = pc

            val roll  = data.euler?.getOrNull(0) ?: 0f
            val pitch = data.euler?.getOrNull(1) ?: 0f
            val yaw   = data.euler?.getOrNull(2) ?: 0f
            val accX  = data.acc?.getOrNull(0)   ?: 0f
            val accY  = data.acc?.getOrNull(1)   ?: 0f
            val accZ  = data.acc?.getOrNull(2)   ?: 0f
            val gyrX  = data.gyro?.getOrNull(0)  ?: 0f
            val gyrY  = data.gyro?.getOrNull(1)  ?: 0f
            val gyrZ  = data.gyro?.getOrNull(2)  ?: 0f
            w.write("${data.timestamp.toLong()},$roll,$pitch,$yaw,$accX,$accY,$accZ,$gyrX,$gyrY,$gyrZ\n")
            rowCount++
        } catch (e: Exception) {
            // ignore stream closed exceptions
        }
    }

    fun flush() { try { writer?.flush() } catch (e: Exception) {} }

    fun stop() {
        try {
            writer?.flush()
            writer?.close()
        } catch (e: Exception) {}
        writer = null
        val total = rowCount + droppedCount
        val lossRate = if (total > 0) droppedCount * 100f / total else 0f
        if (droppedCount > 0) {
            Log.w("CsvRecorder", "[$address] 录制结束：收到 $rowCount 帧，丢包 $droppedCount 帧，丢包率 ${"%.1f".format(lossRate)}%")
        } else {
            Log.w("CsvRecorder", "[$address] 录制结束：收到 $rowCount 帧，零丢包 ✓")
        }
    }

    fun getFilePath(): String? = filePath
}
