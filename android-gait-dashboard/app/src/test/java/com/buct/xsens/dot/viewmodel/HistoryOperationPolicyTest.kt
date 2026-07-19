package com.buct.xsens.dot.viewmodel

import com.buct.xsens.dot.engine.FileInfoReadPhase
import com.buct.xsens.dot.engine.FileInfoReadStatus
import com.buct.xsens.dot.engine.FlashRecordingPhase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryOperationPolicyTest {
    @Test
    fun staleCachedFilesAreNeverSelectable() {
        val connected = setOf("LEFT")

        assertFalse(
            isHistoryFileSelectionAllowed(
                address = "LEFT",
                connectedAddresses = connected,
                readStatus = FileInfoReadStatus(FileInfoReadPhase.Failed),
                recordingPhase = FlashRecordingPhase.Idle,
            ),
        )
        assertFalse(
            isHistoryFileSelectionAllowed(
                address = "LEFT",
                connectedAddresses = connected,
                readStatus = FileInfoReadStatus(FileInfoReadPhase.Idle),
                recordingPhase = FlashRecordingPhase.Idle,
            ),
        )
    }

    @Test
    fun readyFilesRequireConnectedIdleDevice() {
        val ready = FileInfoReadStatus(FileInfoReadPhase.Ready)

        assertTrue(
            isHistoryFileSelectionAllowed(
                address = "LEFT",
                connectedAddresses = setOf("LEFT"),
                readStatus = ready,
                recordingPhase = FlashRecordingPhase.Idle,
            ),
        )
        assertFalse(
            isHistoryFileSelectionAllowed(
                address = "LEFT",
                connectedAddresses = emptySet(),
                readStatus = ready,
                recordingPhase = FlashRecordingPhase.Idle,
            ),
        )
        assertFalse(
            isHistoryFileSelectionAllowed(
                address = "LEFT",
                connectedAddresses = setOf("LEFT"),
                readStatus = ready,
                recordingPhase = FlashRecordingPhase.Recording,
            ),
        )
    }

    @Test
    fun engineActivityKeepsGroupLockedAfterUiLoadingFlagClears() {
        assertTrue(
            isParticipantHistoryOperationBusy(
                queued = false,
                loading = false,
                activeReadTargets = setOf("RIGHT"),
                participantTargets = setOf("LEFT", "RIGHT"),
            ),
        )
        assertFalse(
            isParticipantHistoryOperationBusy(
                queued = false,
                loading = false,
                activeReadTargets = setOf("OTHER"),
                participantTargets = setOf("LEFT", "RIGHT"),
            ),
        )
    }
}
