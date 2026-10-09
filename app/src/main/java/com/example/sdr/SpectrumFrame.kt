package com.example.sdr

/** One captured FFT snapshot. Its uniquely owned bins must not be mutated by consumers. */
data class SpectrumFrame(
    val bins: FloatArray,
    val centerFrequencyHz: Long,
    val generation: Long,
    val timestampNanos: Long = 0,
    val sessionId: Long = 0,
    val spanHz: Int = 2_400_000
)
