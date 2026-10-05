package com.example.sdr

enum class DemodulationMode(val label: String, val nativeId: Int, val bandwidthHz: Int) {
    WFM("WFM", 0, 240_000),
    NFM("FM", 2, 12_500),
    AM("AM", 1, 12_000),
    USB("USB", 3, 2_700),
    LSB("LSB", 4, 2_700),
    CW("CW", 5, 500);

    val passbandOffsetHz: Int get() = when (this) {
        USB -> 1500
        LSB -> -1500
        else -> 0
    }
}
