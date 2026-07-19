package com.buct.xsens.dot.engine

import com.xsens.dot.android.sdk.models.DotRecordingFileInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class HistoryFilePairingTest {
    private val baseTime = 1_700_000_000_000L

    @Test
    fun pairsMutualNearestLeftAndRightFiles() {
        val left1 = file(1, baseTime)
        val right1 = file(11, baseTime + 180)
        val left2 = file(2, baseTime + 30_000)
        val right2 = file(12, baseTime + 30_240)

        val sessions = pairParticipantHistoryFiles(
            leftFiles = listOf(left1, left2),
            rightFiles = listOf(right1, right2),
        )

        assertEquals(2, sessions.size)
        assertSame(left2, sessions[0].leftFile)
        assertSame(right2, sessions[0].rightFile)
        assertSame(left1, sessions[1].leftFile)
        assertSame(right1, sessions[1].rightFile)
    }

    @Test
    fun leavesFilesUnpairedWhenTimesAreTooFarApart() {
        val left = file(1, baseTime)
        val right = file(11, baseTime + 8_000)

        val sessions = pairParticipantHistoryFiles(
            leftFiles = listOf(left),
            rightFiles = listOf(right),
        )

        assertEquals(2, sessions.size)
        assertEquals(1, sessions.count { it.leftFile != null })
        assertEquals(1, sessions.count { it.rightFile != null })
        sessions.forEach { session ->
            if (session.leftFile != null) assertNull(session.rightFile)
            if (session.rightFile != null) assertNull(session.leftFile)
        }
    }

    @Test
    fun mutualNearestRulePreventsOneRightFilePairingWithTwoLeftFiles() {
        val leftClosest = file(1, baseTime)
        val leftSecond = file(2, baseTime + 600)
        val right = file(11, baseTime + 100)

        val sessions = pairParticipantHistoryFiles(
            leftFiles = listOf(leftClosest, leftSecond),
            rightFiles = listOf(right),
        )

        assertEquals(2, sessions.size)
        assertSame(
            right,
            sessions.single { it.leftFile === leftClosest }.rightFile,
        )
        assertNull(sessions.single { it.leftFile === leftSecond }.rightFile)
    }

    @Test
    fun parsesTimestampFromLegacyFileNameWhenSdkTimestampIsMissing() {
        val file = DotRecordingFileInfo(1, "recording_20260718_143205.csv", 1024)

        val timestamp = recordingFileTimestampMs(file)

        val formatted = java.text.SimpleDateFormat(
            "yyyyMMdd_HHmmss",
            java.util.Locale.US,
        ).format(java.util.Date(timestamp!!))
        assertEquals("20260718_143205", formatted)
    }

    @Test
    fun pairsSdkPlaceholdersByMatchingFileIdAndSize() {
        val left1 = placeholder(1, 1_126_400)
        val left2 = placeholder(2, 901_120)
        val left3 = file(3, baseTime + 45_000, 225_280)
        val right1 = file(1, baseTime, 1_126_400)
        val right2 = file(2, baseTime + 15_000, 901_120)
        val right3 = file(3, baseTime + 45_000, 225_280)

        val sessions = pairParticipantHistoryFiles(
            leftFiles = listOf(left1, left2, left3),
            rightFiles = listOf(right1, right2, right3),
        )

        assertEquals(3, sessions.size)
        assertEquals(0, sessions.count { it.timestampMs == null })
        assertSame(left1, sessions.single { it.rightFile === right1 }.leftFile)
        assertSame(left2, sessions.single { it.rightFile === right2 }.leftFile)
        assertSame(left3, sessions.single { it.rightFile === right3 }.leftFile)
    }

    @Test
    fun doesNotPairUnknownFilesWhenOnlyFileIdMatches() {
        val left = placeholder(1, 1_024)
        val right = file(1, baseTime, 2_048)

        val sessions = pairParticipantHistoryFiles(
            leftFiles = listOf(left),
            rightFiles = listOf(right),
        )

        assertEquals(2, sessions.size)
        assertEquals(1, sessions.count { it.timestampMs == null })
    }

    private fun file(
        id: Int,
        timestampMs: Long,
        size: Int = 1024,
    ): DotRecordingFileInfo =
        DotRecordingFileInfo(id, "recording_$id", size).also {
            it.startRecordingTimestamp = timestampMs
        }

    private fun placeholder(
        id: Int,
        size: Int,
    ): DotRecordingFileInfo = DotRecordingFileInfo(id, "", size)
}
