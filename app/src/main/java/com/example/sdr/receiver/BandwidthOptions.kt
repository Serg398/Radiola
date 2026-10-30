package com.example.sdr.receiver

import com.example.sdr.DemodulationMode

data class BandwidthOptions(val defaultHz: Int, val minimumHz: Int, val maximumHz: Int, val presets: List<Int>) {
    companion object {
        fun forMode(mode: DemodulationMode) = BandwidthOptions(mode.bandwidthHz, mode.minBandwidthHz,
            mode.maxBandwidthHz, mode.bandwidthPresetsHz.toList())
    }
}
