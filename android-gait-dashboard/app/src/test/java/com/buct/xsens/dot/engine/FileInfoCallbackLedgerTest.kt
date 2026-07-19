package com.buct.xsens.dot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileInfoCallbackLedgerTest {
    private val ledger = FileInfoCallbackLedger(String::uppercase)

    @Test
    fun callbacksAreAttributedToRequestsInOrder() {
        ledger.enqueue("left", 10)
        ledger.enqueue("LEFT", 11)

        assertEquals(10, ledger.pop("Left"))
        assertEquals(11, ledger.pop("left"))
        assertNull(ledger.pop("left"))
    }

    @Test
    fun removingTimedOutRequestDoesNotConsumeNewerRequest() {
        ledger.enqueue("left", 20)
        ledger.enqueue("left", 21)

        ledger.remove("LEFT", 20)

        assertEquals(21, ledger.pop("left"))
        assertFalse(ledger.hasOutstanding("left"))
    }

    @Test
    fun clearReleasesAllAddresses() {
        ledger.enqueue("left", 1)
        ledger.enqueue("right", 2)
        assertTrue(ledger.hasOutstanding("left"))

        ledger.clear()

        assertFalse(ledger.hasOutstanding("left"))
        assertFalse(ledger.hasOutstanding("right"))
    }
}
