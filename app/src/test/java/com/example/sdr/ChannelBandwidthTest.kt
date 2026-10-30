package com.example.sdr

import com.example.sdr.receiver.bandwidthLabel
import com.example.sdr.receiver.offsetForBandwidth
import org.junit.Assert.*
import org.junit.Test

class ChannelBandwidthTest {
    @Test fun sidebandWidthKeepsTheLowerAudioEdge() {
        for (width in listOf(500, 1800, 2700, 4000)) {
            assertEquals(150, DemodulationMode.USB.offsetForBandwidth(width) - width / 2)
            assertEquals(-150, DemodulationMode.LSB.offsetForBandwidth(width) + width / 2)
        }
        assertEquals(0, DemodulationMode.AM.offsetForBandwidth(6000))
    }
    @Test fun presetsAreValidAndDefaultIsAlwaysRepresentable() {
        for (mode in DemodulationMode.entries) {
            assertTrue(mode.bandwidthHz in mode.minBandwidthHz..mode.maxBandwidthHz)
            assertTrue(mode.bandwidthPresetsHz.all { it in mode.minBandwidthHz..mode.maxBandwidthHz })
        }
        assertEquals("6.25 kHz", bandwidthLabel(6250))
        assertEquals("100 Hz", bandwidthLabel(100))
    }
}
