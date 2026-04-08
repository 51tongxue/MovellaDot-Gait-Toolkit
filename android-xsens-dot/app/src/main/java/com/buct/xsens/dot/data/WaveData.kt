package com.buct.xsens.dot.data

/** 波形环形缓冲，保留最近 WAVE_LEN 个采样点，使用 ArrayDeque 保证 O(1) 头部删除 */
class WaveData {
    companion object { const val WAVE_LEN = 120 }

    private val t = ArrayDeque<Double>(WAVE_LEN + 1)
    private val accX = ArrayDeque<Float>(WAVE_LEN + 1)
    private val accY = ArrayDeque<Float>(WAVE_LEN + 1)
    private val accZ = ArrayDeque<Float>(WAVE_LEN + 1)
    private val gyroX = ArrayDeque<Float>(WAVE_LEN + 1)
    private val gyroY = ArrayDeque<Float>(WAVE_LEN + 1)
    private val gyroZ = ArrayDeque<Float>(WAVE_LEN + 1)

    @Synchronized
    fun push(timeSec: Double, acc: FloatArray, gyro: FloatArray?) {
        val a = if (acc.size >= 3) acc else floatArrayOf(0f, 0f, 0f)
        val g = if (gyro != null && gyro.size >= 3) gyro else floatArrayOf(0f, 0f, 0f)
        t.addLast(timeSec)
        accX.addLast(a[0]); accY.addLast(a[1]); accZ.addLast(a[2])
        gyroX.addLast(g[0]); gyroY.addLast(g[1]); gyroZ.addLast(g[2])
        if (t.size > WAVE_LEN) {
            t.removeFirst(); accX.removeFirst(); accY.removeFirst(); accZ.removeFirst()
            gyroX.removeFirst(); gyroY.removeFirst(); gyroZ.removeFirst()
        }
    }

    @Synchronized
    fun snapshot(): WaveSnapshot = WaveSnapshot(
        t = t.toList(),
        accX = accX.toList(), accY = accY.toList(), accZ = accZ.toList(),
        gyroX = gyroX.toList(), gyroY = gyroY.toList(), gyroZ = gyroZ.toList()
    )
}

data class WaveSnapshot(
    val t: List<Double>,
    val accX: List<Float>, val accY: List<Float>, val accZ: List<Float>,
    val gyroX: List<Float>, val gyroY: List<Float>, val gyroZ: List<Float>,
)
