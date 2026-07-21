package com.buct.xsens.dot.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

class ParticipantSyncQueueTest {
    @Test
    fun groupsRemainInClickOrder() {
        val first = enqueueParticipantSyncSlot(
            queuedSlotIds = emptyList(),
            activeSlotId = "participant-1",
            requestedSlotId = "participant-2",
        )
        val second = enqueueParticipantSyncSlot(
            queuedSlotIds = first,
            activeSlotId = "participant-1",
            requestedSlotId = "participant-3",
        )

        assertEquals(listOf("participant-2", "participant-3"), second)
    }

    @Test
    fun duplicateOrActiveGroupIsNotQueuedAgain() {
        val queued = listOf("participant-2")

        assertEquals(
            queued,
            enqueueParticipantSyncSlot(
                queuedSlotIds = queued,
                activeSlotId = "participant-1",
                requestedSlotId = "participant-2",
            ),
        )
        assertEquals(
            queued,
            enqueueParticipantSyncSlot(
                queuedSlotIds = queued,
                activeSlotId = "participant-1",
                requestedSlotId = "participant-1",
            ),
        )
    }

    @Test
    fun cancellingOneGroupPreservesTheRemainingOrder() {
        assertEquals(
            listOf("participant-2", "participant-3"),
            cancelQueuedParticipantSyncSlot(
                queuedSlotIds = listOf(
                    "participant-1",
                    "participant-2",
                    "participant-3",
                ),
                slotId = "participant-1",
            ),
        )
    }
}
