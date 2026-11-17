package com.example.sdr

import com.example.sdr.decoders.fax.FaxDecoder
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class FaxDecoderTest {
    private fun signal(rate: Int, seconds: Double, frequency: (Double) -> Double): ShortArray {
        var phase = 0.0
        return ShortArray((rate * seconds).roundToInt()) { i ->
            phase += 2 * PI * frequency(i.toDouble() / rate) / rate
            (20000 * cos(phase)).roundToInt().toShort()
        }
    }
    private fun feed(decoder: FaxDecoder, pcm: ShortArray, rate: Int, stereo: Boolean = false) {
        var offset = 0
        while (offset < pcm.size) {
            val block = pcm.copyOfRange(offset, min(pcm.size, offset + 997))
            decoder.process(if (stereo) ShortArray(block.size * 2) { block[it / 2] } else block, rate, if (stereo) 2 else 1)
            offset += block.size
        }
    }
    @Test fun reconstructsBlackWhiteLinesAcrossRatesAndPcmBoundaries() {
        for (rate in listOf(8000, 12000, 48000)) {
            val lines = ArrayList<ByteArray>()
            val decoder = FaxDecoder(onLine = lines::add)
            decoder.manualStart()
            feed(decoder, signal(rate, 4.5) { t -> if (t % .5 < .25) 1500.0 else 2300.0 }, rate, rate == 48000)
            assertEquals("line clock at $rate", 9, lines.size)
            val row = lines.last()
            assertEquals(1809, row.size)
            fun mean(start: Int, end: Int) = (start until end).map { row[it].toInt() and 255 }.average()
            assertTrue("Black polarity at $rate", mean(200, 600) < 30)
            assertTrue("White polarity at $rate", mean(1100, 1500) > 225)
        }
    }
    @Test fun automaticStartPhasingAndStop() {
        val lines = ArrayList<ByteArray>(); var starts = 0
        val decoder = FaxDecoder(onStart = { starts++ }, onLine = lines::add)
        val rate = 12000
        feed(decoder, signal(rate, 12.0) { t ->
            when {
                t < 2.0 -> if ((t * 300) % 1 < .5) 1500.0 else 2300.0
                t < 7.0 -> if ((t - 2) % .5 < .025) 1500.0 else 2300.0
                t < 10.0 -> if ((t - 7) % .5 < .25) 1500.0 else 2300.0
                else -> if ((t * 450) % 1 < .5) 1500.0 else 2300.0
            }
        }, rate)
        assertEquals(1, starts)
        assertTrue("Phasing must lead to image lines", lines.size >= 3)
        assertFalse(decoder.receiving)
        assertEquals("Complete", decoder.status)
    }
    @Test fun formatChangesLineClockAndWidth() {
        val lines = ArrayList<ByteArray>()
        val decoder = FaxDecoder(lpm = 60, ioc = 288, onLine = lines::add)
        decoder.manualStart()
        feed(decoder, signal(8000, 3.0) { 1900.0 }, 8000)
        assertEquals(3, lines.size)
        assertEquals(904, decoder.width)
        assertTrue(lines.last().map { it.toInt() and 255 }.average() in 115.0..140.0)
    }
}
