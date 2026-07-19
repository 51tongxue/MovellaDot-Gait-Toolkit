package com.buct.xsens.dot.engine

import com.xsens.dot.android.sdk.models.DotRecordingFileInfo

internal fun snapshotRecordingFileInfo(
    source: DotRecordingFileInfo,
): DotRecordingFileInfo =
    DotRecordingFileInfo(
        source.fileId,
        source.fileName.orEmpty(),
        source.dataSize,
    ).also {
        it.startRecordingTimestamp = source.startRecordingTimestamp
    }

internal fun snapshotRecordingFileInfos(
    source: List<DotRecordingFileInfo>,
): List<DotRecordingFileInfo> = source.map(::snapshotRecordingFileInfo)
