package com.buct.xsens.dot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncompleteSyncRetryTest {
    @Test
    fun retriesAllZeroResultWithinBudget() {
        assertTrue(
            shouldRetryIncompleteSync(
                succeededCount = 0,
                totalCount = 2,
                retryCount = 0,
                maxRetries = 1,
            )
        )
    }

    @Test
    fun retriesPartialResultWithinBudget() {
        assertTrue(
            shouldRetryIncompleteSync(
                succeededCount = 1,
                totalCount = 2,
                retryCount = 0,
                maxRetries = 1,
            )
        )
    }

    @Test
    fun doesNotRetryCompleteOrExhaustedResult() {
        assertFalse(
            shouldRetryIncompleteSync(
                succeededCount = 2,
                totalCount = 2,
                retryCount = 0,
                maxRetries = 1,
            )
        )
        assertFalse(
            shouldRetryIncompleteSync(
                succeededCount = 1,
                totalCount = 2,
                retryCount = 1,
                maxRetries = 1,
            )
        )
    }
}
