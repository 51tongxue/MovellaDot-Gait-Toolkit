package com.buct.xsens.dot.engine

import com.xsens.dot.android.sdk.models.DotRecordingFileInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class RecordingFileInfoSnapshotTest {
    @Test
    fun snapshotDoesNotChangeWhenSdkObjectIsMutatedLater() {
        val sdkFile = DotRecordingFileInfo(2, "20260716_151815", 901_120).also {
            it.startRecordingTimestamp = 1_784_186_295_000L
        }

        val snapshot = snapshotRecordingFileInfo(sdkFile)
        sdkFile.fileName = ""
        sdkFile.dataSize = 0
        sdkFile.startRecordingTimestamp = 0L

        assertNotSame(sdkFile, snapshot)
        assertEquals(2, snapshot.fileId)
        assertEquals("20260716_151815", snapshot.fileName)
        assertEquals(901_120, snapshot.dataSize)
        assertEquals(1_784_186_295_000L, snapshot.startRecordingTimestamp)
    }
}
