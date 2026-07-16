package com.buct.xsens.dot.ui.screens

import com.buct.xsens.dot.engine.FlashRecordingPhase
import org.junit.Assert.assertEquals
import org.junit.Test

class ParticipantPrimaryActionTest {
    @Test
    fun recordingTakesPriorityWhenSdkSyncFlagIsLostAfterReconnect() {
        assertEquals(
            ParticipantPrimaryAction.Stop,
            resolveParticipantPrimaryAction(
                groupSynced = false,
                groupPreparing = false,
                groupPhase = FlashRecordingPhase.Recording,
            ),
        )
    }

    @Test
    fun weakOrDisconnectedRecordingStillAllowsReliableStopRequest() {
        assertEquals(
            ParticipantPrimaryAction.Stop,
            resolveParticipantPrimaryAction(
                groupSynced = false,
                groupPreparing = false,
                groupPhase = FlashRecordingPhase.Recording,
            ),
        )
    }

    @Test
    fun idleUnsyncedGroupStillOffersSync() {
        assertEquals(
            ParticipantPrimaryAction.Sync,
            resolveParticipantPrimaryAction(
                groupSynced = false,
                groupPreparing = false,
                groupPhase = FlashRecordingPhase.Idle,
            ),
        )
    }

    @Test
    fun stoppingStatusExplainsWhyConfirmationIsStillPending() {
        assertEquals(
            "停止中",
            resolveStoppingStatusText(
                waitingReconnect = false,
                confirmationDelayed = false,
            ),
        )
        assertEquals(
            "停止待回连",
            resolveStoppingStatusText(
                waitingReconnect = true,
                confirmationDelayed = true,
            ),
        )
        assertEquals(
            "停止未确认 · 自动重试中",
            resolveStoppingStatusText(
                waitingReconnect = false,
                confirmationDelayed = true,
            ),
        )
    }

    @Test
    fun stopRequiresBothDevicesConnectedWithStableRecordingSignal() {
        assertEquals(
            true,
            canSafelyRequestStop(
                groupPhase = FlashRecordingPhase.Recording,
                connectedCount = 2,
                targetCount = 2,
                unsafeToStop = false,
            ),
        )
        assertEquals(
            false,
            canSafelyRequestStop(
                groupPhase = FlashRecordingPhase.Recording,
                connectedCount = 2,
                targetCount = 2,
                unsafeToStop = true,
            ),
        )
        assertEquals(
            false,
            canSafelyRequestStop(
                groupPhase = FlashRecordingPhase.Recording,
                connectedCount = 1,
                targetCount = 2,
                unsafeToStop = false,
            ),
        )
    }

    @Test
    fun restoredRecordingCanStopWithoutTransientSdkStateObject() {
        assertEquals(
            true,
            canSafelyRequestStop(
                groupPhase = FlashRecordingPhase.Recording,
                connectedCount = 2,
                targetCount = 2,
                unsafeToStop = false,
            ),
        )
    }
}
