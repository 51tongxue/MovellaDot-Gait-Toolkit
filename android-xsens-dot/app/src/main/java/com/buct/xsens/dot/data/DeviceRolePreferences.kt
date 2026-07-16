package com.buct.xsens.dot.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class DeviceRolePreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun load(): DeviceRoleConfig {
        val config = runCatching {
            preferences.getString(KEY_PARTICIPANTS_JSON, null)
                ?.let(::decode)
                ?: migrateLegacyConfig()
        }.getOrElse {
            LongJumpDeviceRoles.defaultConfig()
        }
        val migrated = migrateConfiguredDevicePresets(config)
        return runCatching { LongJumpDeviceRoles.configure(migrated) }
            .getOrElse { LongJumpDeviceRoles.configure(LongJumpDeviceRoles.defaultConfig()) }
            .also(::persist)
    }

    fun save(config: DeviceRoleConfig): DeviceRoleConfig =
        LongJumpDeviceRoles.configure(config).also(::persist)

    private fun migrateLegacyConfig(): DeviceRoleConfig {
        val left = preferences.getString(KEY_LEFT_DEVICE_ID, null)
            ?: LongJumpDeviceRoles.DEFAULT_LEFT_DEVICE_ID
        val right = preferences.getString(KEY_RIGHT_DEVICE_ID, null)
            ?: LongJumpDeviceRoles.DEFAULT_RIGHT_DEVICE_ID
        return DeviceRoleConfig(
            participants = listOf(
                CaptureParticipantBinding(
                    slotId = LongJumpDeviceRoles.DEFAULT_SLOT_ID,
                    leftDeviceId = left,
                    rightDeviceId = right,
                )
            )
        )
    }

    private fun decode(raw: String): DeviceRoleConfig {
        val array = JSONArray(raw)
        val participants = buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    CaptureParticipantBinding(
                        slotId = item.optString("slot_id").ifBlank { "participant-${index + 1}" },
                        athleteId = item.optString("athlete_id"),
                        athleteName = item.optString("athlete_name"),
                        leftDeviceId = item.optString("left_device_id"),
                        rightDeviceId = item.optString("right_device_id"),
                    )
                )
            }
        }
        return DeviceRoleConfig(participants)
    }

    private fun migrateConfiguredDevicePresets(config: DeviceRoleConfig): DeviceRoleConfig {
        val storedVersion = preferences.getInt(KEY_CONFIG_VERSION, 0)
        if (storedVersion >= CONFIG_VERSION) return config

        var migrated = config
        if (storedVersion < 2) {
            migrated = migrated.copy(
                participants = migrated.participants.map { participant ->
                    if (participant.slotId == LongJumpDeviceRoles.DEFAULT_SLOT_ID) {
                        participant
                    } else {
                        val (leftDeviceId, rightDeviceId) =
                            LongJumpDeviceRoles.defaultDeviceIdsForSlot(participant.slotId)
                        participant.copy(
                            leftDeviceId = leftDeviceId,
                            rightDeviceId = rightDeviceId,
                        )
                    }
                }
            )
        }
        if (storedVersion < 3 && migrated.participants.size > 1) {
            migrated = migrated.copy(participants = migrated.participants.take(1))
        }
        return migrated
    }

    private fun persist(config: DeviceRoleConfig) {
        val array = JSONArray()
        config.participants.forEach { participant ->
            array.put(
                JSONObject()
                    .put("slot_id", participant.slotId)
                    .put("athlete_id", participant.athleteId)
                    .put("athlete_name", participant.athleteName)
                    .put("left_device_id", participant.leftDeviceId)
                    .put("right_device_id", participant.rightDeviceId)
            )
        }
        preferences.edit()
            .putString(KEY_PARTICIPANTS_JSON, array.toString())
            .putString(KEY_LEFT_DEVICE_ID, config.leftDeviceId)
            .putString(KEY_RIGHT_DEVICE_ID, config.rightDeviceId)
            .putInt(KEY_CONFIG_VERSION, CONFIG_VERSION)
            .apply()
    }

    private companion object {
        const val CONFIG_VERSION = 3
        const val PREFERENCES_NAME = "dot_device_roles"
        const val KEY_CONFIG_VERSION = "config_version"
        const val KEY_PARTICIPANTS_JSON = "participants_json"
        const val KEY_LEFT_DEVICE_ID = "left_device_id"
        const val KEY_RIGHT_DEVICE_ID = "right_device_id"
    }
}
