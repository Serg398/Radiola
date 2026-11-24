package com.example.sdr

import org.junit.Assert.*
import org.junit.Test

/** Debug regression check; the minified build uses ReleaseSmokeInstrumentation. */
class ReleaseBoundaryTest {
    @Test fun nativeBridgeRetainsNamesAndSnapshotConstructors() {
        val rates = NativeSdr.receiverRates()
        assertTrue(rates[0] > 0 && rates[1] > 0)
        val frame = NativeSdr.spectrum()
        assertEquals(8192, frame.bins.size)
        assertEquals(rates[0], frame.spanHz)
        assertNotNull(NativeSdr.rds().information())
        assertTrue(NativeSdr.visualStats().isNotEmpty())
    }
}
