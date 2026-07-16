package com.buct.xsens.dot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncStateStabilityTest {

    @Test
    fun refreshDoesNotErasePreviouslyConfirmedGroupSync() {
        assertTrue(
            mergeObservedSyncState(
                previouslyConfirmed = true,
                sdkReportsSynced = false,
            )
        )
        assertTrue(
            mergeObservedSyncState(
                previouslyConfirmed = false,
                sdkReportsSynced = true,
            )
        )
        assertFalse(
            mergeObservedSyncState(
                previouslyConfirmed = false,
                sdkReportsSynced = false,
            )
        )
    }

    @Test
    fun negativeStatusIsDeferredDuringRecordingAndReconnectStabilization() {
        assertTrue(
            shouldDeferNegativeSyncStatus(
                previouslyConfirmed = true,
                flashRecordingActive = true,
                reconnectPending = false,
                connectedStableMs = 20_000L,
                reconnectGuardMs = 8_000L,
            )
        )
        assertTrue(
            shouldDeferNegativeSyncStatus(
                previouslyConfirmed = true,
                flashRecordingActive = false,
                reconnectPending = true,
                connectedStableMs = 20_000L,
                reconnectGuardMs = 8_000L,
            )
        )
        assertTrue(
            shouldDeferNegativeSyncStatus(
                previouslyConfirmed = true,
                flashRecordingActive = false,
                reconnectPending = false,
                connectedStableMs = 2_000L,
                reconnectGuardMs = 8_000L,
            )
        )
        assertFalse(
            shouldDeferNegativeSyncStatus(
                previouslyConfirmed = true,
                flashRecordingActive = false,
                reconnectPending = false,
                connectedStableMs = 12_000L,
                reconnectGuardMs = 8_000L,
            )
        )
    }
}
