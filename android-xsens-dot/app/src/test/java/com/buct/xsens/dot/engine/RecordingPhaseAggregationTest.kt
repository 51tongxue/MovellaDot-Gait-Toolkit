package com.buct.xsens.dot.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingPhaseAggregationTest {
    @Test
    fun stoppedGroupDoesNotEndAnotherGroupsRecording() {
        val phases = mapOf(
            "athlete-1-left" to FlashRecordingPhase.Idle,
            "athlete-1-right" to FlashRecordingPhase.Idle,
            "athlete-2-left" to FlashRecordingPhase.Recording,
            "athlete-2-right" to FlashRecordingPhase.Recording,
        )

        assertEquals(
            FlashRecordingPhase.Recording,
            aggregateFlashRecordingPhase(
                devicePhases = phases.values,
                hasActiveRecordingDevices = true,
            ),
        )
    }

    @Test
    fun allGroupsIdleOnlyAfterEveryGroupHasStopped() {
        assertEquals(
            FlashRecordingPhase.Idle,
            aggregateFlashRecordingPhase(
                devicePhases = List(6) { FlashRecordingPhase.Idle },
                hasActiveRecordingDevices = false,
            ),
        )
    }

    @Test
    fun startingAndStoppingCommandsRemainVisibleDuringStaggeredRecording() {
        assertEquals(
            FlashRecordingPhase.Starting,
            aggregateFlashRecordingPhase(
                devicePhases = listOf(
                    FlashRecordingPhase.Recording,
                    FlashRecordingPhase.Starting,
                ),
                hasActiveRecordingDevices = true,
            ),
        )
        assertEquals(
            FlashRecordingPhase.Stopping,
            aggregateFlashRecordingPhase(
                devicePhases = listOf(
                    FlashRecordingPhase.Recording,
                    FlashRecordingPhase.Stopping,
                ),
                hasActiveRecordingDevices = true,
            ),
        )
    }

    @Test
    fun devicesWithoutRecordingStateDoNotBlockInitialConnection() {
        assertEquals(
            false,
            participantHasActiveRecordingOperation(
                devicePhases = emptyMap(),
                targetAddresses = setOf("left", "right"),
            ),
        )
    }

    @Test
    fun onlyTheTargetParticipantRecordingStateBlocksItsOwnOperations() {
        val phases = mapOf(
            "athlete-1-left" to FlashRecordingPhase.Recording,
            "athlete-1-right" to FlashRecordingPhase.Recording,
            "athlete-2-left" to FlashRecordingPhase.Idle,
            "athlete-2-right" to FlashRecordingPhase.Idle,
        )

        assertEquals(
            false,
            participantHasActiveRecordingOperation(
                devicePhases = phases,
                targetAddresses = setOf("athlete-2-left", "athlete-2-right"),
            ),
        )
        assertEquals(
            true,
            participantHasActiveRecordingOperation(
                devicePhases = phases,
                targetAddresses = setOf("athlete-1-left", "athlete-1-right"),
            ),
        )
    }

    @Test
    fun reconnectDoesNotRewriteRecordingNotificationWhileFlashRecordingIsActive() {
        assertEquals(
            true,
            shouldDeferRecordingNotification(
                phase = FlashRecordingPhase.Recording,
                isKnownActiveRecording = false,
            ),
        )
        assertEquals(
            true,
            shouldDeferRecordingNotification(
                phase = FlashRecordingPhase.Stopping,
                isKnownActiveRecording = false,
            ),
        )
        assertEquals(
            true,
            shouldDeferRecordingNotification(
                phase = FlashRecordingPhase.Idle,
                isKnownActiveRecording = true,
            ),
        )
        assertEquals(
            false,
            shouldDeferRecordingNotification(
                phase = FlashRecordingPhase.Idle,
                isKnownActiveRecording = false,
            ),
        )
    }
}
