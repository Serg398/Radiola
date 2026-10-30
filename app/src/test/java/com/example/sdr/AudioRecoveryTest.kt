package com.example.sdr

import com.example.sdr.audio.AudioRecovery
import org.junit.Assert.*
import org.junit.Test

class AudioRecoveryTest {
    @Test fun repeatedDeadOutputsHaveBoundedRecovery() {
        val policy = AudioRecovery()
        assertTrue(policy.recreate(100))
        assertTrue(policy.recreate(200))
        assertTrue(policy.recreate(300))
        assertFalse(policy.recreate(400))
        assertTrue(policy.recreate(10100))
    }
    @Test fun zeroWritesCannotSpinForever() {
        val policy = AudioRecovery()
        assertTrue(policy.wrote(0, 100))
        assertTrue(policy.wrote(0, 1099))
        assertFalse(policy.wrote(0, 1100))
    }
    @Test fun partialWritesResetOnlyTheStallTimer() {
        val policy = AudioRecovery()
        assertTrue(policy.wrote(0, 100))
        assertTrue(policy.wrote(128, 900))
        assertTrue(policy.wrote(0, 1800))
        assertFalse(policy.wrote(0, 2800))
        assertTrue(policy.recreate(2800))
        assertTrue(policy.wrote(0, 2801))
    }
}
