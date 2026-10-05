package com.example.sdr

/** Threshold uses the same relative dBFS scale as the S-meter. */
data class ReceiverSquelch(val enabled: Boolean = false, val thresholdDbfs: Float = -65f)
