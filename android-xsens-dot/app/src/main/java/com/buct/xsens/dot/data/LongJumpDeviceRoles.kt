package com.buct.xsens.dot.data

object LongJumpDeviceRoles {
    const val LEFT_DEVICE_ID = "D422CD007E6E"
    const val RIGHT_DEVICE_ID = "D422CD00937F"

    val targetDeviceIds: Set<String> = setOf(LEFT_DEVICE_ID, RIGHT_DEVICE_ID)

    fun normalizeDeviceId(raw: String): String =
        raw.replace(":", "")
            .replace("-", "")
            .uppercase()

    fun isTargetDevice(raw: String): Boolean =
        normalizeDeviceId(raw) in targetDeviceIds

    fun sideCode(raw: String): String? =
        when (normalizeDeviceId(raw)) {
            LEFT_DEVICE_ID -> "L"
            RIGHT_DEVICE_ID -> "R"
            else -> null
        }

    fun sideLabel(raw: String): String? =
        when (sideCode(raw)) {
            "L" -> "左脚"
            "R" -> "右脚"
            else -> null
        }

    fun roleSortIndex(raw: String): Int =
        when (normalizeDeviceId(raw)) {
            LEFT_DEVICE_ID -> 0
            RIGHT_DEVICE_ID -> 1
            else -> 99
        }
}
