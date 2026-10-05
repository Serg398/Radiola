package com.example.sdr

/** FFT bins and their RF center belong to one native snapshot. */
data class SpectrumFrame(val bins: FloatArray, val centerFrequencyHz: Long, val generation: Long)
