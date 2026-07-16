package com.buct.xsens.dot.data

data class CaptureAthleteOption(
    val athleteId: String,
    val athleteName: String,
    val athleteCode: String = "",
    val gender: String = "",
    val birthDate: String = "",
    val heightCm: Double = 0.0,
    val weightKg: Double = 0.0,
    val groupName: String = "",
    val dominantLeg: String = "left",
    val extraJson: String = "{}",
)

data class CaptureParticipantBinding(
    val slotId: String,
    val athleteId: String = "",
    val athleteName: String = "",
    val leftDeviceId: String = "",
    val rightDeviceId: String = "",
) {
    val deviceIds: List<String>
        get() = listOf(leftDeviceId, rightDeviceId).filter(String::isNotBlank)

    val normalizedDeviceIds: Set<String>
        get() = deviceIds.map(LongJumpDeviceRoles::normalizeDeviceId).toSet()

    val isComplete: Boolean
        get() = athleteId.isNotBlank() &&
            leftDeviceId.isNotBlank() &&
            rightDeviceId.isNotBlank() &&
            leftDeviceId != rightDeviceId
}

data class DeviceRoleConfig(
    val participants: List<CaptureParticipantBinding>,
) {
    init {
        require(participants.size in 1..3) { "采集运动员数量必须为 1 到 3 名" }
    }

    val targetDeviceIds: Set<String>
        get() = participants.flatMap(CaptureParticipantBinding::deviceIds).toSet()

    val leftDeviceId: String
        get() = participants.first().leftDeviceId

    val rightDeviceId: String
        get() = participants.first().rightDeviceId

    val isComplete: Boolean
        get() = participants.all(CaptureParticipantBinding::isComplete) &&
            targetDeviceIds.size == participants.size * 2

    fun targetsForParticipant(slotId: String): Set<String> =
        participants
            .firstOrNull { it.slotId == slotId }
            ?.normalizedDeviceIds
            .orEmpty()
}

data class DeviceRoleAssignment(
    val participant: CaptureParticipantBinding,
    val sideCode: String,
) {
    val sideLabel: String
        get() = if (sideCode == "L") "左脚" else "右脚"

    val displayLabel: String
        get() = participant.athleteName
            .takeIf(String::isNotBlank)
            ?.let { "$it·$sideLabel" }
            ?: sideLabel
}

object LongJumpDeviceRoles {
    const val DEFAULT_LEFT_DEVICE_ID = "D422CD007E6E"
    const val DEFAULT_RIGHT_DEVICE_ID = "D422CD00937F"
    const val DEFAULT_SLOT_ID = "participant-1"
    const val PARTICIPANT_2_LEFT_DEVICE_ID = "D422CD008569"
    const val PARTICIPANT_2_RIGHT_DEVICE_ID = "D422CD0093B3"
    const val PARTICIPANT_3_LEFT_DEVICE_ID = "D422CD009412"
    const val PARTICIPANT_3_RIGHT_DEVICE_ID = "D422CD007E73"

    @Volatile
    private var configuredRoles = defaultConfig()

    val currentConfig: DeviceRoleConfig
        get() = configuredRoles

    val leftDeviceId: String
        get() = configuredRoles.leftDeviceId

    val rightDeviceId: String
        get() = configuredRoles.rightDeviceId

    val targetDeviceIds: Set<String>
        get() = configuredRoles.targetDeviceIds

    fun defaultConfig(): DeviceRoleConfig = DeviceRoleConfig(
        participants = listOf(
            CaptureParticipantBinding(
                slotId = DEFAULT_SLOT_ID,
                leftDeviceId = DEFAULT_LEFT_DEVICE_ID,
                rightDeviceId = DEFAULT_RIGHT_DEVICE_ID,
            )
        )
    )

    fun defaultDeviceIdsForSlot(slotId: String): Pair<String, String> =
        when (slotId) {
            DEFAULT_SLOT_ID -> DEFAULT_LEFT_DEVICE_ID to DEFAULT_RIGHT_DEVICE_ID
            "participant-2" ->
                PARTICIPANT_2_LEFT_DEVICE_ID to PARTICIPANT_2_RIGHT_DEVICE_ID
            "participant-3" ->
                PARTICIPANT_3_LEFT_DEVICE_ID to PARTICIPANT_3_RIGHT_DEVICE_ID
            else -> "" to ""
        }

    fun normalizeDeviceId(raw: String): String =
        raw.replace(":", "")
            .replace("-", "")
            .uppercase()

    @Synchronized
    fun configure(config: DeviceRoleConfig): DeviceRoleConfig {
        val normalizedParticipants = config.participants.mapIndexed { index, binding ->
            binding.copy(
                slotId = binding.slotId.ifBlank { "participant-${index + 1}" },
                athleteId = binding.athleteId.trim(),
                athleteName = binding.athleteName.trim(),
                leftDeviceId = normalizeDeviceId(binding.leftDeviceId),
                rightDeviceId = normalizeDeviceId(binding.rightDeviceId),
            )
        }
        val normalized = DeviceRoleConfig(normalizedParticipants)
        val assignedDeviceIds = normalized.participants
            .flatMap(CaptureParticipantBinding::deviceIds)
        require(assignedDeviceIds.size == assignedDeviceIds.distinct().size) {
            "同一台设备不能分配给多个脚"
        }
        val assignedAthleteIds = normalized.participants
            .map(CaptureParticipantBinding::athleteId)
            .filter(String::isNotBlank)
        require(assignedAthleteIds.size == assignedAthleteIds.distinct().size) {
            "同一名运动员不能重复加入采集"
        }
        configuredRoles = normalized
        return normalized
    }

    @Synchronized
    fun configure(leftDeviceId: String, rightDeviceId: String): DeviceRoleConfig =
        configure(
            defaultConfig().copy(
                participants = listOf(
                    defaultConfig().participants.first().copy(
                        leftDeviceId = leftDeviceId,
                        rightDeviceId = rightDeviceId,
                    )
                )
            )
        )

    fun isTargetDevice(raw: String): Boolean =
        normalizeDeviceId(raw) in targetDeviceIds

    fun assignmentForDevice(raw: String): DeviceRoleAssignment? {
        val normalized = normalizeDeviceId(raw)
        currentConfig.participants.forEach { participant ->
            if (participant.leftDeviceId == normalized) {
                return DeviceRoleAssignment(participant, "L")
            }
            if (participant.rightDeviceId == normalized) {
                return DeviceRoleAssignment(participant, "R")
            }
        }
        return null
    }

    fun participantForDevice(raw: String): CaptureParticipantBinding? =
        assignmentForDevice(raw)?.participant

    fun sideCode(raw: String): String? =
        assignmentForDevice(raw)?.sideCode

    fun sideLabel(raw: String): String? =
        assignmentForDevice(raw)?.sideLabel

    fun assignmentLabel(raw: String): String? =
        assignmentForDevice(raw)?.displayLabel

    fun roleSortIndex(raw: String): Int {
        val normalized = normalizeDeviceId(raw)
        currentConfig.participants.forEachIndexed { index, participant ->
            if (participant.leftDeviceId == normalized) return index * 2
            if (participant.rightDeviceId == normalized) return index * 2 + 1
        }
        return 99
    }
}
