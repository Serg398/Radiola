package com.example.sdr

import com.example.sdr.sources.websdr.WebSdrAudioDecoder
import com.example.sdr.sources.websdr.WebSdrBandParser
import com.example.sdr.sources.websdr.WebSdrWaterfallDecoder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class WebSdrProtocolTest {
    @Test fun signalUsesServerScaleAndPersistsAcrossAudioPackets() {
        val decoder = WebSdrAudioDecoder()
        assertNull(decoder.signalDbm)
        decoder.decode(byteArrayOf(0xf2.toByte(), 0x1c)) { _, _ -> }
        assertEquals(-73f, decoder.signalDbm!!, 0.001f)
        decoder.decode(byteArrayOf(0x84.toByte())) { _, _ -> }
        assertEquals(-73f, decoder.signalDbm!!, 0.001f)
        decoder.decode(byteArrayOf(0xf0.toByte(), 0)) { _, _ -> }
        assertEquals(-127f, decoder.signalDbm!!, 0.001f)
        decoder.decode(byteArrayOf(0xf3.toByte(), 0x48)) { _, _ -> }
        assertEquals(-43f, decoder.signalDbm!!, 0.001f)
    }
    private fun fixture(name: String) = javaClass.getResourceAsStream("/websdr/$name")!!.use { it.readBytes() }

    @Test fun capturedAudioMatchesReferenceAcrossMessageBoundaries() {
        val decoder = WebSdrAudioDecoder()
        repeat(12) { index ->
            val prefix = "audio-%02d".format(index)
            val actual = ByteArrayOutputStream()
            decoder.decode(fixture("$prefix.bin")) { rate, samples ->
                assertEquals(7119, rate)
                val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                samples.forEach { bytes.putShort(it) }
                actual.write(bytes.array())
            }
            assertArrayEquals(prefix, fixture("$prefix.pcm"), actual.toByteArray())
        }
    }

    @Test fun capturedWaterfallMatchesReferenceAcrossRows() {
        for (format in listOf(9, 10)) {
            val decoder = WebSdrWaterfallDecoder(format = format)
            repeat(12) { index ->
                val prefix = "water$format-%02d".format(index)
                val row = decoder.decode(fixture("$prefix.bin"))
                if (index == 0) assertNull(row)
                else assertArrayEquals(prefix, fixture("$prefix.levels"), row!!.map { it.toByte() }.toByteArray())
            }
        }
    }

    @Test fun rawAudioAndSilenceDoNotRetainPredictorState() {
        val decoder = WebSdrAudioDecoder()
        val raw = byteArrayOf(0x80.toByte()) + ByteArray(128) { 0xd5.toByte() } + byteArrayOf(0x84.toByte())
        val blocks = mutableListOf<ShortArray>()
        decoder.decode(raw) { _, samples -> blocks += samples }
        assertArrayEquals(ShortArray(128) { 8 }, blocks[0])
        assertArrayEquals(ShortArray(128), blocks[1])
    }

    @Test fun malformedPacketsFailWithoutReadingPastTheBuffer() {
        assertThrows(IllegalArgumentException::class.java) { WebSdrAudioDecoder().decode(byteArrayOf(0x81.toByte())) { _, _ -> } }
        assertThrows(IllegalArgumentException::class.java) { WebSdrWaterfallDecoder().decode(byteArrayOf(0xff.toByte())) }
        assertThrows(IllegalArgumentException::class.java) { WebSdrWaterfallDecoder().decode(byteArrayOf(0)) }
    }

    @Test fun tunerBandsAreReadAsDataWithoutExecutingJavascript() {
        val source = """
            var nbands=2;
            var bandinfo=[
              { centerfreq: 7100.0, samplerate: 192.0, vfo: 7150.0, maxzoom: 5, name: '40m', scaleimgs: [['image.png']] },
              { centerfreq: 14200.0, samplerate: 384.0, vfo: 14250.0, maxzoom: 6, name: '20m' }
            ];
            alert('must never execute');
        """.trimIndent()
        val bands = WebSdrBandParser.parse(source)
        assertEquals(2, bands.size)
        assertEquals(7_004_000L, bands[0].lowHz)
        assertEquals(7_196_000L, bands[0].highHz)
        assertEquals(7_150_000L, bands[0].defaultHz)
        assertTrue(bands[1].contains(14_250_000L))
        assertFalse(bands[1].contains(7_100_000L))
    }
}
