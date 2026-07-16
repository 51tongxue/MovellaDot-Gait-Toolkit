package com.buct.xsens.dot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncCommandReadinessTest {
    @Test
    fun firstConnectionRequiresInitDone() {
        assertFalse(
            isSyncCommandReady(
                isConnected = true,
                initializedThisConnection = false,
                initializedBefore = false,
                connectedStableMs = 10_000L,
                reconnectStableRequirementMs = 1_500L,
            )
        )
    }

    @Test
    fun initDoneMakesConnectedDeviceReadyImmediately() {
        assertTrue(
            isSyncCommandReady(
                isConnected = true,
                initializedThisConnection = true,
                initializedBefore = true,
                connectedStableMs = 0L,
                reconnectStableRequirementMs = 1_500L,
            )
        )
    }

    @Test
    fun previouslyInitializedReconnectMustRemainConnectedBeforeFallbackReady() {
        assertFalse(
            isSyncCommandReady(
                isConnected = true,
                initializedThisConnection = false,
                initializedBefore = true,
                connectedStableMs = 1_499L,
                reconnectStableRequirementMs = 1_500L,
            )
        )
        assertTrue(
            isSyncCommandReady(
                isConnected = true,
                initializedThisConnection = false,
                initializedBefore = true,
                connectedStableMs = 1_500L,
                reconnectStableRequirementMs = 1_500L,
            )
        )
    }

    @Test
    fun disconnectedDeviceIsNeverReady() {
        assertFalse(
            isSyncCommandReady(
                isConnected = false,
                initializedThisConnection = true,
                initializedBefore = true,
                connectedStableMs = 10_000L,
                reconnectStableRequirementMs = 1_500L,
            )
        )
    }

    @Test
    fun recordingManagerSetupIsBlockedForSyncReconnects() {
        assertFalse(
            shouldSetupRecordingManagerAfterConnection(
                isSyncing = true,
                newlyConnectedAddresses = setOf("D422CD007E6E", "D422CD00937F"),
                syncTargetAddresses = setOf("D422CD007E6E", "D422CD00937F"),
            )
        )
        assertFalse(
            shouldSetupRecordingManagerAfterConnection(
                isSyncing = false,
                newlyConnectedAddresses = setOf("D422CD007E6E"),
                syncTargetAddresses = setOf("D422CD007E6E", "D422CD00937F"),
            )
        )
    }

    @Test
    fun recordingManagerSetupContinuesForNormalConnections() {
        assertTrue(
            shouldSetupRecordingManagerAfterConnection(
                isSyncing = false,
                newlyConnectedAddresses = setOf("D422CD008569"),
                syncTargetAddresses = setOf("D422CD007E6E", "D422CD00937F"),
            )
        )
    }
}
