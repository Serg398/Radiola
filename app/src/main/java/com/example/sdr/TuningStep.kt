package com.example.sdr

enum class TuningStep(val label: String, val fixedHz: Long?) {
    AUTO("AUTO", null),
    KHZ_1("1 kHz", 1_000L),
    KHZ_10("10 kHz", 10_000L),
    KHZ_50("50 kHz", 50_000L),
    KHZ_100("100 kHz", 100_000L),
    KHZ_500("500 kHz", 500_000L),
    MHZ_1("1 MHz", 1_000_000L);

    // Speeds in dp/s give the same feel on displays with different pixel densities.
    // Different up/down thresholds prevent step chatter near a speed boundary.
    fun hzForSpeed(dpPerSecond: Float, previousHz: Long = 1_000L): Long = fixedHz ?: when {
        dpPerSecond >= 1500f -> 50_000L
        previousHz == 50_000L && dpPerSecond >= 350f -> 50_000L
        dpPerSecond >= 300f -> 10_000L
        previousHz >= 10_000L && dpPerSecond >= 150f -> 10_000L
        else -> 1_000L
    }

    fun shortLabel(hz: Long): String = if (hz >= 1_000_000L) "${hz / 1_000_000}M" else "${hz / 1000}k"

    companion object {
        fun fromStored(value: String?): TuningStep = entries.firstOrNull { it.name == value } ?: KHZ_100
    }
}
