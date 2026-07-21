package com.buct.xsens.dot.viewmodel

internal fun enqueueParticipantSyncSlot(
    queuedSlotIds: List<String>,
    activeSlotId: String?,
    requestedSlotId: String,
): List<String> =
    if (requestedSlotId == activeSlotId || requestedSlotId in queuedSlotIds) {
        queuedSlotIds
    } else {
        queuedSlotIds + requestedSlotId
    }

internal fun cancelQueuedParticipantSyncSlot(
    queuedSlotIds: List<String>,
    slotId: String,
): List<String> = queuedSlotIds.filterNot { it == slotId }
