package com.example.sdr

import org.junit.Assert.*
import org.junit.Test

class RdsFrameTest {
    @Test fun nativePayloadDecodesVerifiedFieldsAndTextTerminator() {
        val bytes = ByteArray(76)
        bytes[0] = 0x77; bytes[1] = 0x60; bytes[2] = 10; bytes[3] = 15
        "RADIOLA ".toByteArray().copyInto(bytes, 4)
        "Song\rOld text".toByteArray().copyInto(bytes, 12)
        val info = RdsFrame(bytes, 87_600_000, 40, 20).information()
        assertEquals("RADIOLA", info.station)
        assertEquals("Song", info.text)
        assertEquals("Pop music", info.programmeType)
        assertEquals(0x7760, info.pi)
        assertTrue(info.trafficProgramme && info.trafficAnnouncement && info.synchronized)
    }

    @Test fun incompleteAndStaleGroupsAreDistinguished() {
        val bytes = ByteArray(76)
        bytes[0] = 0x12; bytes[1] = 0x34
        "BAD TEXT".toByteArray().copyInto(bytes, 4)
        val info = RdsFrame(bytes, 100_000_000, 6000, 5).information()
        assertEquals("", info.station)
        assertEquals("", info.text)
        assertFalse(info.synchronized)
        assertNull(RdsFrame(bytes, 100_000_000, 0, 0).information().pi)
    }

    @Test fun rdsAlphabetIsNotLatin1AndDoesNotDisplayControlCodes() {
        assertEquals("á€", RdsCharset.decode(byteArrayOf(0x80.toByte(), 0xa9.toByte()), 0, 2))
        assertEquals("A B", RdsCharset.decode(byteArrayOf(65, 0, 66, 13, 67), 0, 5))
    }
}
