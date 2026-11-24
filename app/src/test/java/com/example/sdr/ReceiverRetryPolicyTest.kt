package com.example.sdr

import com.example.sdr.receiver.ReceiverRetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiverRetryPolicyTest {
    @Test fun retryDelayIsBoundedAtBothEnds() {
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L),
            (0..6).map { ReceiverRetryPolicy.delayMs(it) })
        assertEquals(1000L, ReceiverRetryPolicy.delayMs(-1))
        assertEquals(30000L, ReceiverRetryPolicy.delayMs(Int.MAX_VALUE))
    }
}
