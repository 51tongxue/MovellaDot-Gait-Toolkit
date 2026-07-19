package com.buct.xsens.dot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileInfoReadStateTest {
    private val targets = setOf("LEFT", "RIGHT")

    @Test
    fun terminalRequiresBothDevicesToFinish() {
        val statuses = mapOf(
            "LEFT" to FileInfoReadStatus(FileInfoReadPhase.Ready),
            "RIGHT" to FileInfoReadStatus(FileInfoReadPhase.Reading),
        )

        assertFalse(areFileInfoReadTargetsTerminal(statuses, targets))
    }

    @Test
    fun failureIsTerminalButBlocksImplicitExport() {
        val statuses = mapOf(
            "LEFT" to FileInfoReadStatus(FileInfoReadPhase.Ready),
            "RIGHT" to FileInfoReadStatus(FileInfoReadPhase.Failed),
        )

        assertTrue(areFileInfoReadTargetsTerminal(statuses, targets))
        assertFalse(canImplicitlyExportFileInfo(statuses, targets))
    }

    @Test
    fun readyAndEmptyAreCompleteForImplicitExport() {
        val statuses = mapOf(
            "LEFT" to FileInfoReadStatus(FileInfoReadPhase.Ready),
            "RIGHT" to FileInfoReadStatus(FileInfoReadPhase.Empty),
        )

        assertTrue(areFileInfoReadTargetsTerminal(statuses, targets))
        assertTrue(canImplicitlyExportFileInfo(statuses, targets))
    }

    @Test
    fun missingStatusBlocksCompletionAndExport() {
        val statuses = mapOf(
            "LEFT" to FileInfoReadStatus(FileInfoReadPhase.Ready),
        )

        assertFalse(areFileInfoReadTargetsTerminal(statuses, targets))
        assertFalse(canImplicitlyExportFileInfo(statuses, targets))
    }

    @Test
    fun onlyReadyStatusRepresentsFreshSelectableFiles() {
        assertTrue(FileInfoReadStatus(FileInfoReadPhase.Ready).hasFreshFiles)
        assertFalse(FileInfoReadStatus(FileInfoReadPhase.Empty).hasFreshFiles)
        assertFalse(FileInfoReadStatus(FileInfoReadPhase.Failed).hasFreshFiles)
        assertFalse(FileInfoReadStatus(FileInfoReadPhase.Idle).hasFreshFiles)
    }
}
