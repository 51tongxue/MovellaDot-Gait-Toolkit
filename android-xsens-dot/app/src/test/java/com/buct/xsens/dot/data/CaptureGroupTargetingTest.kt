package com.buct.xsens.dot.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureGroupTargetingTest {
    @Test
    fun eachParticipantTargetsOnlyItsOwnLeftAndRightDevices() {
        val config = DeviceRoleConfig(
            participants = (1..3).map { index ->
                CaptureParticipantBinding(
                    slotId = "participant-$index",
                    athleteId = "athlete-$index",
                    athleteName = "运动员 $index",
                    leftDeviceId = "D4:22:CD:00:00:${index}1",
                    rightDeviceId = "D4:22:CD:00:00:${index}2",
                )
            }
        )

        config.participants.forEachIndexed { index, participant ->
            val number = index + 1
            assertEquals(
                setOf("D422CD0000${number}1", "D422CD0000${number}2"),
                config.targetsForParticipant(participant.slotId),
            )
        }
        val groups = config.participants.map { config.targetsForParticipant(it.slotId) }
        assertTrue(groups.indices.all { left ->
            groups.indices.all { right ->
                left == right || groups[left].intersect(groups[right]).isEmpty()
            }
        })
    }

    @Test
    fun supportsIndependentTargetLookupForOneTwoAndThreeParticipants() {
        (1..3).forEach { participantCount ->
            val participants = (1..participantCount).map { index ->
                CaptureParticipantBinding(
                    slotId = "slot-$index",
                    athleteId = "athlete-$index",
                    leftDeviceId = "LEFT-$index",
                    rightDeviceId = "RIGHT-$index",
                )
            }
            val config = DeviceRoleConfig(participants)

            assertEquals(participantCount, config.participants.size)
            participants.forEach { participant ->
                assertEquals(2, config.targetsForParticipant(participant.slotId).size)
            }
        }
    }
}
