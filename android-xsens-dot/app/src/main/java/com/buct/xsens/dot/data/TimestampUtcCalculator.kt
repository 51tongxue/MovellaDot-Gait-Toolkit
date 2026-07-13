package com.buct.xsens.dot.data

import kotlin.math.abs
import kotlin.math.roundToLong

class TimestampUtcCalculator(
    private val anchorUtcMs: Long,
) {
    private var firstSampleTimeFine: Double? = null
    private var previousPacketCounter: Int = -1
    private var relativePacketCounter: Long = 0L
    private var sampleIntervalMs: Double? = null

    fun timestampUtcMs(sampleTimeFine: Double, packetCounter: Int): Long {
        val firstSample = firstSampleTimeFine
        if (firstSample == null) {
            firstSampleTimeFine = sampleTimeFine
            previousPacketCounter = packetCounter
            return anchorUtcMs
        }

        if (packetCounter >= 0 && previousPacketCounter >= 0) {
            var delta = packetCounter - previousPacketCounter
            if (delta < 0) delta += 65536
            relativePacketCounter += delta.toLong()
            previousPacketCounter = packetCounter
        }

        val interval = sampleIntervalMs
            ?: inferSampleIntervalMs(firstSample, sampleTimeFine, relativePacketCounter)
                ?.also { sampleIntervalMs = it }

        val relativeMs = if (interval != null && relativePacketCounter > 0L) {
            (relativePacketCounter * interval).roundToLong()
        } else {
            val sampleDelta = sampleTimeFine - firstSample
            (sampleDelta * inferSampleTimeScale(abs(sampleDelta))).roundToLong()
        }
        return anchorUtcMs + relativeMs
    }

    private fun inferSampleIntervalMs(
        firstSampleTimeFine: Double,
        sampleTimeFine: Double,
        frameDelta: Long,
    ): Double? {
        if (frameDelta <= 0L) return null
        val rawStep = abs(sampleTimeFine - firstSampleTimeFine) / frameDelta.toDouble()
        if (rawStep <= 0.0) return null
        return rawStep * inferSampleTimeScale(rawStep)
    }

    private fun inferSampleTimeScale(value: Double): Double =
        when {
            value > 500.0 -> 0.001
            value > 0.5 -> 1.0
            else -> 1000.0
        }
}
