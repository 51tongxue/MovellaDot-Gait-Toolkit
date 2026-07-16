package com.buct.xsens.dot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingExportQueueTest {

    @Test
    fun stoppedGroupCanPrepareExportWithoutWaitingForOtherRecordings() {
        assertTrue(
            canStartNextRecordingExportPreparation(
                hasActivePreparation = false,
                hasExportTransfer = false,
                queuedSessionCount = 1,
            )
        )
    }

    @Test
    fun preparationRemainsSerializedWithFileReadAndExportTransfer() {
        assertFalse(
            canStartNextRecordingExportPreparation(
                hasActivePreparation = true,
                hasExportTransfer = false,
                queuedSessionCount = 2,
            )
        )
        assertFalse(
            canStartNextRecordingExportPreparation(
                hasActivePreparation = false,
                hasExportTransfer = true,
                queuedSessionCount = 2,
            )
        )
    }

    @Test
    fun exportPausesRssiOnlyForItsTargetDevices() {
        assertFalse(
            shouldPollRssiForDevice(
                isConnected = true,
                backgroundReadsPaused = false,
                isSyncing = false,
                isExportTarget = true,
            )
        )
        assertTrue(
            shouldPollRssiForDevice(
                isConnected = true,
                backgroundReadsPaused = false,
                isSyncing = false,
                isExportTarget = false,
            )
        )
    }

    @Test
    fun syncStillPausesAllRssiReads() {
        assertFalse(
            shouldPollRssiForDevice(
                isConnected = true,
                backgroundReadsPaused = true,
                isSyncing = true,
                isExportTarget = false,
            )
        )
    }
}
