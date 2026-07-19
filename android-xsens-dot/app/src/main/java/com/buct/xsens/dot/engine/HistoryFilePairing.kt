package com.buct.xsens.dot.engine

import com.xsens.dot.android.sdk.models.DotRecordingFileInfo
import java.text.SimpleDateFormat
import java.util.Locale

data class ParticipantHistorySession(
    val id: String,
    val timestampMs: Long?,
    val leftFile: DotRecordingFileInfo?,
    val rightFile: DotRecordingFileInfo?,
)

private const val MAX_HISTORY_PAIR_DELTA_MS = 5_000L

internal fun recordingFileTimestampMs(file: DotRecordingFileInfo): Long? {
    val raw = file.startRecordingTimestamp
    if (raw > 10_000_000_000L) return raw
    if (raw > 0L) return raw * 1_000L

    val name = file.fileName.orEmpty()
    val match = Regex("(20\\d{6})[_-]?(\\d{6})").find(name) ?: return null
    return runCatching {
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .parse("${match.groupValues[1]}_${match.groupValues[2]}")
            ?.time
    }.getOrNull()
}

internal fun pairParticipantHistoryFiles(
    leftFiles: List<DotRecordingFileInfo>,
    rightFiles: List<DotRecordingFileInfo>,
): List<ParticipantHistorySession> {
    val leftTimestamps = leftFiles.associateWith(::recordingFileTimestampMs)
    val rightTimestamps = rightFiles.associateWith(::recordingFileTimestampMs)

    fun nearestRight(left: DotRecordingFileInfo): DotRecordingFileInfo? {
        val leftTime = leftTimestamps[left] ?: return null
        return rightFiles
            .mapNotNull { right ->
                val rightTime = rightTimestamps[right] ?: return@mapNotNull null
                right to kotlin.math.abs(leftTime - rightTime)
            }
            .minWithOrNull(
                compareBy<Pair<DotRecordingFileInfo, Long>> { it.second }
                    .thenBy { it.first.fileId },
            )
            ?.takeIf { it.second <= MAX_HISTORY_PAIR_DELTA_MS }
            ?.first
    }

    fun nearestLeft(right: DotRecordingFileInfo): DotRecordingFileInfo? {
        val rightTime = rightTimestamps[right] ?: return null
        return leftFiles
            .mapNotNull { left ->
                val leftTime = leftTimestamps[left] ?: return@mapNotNull null
                left to kotlin.math.abs(rightTime - leftTime)
            }
            .minWithOrNull(
                compareBy<Pair<DotRecordingFileInfo, Long>> { it.second }
                    .thenBy { it.first.fileId },
            )
            ?.takeIf { it.second <= MAX_HISTORY_PAIR_DELTA_MS }
            ?.first
    }

    val matchedLeft = mutableSetOf<DotRecordingFileInfo>()
    val matchedRight = mutableSetOf<DotRecordingFileInfo>()
    val rightByLeft = mutableMapOf<DotRecordingFileInfo, DotRecordingFileInfo>()

    // Prefer timestamps because file IDs are only device-local. Mutual nearest matching prevents
    // one file from being reused when recordings were started independently.
    leftFiles.forEach { left ->
        val right = nearestRight(left)
            ?.takeIf { nearestLeft(it) === left }
            ?.takeIf { it !in matchedRight }
            ?: return@forEach
        matchedLeft += left
        matchedRight += right
        rightByLeft[left] = right
    }

    // The SDK first publishes mutable Flash placeholders and fills their timestamp later. If one
    // side reaches the app before that mutation is complete, recover the synchronized pair only
    // when both device-local file ID and byte size agree. Never pair two timestamped files here.
    leftFiles
        .filterNot { it in matchedLeft }
        .forEach { left ->
            val leftTime = leftTimestamps[left]
            val candidates = rightFiles.filter { right ->
                right !in matchedRight &&
                    (leftTime == null || rightTimestamps[right] == null) &&
                    right.fileId == left.fileId &&
                    right.dataSize == left.dataSize
            }
            if (candidates.size == 1) {
                val right = candidates.single()
                matchedLeft += left
                matchedRight += right
                rightByLeft[left] = right
            }
        }

    val sessions = mutableListOf<ParticipantHistorySession>()
    leftFiles.forEach { left ->
        val right = rightByLeft[left]
        val timestamp = listOfNotNull(
            leftTimestamps[left],
            right?.let(rightTimestamps::get),
        ).minOrNull()
        sessions += ParticipantHistorySession(
            id = "L${left.fileId}-R${right?.fileId ?: "none"}",
            timestampMs = timestamp,
            leftFile = left,
            rightFile = right,
        )
    }
    rightFiles
        .filterNot { it in matchedRight }
        .forEach { right ->
            sessions += ParticipantHistorySession(
                id = "Lnone-R${right.fileId}",
                timestampMs = rightTimestamps[right],
                leftFile = null,
                rightFile = right,
            )
        }

    return sessions.sortedWith(
        compareByDescending<ParticipantHistorySession> { it.timestampMs ?: Long.MIN_VALUE }
            .thenByDescending { it.id },
    )
}
