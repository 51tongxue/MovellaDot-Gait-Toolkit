package com.buct.xsens.dot.data

data class SensorData(
    val sensorId: Int,
    val address: String,
    val timestamp: Double,          // 硬件 SampleTimeFine（传感器内部计数器）
    val packetCounter: Int = -1,    // 传感器单调递增帧序号（0-65535 循环），用于去重和丢包检测
    val euler: FloatArray? = null,  // roll, pitch, yaw (deg)，Custom Mode 1 有效
    val acc: FloatArray? = null,    // freeAcc x, y, z (m/s²)，Custom Mode 1 提供
    val gyro: FloatArray? = null,   // x, y, z (deg/s)，Custom Mode 1 提供
    val mag: FloatArray? = null     // x, y, z，Mode 2/3 有效
) {
    // timestamp（SampleTimeFine）每帧唯一，确保 Compose 检测到数据变化并触发重组
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SensorData
        return timestamp == other.timestamp && address == other.address
    }

    override fun hashCode(): Int {
        var result = address.hashCode()
        result = 31 * result + timestamp.hashCode()
        return result
    }
}
