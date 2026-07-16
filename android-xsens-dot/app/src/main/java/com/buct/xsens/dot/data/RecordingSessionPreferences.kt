package com.buct.xsens.dot.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

data class StoredRecordingAssignment(
    val sessionUtcMs: Long,
    val assignment: DeviceRoleAssignment,
)

class RecordingSessionPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun saveSession(sessionUtcMs: Long, config: DeviceRoleConfig) {
        val sessions = loadSessions()
            .filterNot { it.optLong("session_utc_ms") == sessionUtcMs }
            .toMutableList()
        sessions += encodeSession(sessionUtcMs, config)
        while (sessions.size > MAX_SESSIONS) sessions.removeAt(0)
        persist(sessions)
    }

    fun findAssignment(deviceId: String, recordingUtcMs: Long): StoredRecordingAssignment? {
        val normalizedDeviceId = LongJumpDeviceRoles.normalizeDeviceId(deviceId)
        return loadSessions()
            .mapNotNull { session ->
                val sessionUtcMs = session.optLong("session_utc_ms")
                val participants = session.optJSONArray("participants") ?: return@mapNotNull null
                for (index in 0 until participants.length()) {
                    val item = participants.optJSONObject(index) ?: continue
                    val participant = CaptureParticipantBinding(
                        slotId = item.optString("slot_id"),
                        athleteId = item.optString("athlete_id"),
                        athleteName = item.optString("athlete_name"),
                        leftDeviceId = LongJumpDeviceRoles.normalizeDeviceId(
                            item.optString("left_device_id")
                        ),
                        rightDeviceId = LongJumpDeviceRoles.normalizeDeviceId(
                            item.optString("right_device_id")
                        ),
                    )
                    val side = when (normalizedDeviceId) {
                        participant.leftDeviceId -> "L"
                        participant.rightDeviceId -> "R"
                        else -> null
                    } ?: continue
                    return@mapNotNull StoredRecordingAssignment(
                        sessionUtcMs = sessionUtcMs,
                        assignment = DeviceRoleAssignment(participant, side),
                    )
                }
                null
            }
            .filter {
                recordingUtcMs <= 0L ||
                    abs(it.sessionUtcMs - recordingUtcMs) <= SESSION_MATCH_TOLERANCE_MS
            }
            .minByOrNull {
                if (recordingUtcMs > 0L) abs(it.sessionUtcMs - recordingUtcMs) else -it.sessionUtcMs
            }
    }

    private fun encodeSession(sessionUtcMs: Long, config: DeviceRoleConfig): JSONObject {
        val participants = JSONArray()
        config.participants.forEach { participant ->
            participants.put(
                JSONObject()
                    .put("slot_id", participant.slotId)
                    .put("athlete_id", participant.athleteId)
                    .put("athlete_name", participant.athleteName)
                    .put("left_device_id", participant.leftDeviceId)
                    .put("right_device_id", participant.rightDeviceId)
            )
        }
        return JSONObject()
            .put("session_utc_ms", sessionUtcMs)
            .put("participants", participants)
    }

    private fun loadSessions(): List<JSONObject> {
        val raw = preferences.getString(KEY_SESSIONS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun persist(sessions: List<JSONObject>) {
        val array = JSONArray()
        sessions.forEach(array::put)
        preferences.edit().putString(KEY_SESSIONS, array.toString()).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "dot_recording_sessions"
        const val KEY_SESSIONS = "sessions"
        const val MAX_SESSIONS = 50
        const val SESSION_MATCH_TOLERANCE_MS = 10 * 60 * 1000L
    }
}
