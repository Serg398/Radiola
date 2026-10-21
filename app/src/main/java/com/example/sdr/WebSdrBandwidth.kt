package com.example.sdr

data class BandwidthOptions(val defaultHz: Int, val minimumHz: Int, val maximumHz: Int, val presets: List<Int>) {
    companion object {
        fun forMode(mode: DemodulationMode) = BandwidthOptions(mode.bandwidthHz, mode.minBandwidthHz,
            mode.maxBandwidthHz, mode.bandwidthPresetsHz.toList())
    }
}

internal object WebSdrBandwidth {
    fun default(mode: DemodulationMode) = when (mode) {
        DemodulationMode.AM -> 9000
        DemodulationMode.NFM -> 10000
        DemodulationMode.CW -> 500
        else -> 2400
    }
    // The reference client limits filter edges, rather than total channel width.
    fun maximum(mode: DemodulationMode, band: WebSdrTunerBand?): Int {
        val edge = ((band?.maxLinearHz ?: 12_000) * .95).toInt()
        return when (mode) {
            DemodulationMode.NFM -> 30_000
            DemodulationMode.AM -> edge * 2
            DemodulationMode.CW -> (edge - 750) * 2
            else -> edge - 150
        }.coerceIn(100, 100_000)
    }
    fun options(mode: DemodulationMode, limit: Int): BandwidthOptions {
        val values = when (mode) {
            DemodulationMode.CW -> listOf(100, 200, 300, 500, 800, 1000, 1500, 2400)
            DemodulationMode.USB, DemodulationMode.LSB -> listOf(300, 500, 1000, 1500, 1800, 2400, 2700, 3000, 3600, 5000)
            else -> listOf(1000, 2400, 3000, 5000, 6000, 8000, 9000, 10000, 12000, 15000, 20000, 25000, 30000)
        }
        return BandwidthOptions(default(mode).coerceAtMost(limit), 100, limit, values.filter { it <= limit })
    }
}
