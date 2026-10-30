package com.example.sdr

import com.example.sdr.receiver.ReceiverSource
import org.junit.Assert.*
import org.junit.Test

class ReceiverSourceTest {
    @Test fun existingPreferenceValuesRemainCompatible() {
        assertEquals(ReceiverSource.USB, ReceiverSource.fromStored("USB"))
        assertEquals(ReceiverSource.WEB_SDR, ReceiverSource.fromStored("WebSDR"))
        assertEquals(ReceiverSource.OPEN_WEB_RX, ReceiverSource.fromStored("OpenWebRX"))
        assertEquals(ReceiverSource.USB, ReceiverSource.fromStored(null))
        assertEquals(ReceiverSource.USB, ReceiverSource.fromStored("unsupported"))
        assertFalse(ReceiverSource.USB.online)
        assertTrue(ReceiverSource.OPEN_WEB_RX.online)
    }
}
