package com.buct.xsens.dot.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LongJumpDeviceRolesTest {
    @After
    fun resetRoles() {
        LongJumpDeviceRoles.configure(LongJumpDeviceRoles.defaultConfig())
    }

    @Test
    fun supportsOneTwoAndThreeParticipants() {
        (1..3).forEach { count ->
            val config = DeviceRoleConfig(
                participants = (1..count).map(::participant)
            )

            val configured = LongJumpDeviceRoles.configure(config)

            assertEquals(count, configured.participants.size)
            assertEquals(count * 2, configured.targetDeviceIds.size)
            assertTrue(configured.isComplete)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsZeroParticipants() {
        DeviceRoleConfig(emptyList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMoreThanThreeParticipants() {
        DeviceRoleConfig((1..4).map(::participant))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDuplicateAthletes() {
        LongJumpDeviceRoles.configure(
            DeviceRoleConfig(
                listOf(
                    participant(1),
                    participant(2).copy(athleteId = "athlete-1"),
                )
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDeviceSharedAcrossParticipants() {
        LongJumpDeviceRoles.configure(
            DeviceRoleConfig(
                listOf(
                    participant(1),
                    participant(2).copy(leftDeviceId = participant(1).rightDeviceId),
                )
            )
        )
    }

    @Test
    fun resolvesAthleteAndFootForEveryConfiguredDevice() {
        val configured = LongJumpDeviceRoles.configure(
            DeviceRoleConfig((1..3).map(::participant))
        )

        configured.participants.forEach { participant ->
            val left = LongJumpDeviceRoles.assignmentForDevice(participant.leftDeviceId)
            val right = LongJumpDeviceRoles.assignmentForDevice(participant.rightDeviceId)

            assertEquals(participant.athleteId, left?.participant?.athleteId)
            assertEquals("L", left?.sideCode)
            assertEquals(participant.athleteId, right?.participant?.athleteId)
            assertEquals("R", right?.sideCode)
        }
    }

    @Test
    fun incompleteParticipantDoesNotBlockValidParticipantCount() {
        val config = DeviceRoleConfig(
            listOf(
                participant(1),
                participant(2).copy(rightDeviceId = ""),
            )
        )

        assertEquals(2, config.participants.size)
        assertFalse(config.isComplete)
    }

    @Test
    fun providesConfiguredDevicePairForEachParticipantSlot() {
        assertEquals(
            "D422CD007E6E" to "D422CD00937F",
            LongJumpDeviceRoles.defaultDeviceIdsForSlot("participant-1"),
        )
        assertEquals(
            "D422CD008569" to "D422CD0093B3",
            LongJumpDeviceRoles.defaultDeviceIdsForSlot("participant-2"),
        )
        assertEquals(
            "D422CD009412" to "D422CD007E73",
            LongJumpDeviceRoles.defaultDeviceIdsForSlot("participant-3"),
        )
    }

    private fun participant(index: Int): CaptureParticipantBinding =
        CaptureParticipantBinding(
            slotId = "participant-$index",
            athleteId = "athlete-$index",
            athleteName = "运动员 $index",
            leftDeviceId = "D422CD00${index.toString().padStart(4, '0')}L",
            rightDeviceId = "D422CD00${index.toString().padStart(4, '0')}R",
        )
}
