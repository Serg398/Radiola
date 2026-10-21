package com.example.sdr

/** MSB-first bit reader. Network messages are bounded before reaching a decoder. */
internal class WebSdrBits(private val data: ByteArray, var position: Int = 0) {
    fun read(count: Int): Int {
        require(count in 0..24 && position + count <= data.size * 8) { "Truncated WebSDR bitstream" }
        var value = 0
        repeat(count) { value = (value shl 1) or ((data[position / 8].toInt() ushr (7 - position++ % 8)) and 1) }
        return value
    }
    fun peek32(): Int {
        var value = 0
        for (offset in 0 until 32) {
            val bit = position + offset
            value = (value shl 1) or if (bit < data.size * 8) ((data[bit / 8].toInt() ushr (7 - bit % 8)) and 1) else 0
        }
        return value
    }
}

/** Stateful demodulated audio decoder: service records, G.711 A-law and adaptive residual blocks. */
internal class WebSdrAudioDecoder {
    var sampleRate = 8000
        private set
    var signal = 0
        private set
    private var quantizer = 0
    private var flags = 0
    private var rice = 0
    private var integrator = 0.0
    private val coefficients = DoubleArray(20)
    private val history = DoubleArray(20)

    fun decode(data: ByteArray, output: (Int, ShortArray) -> Unit) {
        require(data.size <= 1_000_000)
        var offset = 0
        fun byte(index: Int) = data[index].toInt() and 255
        fun available(count: Int) = require(offset + count <= data.size) { "Truncated WebSDR audio record" }
        fun reset() { coefficients.fill(0.0); history.fill(0.0); integrator = 0.0 }
        while (offset < data.size) {
            val tag = byte(offset)
            when {
                tag >= 0xf0 -> { available(2); signal = ((tag and 15) shl 8) or byte(offset + 1); offset += 2 }
                tag == 0x80 -> {
                    available(129)
                    output(sampleRate, ShortArray(128) { alaw(byte(offset + 1 + it)) })
                    reset(); offset += 129
                }
                tag == 0x81 -> {
                    available(3)
                    val rate = (byte(offset + 1) shl 8) or byte(offset + 2)
                    require(rate in 4000..48000) { "Unsupported WebSDR audio rate: $rate" }
                    sampleRate = rate; offset += 3
                }
                tag == 0x82 -> { available(3); quantizer = (byte(offset + 1) shl 8) or byte(offset + 2); offset += 3 }
                tag == 0x83 -> { available(2); flags = byte(offset + 1); offset += 2 }
                tag == 0x84 -> { output(sampleRate, ShortArray(128)); reset(); offset++ }
                tag == 0x85 || tag == 0x87 -> { available(7); offset += 7 }
                tag == 0x86 -> offset++
                tag < 0x80 || tag in 0x90..0xdf -> {
                    val headerBits = if (tag < 0x80) 1 else 4
                    if (headerBits == 4) rice = 14 - (tag ushr 4)
                    require(rice in 1..5 && quantizer > 0) { "Missing WebSDR codec initialization" }
                    val bits = WebSdrBits(data, offset * 8 + headerBits)
                    val samples = ShortArray(128)
                    for (sample in samples.indices) {
                        val lookahead = bits.peek32()
                        val limit = 15 - rice
                        val zeros = if (lookahead == 0) 0 else lookahead.countLeadingZeroBits().coerceAtMost(limit)
                        val quotient = if (zeros < limit) { bits.read(zeros + 1); zeros }
                            else { bits.read(zeros); bits.read(8) }
                        fun threshold(k: Int) = when (k) { 2 -> 8; 3 -> 4; 4 -> 2; 5 -> 1; else -> 999 }
                        val shift = ((if (quotient >= threshold(rice)) 1 else 0) +
                            (if (quotient >= threshold(rice - 1)) 1 else 0)).coerceAtMost(rice - 1)
                        val magnitude = (quotient shl (rice - 1)) + (bits.read(rice - 1 - shift) shl shift)
                        val residual = if (bits.read(1) == 0) magnitude else -magnitude - (1 shl shift)
                        var prediction = 0.0
                        for (tap in history.indices) prediction += coefficients[tap] * history[tap]
                        val fixed = int32(prediction)
                        val predicted = if (fixed >= 0) fixed shr 12 else (fixed + 4095) shr 12
                        val scaled = residual * quantizer.toDouble() + quantizer / 2.0
                        val adaptation = int32(scaled) shr 4
                        val rateShift = if (flags and 16 != 0) 12 else 14
                        for (tap in history.indices.reversed()) {
                            coefficients[tap] += -(int32(coefficients[tap]) shr 7) + (int32(history[tap] * adaptation) shr rateShift)
                            if (tap > 0) history[tap] = history[tap - 1]
                        }
                        history[0] = predicted + scaled
                        samples[sample] = int32(history[0] + (int32(integrator) shr 4)).toShort()
                        integrator = if (flags and 16 != 0) 0.0 else integrator + ((int32(history[0]) shl 4) shr 3)
                    }
                    output(sampleRate, samples)
                    offset = (bits.position + 7) / 8
                }
                else -> error("Unknown WebSDR audio record: $tag")
            }
        }
    }
    companion object {
        private fun int32(value: Double) = value.toLong().toInt()
        internal fun alaw(value: Int): Short {
            val code = value xor 0x55
            val exponent = (code ushr 4) and 7
            var magnitude = (code and 15) shl 4
            magnitude += if (exponent == 0) 8 else 264
            if (exponent > 1) magnitude = magnitude shl (exponent - 1)
            return (if (code and 128 != 0) magnitude else -magnitude).toShort()
        }
    }
}

