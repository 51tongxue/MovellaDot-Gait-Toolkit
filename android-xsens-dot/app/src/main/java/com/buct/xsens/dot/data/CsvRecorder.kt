package com.buct.xsens.dot.data

import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CSV 录制器（实时流写入）
 * 列：PacketCounter,timestamp_utc_ms,SampleTimeFine,roll,pitch,yaw,freeAccX,freeAccY,freeAccZ,gyroX,gyroY,gyroZ
 *
 * 内置丢包检测：通过 packetCounter（0-65535 循环）检测跳变，
 * 停止录制时用 Log.w 输出统计，可通过 `adb logcat -s CsvRecorder:W` 监控。
 */
class CsvRecorder(
    private val sensorId: Int,
    private val address: String
) {
    private var writer: FileWriter? = null
    private var filePath: String? = null

    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    private var lastPacketCounter: Int = -1
    private var rowCount: Int = 0
    private var droppedCount: Int = 0
    private var writeErrorLogged: Boolean = false
    private var timestampCalculator: TimestampUtcCalculator? = null

    fun start(): String {
        val macStr = address.replace(":", "").replace("-", "").uppercase()
        val now = Date()
        val timestamp = dateFormat.format(now)
        val timestampAnchorUtcMs = dateFormat.parse(timestamp)?.time ?: now.time
        val fileName = "Xsens DOT_${macStr}_${timestamp}.csv"
        val header = "PacketCounter,timestamp_utc_ms,SampleTimeFine,roll,pitch,yaw,freeAccX,freeAccY,freeAccZ,gyroX,gyroY,gyroZ\n"

        // 固定存储：Documents/XsensData/data_logging（可被分析页和外部系统读取）
        val publicDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "XsensData/data_logging"
        )
        ensurePublicDirWritable(publicDir)
        val publicFile = File(publicDir, fileName)

        filePath = publicFile.absolutePath
        rowCount = 0
        droppedCount = 0
        lastPacketCounter = -1
        writeErrorLogged = false
        timestampCalculator = TimestampUtcCalculator(timestampAnchorUtcMs)
        writer = FileWriter(publicFile, false)
        writer!!.write(header)
        writer!!.flush()
        return filePath!!
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
            val timestampUtcMs = timestampCalculator?.timestampUtcMs(data.timestamp, pc) ?: 0L
            w.write("$pc,$timestampUtcMs,${data.timestamp.toLong()},$roll,$pitch,$yaw,$accX,$accY,$accZ,$gyrX,$gyrY,$gyrZ\n")
            rowCount++
        } catch (e: Exception) {
            if (!writeErrorLogged) {
                writeErrorLogged = true
                Log.e("CsvRecorder", "[$address] CSV 写入失败: ${e.message}", e)
            }
        }
    }

    fun flush() {
        try {
            writer?.flush()
        } catch (e: Exception) {
            Log.e("CsvRecorder", "[$address] CSV flush 失败: ${e.message}", e)
        }
    }

    fun stop() {
        try {
            writer?.flush()
            writer?.close()
        } catch (e: Exception) {
            Log.e("CsvRecorder", "[$address] CSV 关闭失败: ${e.message}", e)
        }
        writer = null
        timestampCalculator = null
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
