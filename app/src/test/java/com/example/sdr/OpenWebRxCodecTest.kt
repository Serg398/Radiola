package com.example.sdr

import com.example.sdr.sources.openwebrx.OpenWebRxAdpcm
import com.example.sdr.sources.openwebrx.OpenWebRxCodec
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class OpenWebRxCodecTest {
    @Test fun syncedAudioIsIndependentOfWebSocketMessageBoundaries() {
        fun block(predictor: Short) = "SYNC".toByteArray() +
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putShort(0).putShort(predictor).array() +
            ByteArray(1001) { ((it * 19) and 255).toByte() }
        val wire = byteArrayOf(1, 2, 3) + block(100) + block(-500)
        val expected = OpenWebRxAdpcm().decode(wire, synced = true)
        assertEquals(4004, expected.size)
        for (chunk in listOf(1, 3, 7, 512, 1000)) {
            val decoder = OpenWebRxAdpcm()
            val actual = wire.toList().chunked(chunk).flatMap { decoder.decode(it.toByteArray(), true).toList() }.toShortArray()
            assertArrayEquals("chunk size $chunk", expected, actual)
        }
    }
    @Test fun rawPcmAndFftUseLittleEndianAndRejectBadPayloads() {
        assertArrayEquals(shortArrayOf(1, -32768), OpenWebRxCodec.pcm(byteArrayOf(1, 0, 0, -128)))
        val wire = ByteBuffer.allocate(16 * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(16) { putFloat(-100f + it) }
        }.array()
        assertArrayEquals(FloatArray(16) { -100f + it }, OpenWebRxCodec.spectrum(wire, "none", 16), 0f)
        assertThrows(IllegalArgumentException::class.java) { OpenWebRxCodec.spectrum(wire.copyOf(10), "none", 16) }
        assertThrows(IllegalArgumentException::class.java) { OpenWebRxCodec.spectrum(ByteArray(2), "adpcm", 16) }
    }
    @Test fun compressedFftDropsItsTenWarmupSamples() {
        val result = OpenWebRxCodec.spectrum(ByteArray(13), "adpcm", 16)
        assertEquals(16, result.size)
        assertTrue(result.all { it == 0f })
    }
}