/** Formats 9/10: context-coded differences; format 10 adds per-bin small-delta refinement. */
internal class WebSdrWaterfallDecoder(private val width: Int = 1024, private val format: Int = 9) {
    private val levels = IntArray(width)
    private val refinement = IntArray(width)
    var zoom = 0
        private set
    var start = 0
        private set
    var positionKnown = false
        private set
    fun decode(packet: ByteArray): IntArray? {
        require(packet.isNotEmpty() && packet.size <= 65536)
        var data = packet
        if (packet[0].toInt() and 255 == 255) {
            require(packet.size >= 2)
            when (packet[1].toInt() and 255) {
                255 -> data = packet.copyOfRange(1, packet.size)
                1 -> {
                    require(packet.size >= 8)
                    val z = packet[2].toInt() and 255
                    if (z < 128) zoom = z
                    start = (packet[3].toInt() and 255) or ((packet[4].toInt() and 255) shl 8) or
                        ((packet[5].toInt() and 255) shl 16) or ((packet[6].toInt() and 255) shl 24)
                    positionKnown = true
                    return null
                }
                2 -> {
                    require(packet.size >= 4 && ((packet[2].toInt() and 255) or ((packet[3].toInt() and 255) shl 8)) == width)
                    levels.fill(8); refinement.fill(0); return null
                }
                else -> error("Unknown WebSDR waterfall metadata")
            }
        }
        val bits = WebSdrBits(data)
        var context = 0
        var accumulated = 0
        for (bin in 0 until width) {
            val delta = delta(bits, context)
            context = when (kotlin.math.abs(delta)) { 0 -> 0; 1 -> 1; else -> 2 }
            val change = if (format == 9) {
                accumulated += delta shl 4
                accumulated
            } else if (kotlin.math.abs(delta) == 1) (delta shl 4) shr refinement[bin] else delta shl 4
            val value = levels[bin] + change
            levels[bin] = if (format == 9) when { value < 0 -> 8; value > 255 -> 248; else -> value }
                else value.coerceIn(0, 255)
            refinement[bin] = if (delta == 0) (refinement[bin] + 1).coerceAtMost(4) else 0
        }
        return levels.copyOf()
    }
    private fun delta(bits: WebSdrBits, context: Int): Int {
        if (bits.read(1) == 0) return 0
        if (context == 1) return if (bits.read(1) == 0) 1 else -1
        val base = if (context == 0) 1 else 3
        when (bits.read(2)) {
            0 -> return base
            1 -> return -base
            2 -> return base + 2
        }
        if (bits.read(1) == 0) return -base - 2
        val negative = bits.read(1) != 0
        val branch = bits.read(2)
        val value = when (branch) {
            0 -> base + 4
            1 -> base + 6
            2 -> if (context == 2) 11 else 9 + bits.read(1) * 2
            else -> 13 + bits.read(1) * 2
        }
        return if (negative) -value else value
    }
}
