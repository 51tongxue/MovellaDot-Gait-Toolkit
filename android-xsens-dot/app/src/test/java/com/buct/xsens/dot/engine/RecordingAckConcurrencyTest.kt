package com.buct.xsens.dot.engine

import com.xsens.dot.android.sdk.models.DotRecordingState
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

class RecordingAckConcurrencyTest {
    @Test
    fun recordingAckStateUpdateAndCompletionAreSerialized() {
        val callback = RecordingEngine::class.java.getDeclaredMethod(
            "onDotRecordingAck",
            String::class.java,
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            DotRecordingState::class.java,
        )

        assertTrue(Modifier.isSynchronized(callback.modifiers))
    }
}
