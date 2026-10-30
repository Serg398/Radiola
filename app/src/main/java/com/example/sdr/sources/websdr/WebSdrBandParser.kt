package com.example.sdr.sources.websdr

import com.example.sdr.receiver.ReceiverBand

internal object WebSdrBandParser {
    /** Read numeric band descriptors, without executing any server-supplied JavaScript. */
    fun parse(script: String): List<ReceiverBand> {
        val table = script.substringAfter("var bandinfo", "").substringBefore("var freqbands")
        val chunks = Regex("\\{\\s*centerfreq\\s*:").split(table).drop(1)
        val result = chunks.mapIndexed { index, chunk ->
            fun number(key: String, fallback: Double = Double.NaN) =
                Regex("\\b$key\\s*:\\s*([-+0-9.eE]+)").find(chunk)?.groupValues?.get(1)?.toDoubleOrNull() ?: fallback
            val center = chunk.substringBefore(',').trim().toDouble() * 1000
            val span = number("samplerate") * 1000
            val vfo = number("vfo", center / 1000) * 1000
            require(center.isFinite() && center >= 0 && span.isFinite() && span in 100.0..2_000_000_000.0)
            ReceiverBand(index, Regex("\\bname\\s*:\\s*['\"]([^'\"]*)['\"]").find(chunk)?.groupValues?.get(1) ?: "Band ${index + 1}",
                center.toLong(), span.toInt(), vfo.toLong(), number("maxzoom", 0.0).toInt().coerceIn(0, 20),
                (number("maxlinbw", 12.0) * 1000).takeIf { it.isFinite() }?.toInt()?.coerceIn(1000, 100_000) ?: 12_000)
        }
        require(result.isNotEmpty()) { "No WebSDR tuner bands found" }
        return result
    }
}
