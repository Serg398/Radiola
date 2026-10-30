package com.example.sdr.sources.openwebrx

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Wire codecs: little endian PCM/float FFT and IMA ADPCM (low nibble first). */
internal class OpenWebRxAdpcm {
    private var predictor = 0
    private var index = 0
    private var step = 0
    private var syncAt = 0
    private var headerAt = -1
    private val header = ByteArray(4)
    private var remaining = 0

    private fun sample(nibble: Int): Short {
        var delta = step shr 3
        if (nibble and 1 != 0) delta += step shr 2
        if (nibble and 2 != 0) delta += step shr 1
        if (nibble and 4 != 0) delta += step
        predictor = (predictor + if (nibble and 8 != 0) -delta else delta).coerceIn(-32768, 32767)
        index = (index + adjustment[nibble and 7]).coerceIn(0, 88)
        step = steps[index]
        return predictor.toShort()
    }

    fun decode(bytes: ByteArray, synced: Boolean): ShortArray {
        val output = ShortArray(bytes.size * 2)
        var count = 0
        for (byte in bytes) {
            val value = byte.toInt() and 255
            if (synced && remaining == 0) {
                if (headerAt >= 0) {
                    header[headerAt++] = byte
                    if (headerAt == 4) {
                        val state = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                        index = state.short.toInt()
                        require(index in 0..88) { "Invalid ADPCM state" }
                        predictor = state.short.toInt()
                        remaining = 1001
                        headerAt = -1
                        syncAt = 0
                    }
                } else {
                    syncAt = if (value == sync[syncAt]) syncAt + 1 else if (value == sync[0]) 1 else 0
                    if (syncAt == sync.size) { headerAt = 0; syncAt = 0 }
                }
                continue
            }
            output[count++] = sample(value and 15)
            output[count++] = sample(value shr 4)
            if (synced) remaining--
        }
        return output.copyOf(count)
    }

    companion object {
        private val sync = intArrayOf(83, 89, 78, 67)
        private val adjustment = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8)
        private val steps = intArrayOf(7,8,9,10,11,12,13,14,16,17,19,21,23,25,28,31,34,37,41,45,
            50,55,60,66,73,80,88,97,107,118,130,143,157,173,190,209,230,253,279,307,
            337,371,408,449,494,544,598,658,724,796,876,963,1060,1166,1282,1411,1552,
            1707,1878,2066,2272,2499,2749,3024,3327,3660,4026,4428,4871,5358,5894,
            6484,7132,7845,8630,9493,10442,11487,12635,13899,15289,16818,18500,20350,
            22385,24623,27086,29794,32767)
    }
}

internal object OpenWebRxCodec {
    fun pcm(bytes: ByteArray): ShortArray {
        require(bytes.size % 2 == 0)
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(data.remaining()).also { data.get(it) }
    }

    fun spectrum(bytes: ByteArray, compression: String, bins: Int): FloatArray {
        require(bins in 16..131072)
        val values = when (compression) {
            "adpcm" -> {
                val data = OpenWebRxAdpcm().decode(bytes, synced = false)
                require(data.size >= bins + 10)
                FloatArray(bins) { data[it + 10] / 100f }
            }
            "none" -> {
                require(bytes.size == bins * 4)
                val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                FloatArray(bins).also { data.get(it) }
            }
            else -> error("Unsupported FFT compression")
        }
        require(values.all { it.isFinite() })
        return values
    }
}
