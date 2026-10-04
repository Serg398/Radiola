package com.example.sdr

enum class TuningStep(val label: String, val fixedHz: Long?) {
    AUTO("AUTO", null),
    KHZ_1("1 kHz", 1_000L),
    KHZ_10("10 kHz", 10_000L),
    KHZ_50("50 kHz", 50_000L),
    KHZ_100("100 kHz", 100_000L),
    KHZ_500("500 kHz", 500_000L),
    MHZ_1("1 MHz", 1_000_000L);

    fun hzForSpeed(pixelsPerSecond: Float): Long = fixedHz ?: when {
        pixelsPerSecond < 250f -> 1_000L
        pixelsPerSecond < 700f -> 10_000L
        pixelsPerSecond < 1400f -> 50_000L
        pixelsPerSecond < 3000f -> 100_000L
        else -> 500_000L
    }

    fun shortLabel(hz: Long): String = if (hz >= 1_000_000L) "${hz / 1_000_000}M" else "${hz / 1000}k"

    companion object {
        fun fromStored(value: String?): TuningStep = entries.firstOrNull { it.name == value } ?: KHZ_100
    }
}
