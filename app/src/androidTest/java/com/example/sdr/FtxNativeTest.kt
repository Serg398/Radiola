package com.example.sdr

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class FtxNativeTest {
    private external fun reference(ft4: Boolean): ShortArray

    @Test fun ft8AndFt4DecodeIndependentEncoderSignals() {
        NativeFtx.decode(ShortArray(12000), false) // load JNI library
        for (ft4 in listOf(false, true)) {
            val result = NativeFtx.decode(reference(ft4), ft4)
            assertTrue("ft4=$ft4: ${result.toList()}", result.any { it.endsWith("CQ K1ABC FN42") })
        }
    }

    @Test fun liveWorkerCollectsAndDecodesBothModes() {
        NativeFtx.decode(ShortArray(12000), false)
        for (mode in FtxMode.entries) {
            val worker = FtxDecodeWorker()
            try {
                worker.configure(mode, 14074000)
                val pcm = reference(mode == FtxMode.FT4)
                var offset = 0
                while (offset < pcm.size) {
                    val block = pcm.copyOfRange(offset, minOf(offset + 4096, pcm.size))
                    worker.offer(block, block.size, 12000, 1)
                    offset += block.size
                }
                val end = System.currentTimeMillis() + 5000
                while (worker.snapshot().lines.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(20)
                assertTrue("$mode: ${worker.snapshot()}", worker.snapshot().lines.any { it.endsWith("CQ K1ABC FN42") })
            } finally { worker.close() }
        }
    }

    @Test fun workerResamplesUsbStereoAndWebSdrAudio() {
        NativeFtx.decode(ShortArray(12000), false)
        for ((mode, rate, channels) in listOf(Triple(FtxMode.FT8, 48000, 2), Triple(FtxMode.FT4, 8000, 1))) {
            val worker = FtxDecodeWorker()
            try {
                worker.configure(mode, 7074000)
                val input = reference(mode == FtxMode.FT4)
                val frames = input.size.toLong() * rate / 12000
                val samples = ShortArray((frames * channels).toInt()) { index ->
                    val x = (index / channels) * 12000.0 / rate
                    val a = x.toInt().coerceAtMost(input.lastIndex)
                    (input[a] + (input[minOf(a + 1, input.lastIndex)] - input[a]) * (x - a)).toInt().toShort()
                }
                var offset = 0
                while (offset < samples.size) {
                    val block = samples.copyOfRange(offset, minOf(offset + 8192, samples.size))
                    worker.offer(block, block.size, rate, channels)
                    offset += block.size
                    Thread.sleep(1)
                }
                // Linear interpolation retains one input sample until its successor arrives.
                worker.offer(ShortArray(channels * 128), channels * 128, rate, channels)
                val end = System.currentTimeMillis() + 5000
                while (worker.snapshot().lines.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(20)
                assertTrue("$mode/$rate/$channels: ${worker.snapshot()}", worker.snapshot().lines.any { it.endsWith("CQ K1ABC FN42") })
            } finally { worker.close() }
        }
    }

    @Test fun realFt8CaptureDecodesKnownMessage() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val wav = context.assets.open("ft8-websdr.wav").use { it.readBytes() }
        var offset = 12
        while (String(wav, offset, 4, Charsets.US_ASCII) != "data") {
            val size = ByteBuffer.wrap(wav, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            offset += 8 + size + (size and 1)
        }
        val dataSize = ByteBuffer.wrap(wav, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val bytes = ByteBuffer.wrap(wav, offset + 8, dataSize).order(ByteOrder.LITTLE_ENDIAN)
        val pcm = ShortArray(bytes.remaining() / 2) { bytes.short }
        val result = NativeFtx.decode(pcm, false)
        assertTrue(result.toList().toString(), result.any { it.endsWith("LZ1LZ G4UJS IO83") })
    }
}
