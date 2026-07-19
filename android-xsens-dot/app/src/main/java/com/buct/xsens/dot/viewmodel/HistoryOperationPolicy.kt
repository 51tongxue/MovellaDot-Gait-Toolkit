package com.buct.xsens.dot.viewmodel

import com.buct.xsens.dot.engine.FileInfoReadStatus
import com.buct.xsens.dot.engine.FlashRecordingPhase
import com.buct.xsens.dot.engine.hasFreshFiles

internal fun isHistoryFileSelectionAllowed(
    address: String,
    connectedAddresses: Set<String>,
    readStatus: FileInfoReadStatus?,
    recordingPhase: FlashRecordingPhase?,
): Boolean =
    address in connectedAddresses &&
        readStatus?.hasFreshFiles == true &&
        recordingPhase !in setOf(
            FlashRecordingPhase.Starting,
            FlashRecordingPhase.Recording,
            FlashRecordingPhase.Stopping,
        )

internal fun isParticipantHistoryOperationBusy(
    queued: Boolean,
    loading: Boolean,
    activeReadTargets: Set<String>,
    participantTargets: Set<String>,
): Boolean =
    queued ||
        loading ||
        participantTargets.any { it in activeReadTargets }
