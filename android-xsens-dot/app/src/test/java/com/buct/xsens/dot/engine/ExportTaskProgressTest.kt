package com.buct.xsens.dot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportTaskProgressTest {

    @Test
    fun flashSampleEstimateUsesStoredSampleSize() {
        assertTrue(estimatedFlashSampleCount(225_280) in 3_630..3_640)
        assertTrue(estimatedFlashSampleCount(901_120) in 14_530..14_540)
    }

    @Test
    fun recentRecordingEstimateUsesDurationAndOutputRate() {
        assertTrue(
            estimatedRecordedSampleCount(
                startUtcMs = 1_000L,
                stopUtcMs = 37_000L,
                outputRateHz = 120,
            ) in 4_319..4_321
        )
    }

    @Test
    fun unrelatedCompletedCallbackDoesNotFinishCurrentExport() {
        val progress = ExportTaskProgress(
            isExporting = true,
            totalFiles = 2,
            targetBytesByFile = mapOf(
                "LEFT-1" to 100L,
                "RIGHT-2" to 100L,
            ),
            completedFileKeys = setOf("OLD-8", "OLD-9"),
        )

        assertTrue(progress.hasPendingFiles)
        assertTrue(progress.completedTargetFileKeys.isEmpty())
    }

    @Test
    fun exportFinishesOnlyWhenEveryTargetFileIsResolved() {
        val oneComplete = ExportTaskProgress(
            isExporting = true,
            totalFiles = 2,
            targetBytesByFile = mapOf(
                "LEFT-1" to 100L,
                "RIGHT-2" to 100L,
            ),
            completedFileKeys = setOf("LEFT-1"),
        )
        val allResolved = oneComplete.copy(
            completedFileKeys = setOf("LEFT-1"),
            failedFileKeys = setOf("RIGHT-2"),
        )

        assertTrue(oneComplete.hasPendingFiles)
        assertFalse(allResolved.hasPendingFiles)
    }
}
