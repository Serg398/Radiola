package com.example.sdr

enum class DemodulationMode(val label: String, val nativeId: Int, val bandwidthHz: Int) {
    WFM("WFM", 0, 240_000),
    NFM("FM", 2, 12_500),
    AM("AM", 1, 12_000)
}
