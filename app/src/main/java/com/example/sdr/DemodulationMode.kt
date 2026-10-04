package com.example.sdr

enum class DemodulationMode(val label: String, val nativeId: Int, val bandwidthHz: Int) {
    WFM("FM", 0, 240_000),
    AM("AM", 1, 12_000)
}
