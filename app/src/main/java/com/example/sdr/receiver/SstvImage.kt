package com.example.sdr.receiver

import android.graphics.Bitmap

/** One progressively received picture. Pixels are owned and updated on the main thread. */
internal data class SstvImage(
    val bitmap: Bitmap,
    val mode: String,
    val timestamp: String,
    val lines: Int,
    val revision: Long,
)
