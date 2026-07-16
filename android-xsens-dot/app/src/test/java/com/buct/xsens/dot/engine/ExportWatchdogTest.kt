package com.buct.xsens.dot.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class ExportWatchdogTest {

    @Test
    fun firstFrameCanTakeLongerThanOldFifteenSecondLimit() {
        assertEquals(
            ExportWatchdogAction.Wait,
            resolveExportWatchdogAction(
                nowMs = 20_000L,
                attemptStartedAtMs = 0L,
                lastProgressAtMs = 0L,
                hasReceivedData = false,
                isConnected = true,
                isResetting = false,
                isRestartScheduled = false,
                firstDataTimeoutMs = 45_000L,
                streamingStallTimeoutMs = 40_000L,
            ),
        )
    }

    @Test
    fun streamingPauseUsesLastFrameInsteadOfAttemptStart() {
        assertEquals(
            ExportWatchdogAction.Wait,
            resolveExportWatchdogAction(
                nowMs = 70_000L,
                attemptStartedAtMs = 0L,
                lastProgressAtMs = 45_000L,
                hasReceivedData = true,
                isConnected = true,
                isResetting = false,
                isRestartScheduled = false,
                firstDataTimeoutMs = 45_000L,
                streamingStallTimeoutMs = 40_000L,
            ),
        )
    }

    @Test
    fun disconnectedDeviceDoesNotConsumeRetry() {
        assertEquals(
            ExportWatchdogAction.Wait,
            resolveExportWatchdogAction(
                nowMs = 120_000L,
                attemptStartedAtMs = 0L,
                lastProgressAtMs = 10_000L,
                hasReceivedData = true,
                isConnected = false,
                isResetting = false,
                isRestartScheduled = false,
                firstDataTimeoutMs = 45_000L,
                streamingStallTimeoutMs = 40_000L,
            ),
        )
    }

    @Test
    fun stalledDeviceRetriesAfterStreamingTimeout() {
        assertEquals(
            ExportWatchdogAction.Retry,
            resolveExportWatchdogAction(
                nowMs = 90_000L,
                attemptStartedAtMs = 0L,
                lastProgressAtMs = 45_000L,
                hasReceivedData = true,
                isConnected = true,
                isResetting = false,
                isRestartScheduled = false,
                firstDataTimeoutMs = 45_000L,
                streamingStallTimeoutMs = 40_000L,
            ),
        )
    }

    @Test
    fun sampleProgressIncludesDroppedPacketSpanAndCounterWrap() {
        val tracker = ExportSampleProgressTracker()

        assertEquals(1, tracker.observe(253))
        assertEquals(3, tracker.observe(255))
        assertEquals(5, tracker.observe(1))
        assertEquals(8, tracker.observe(4))
    }
}
