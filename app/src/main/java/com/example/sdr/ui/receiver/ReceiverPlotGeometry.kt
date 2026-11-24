package com.example.sdr.ui.receiver

import androidx.compose.ui.unit.dp

/** Shared dimensions for RF-coordinate drawing and touch handling. */
internal object ReceiverPlotGeometry {
    val frequencyInset = 31.dp
    val encoderWidth = 74.dp
    val encoderGap = 4.dp
    val encoderReserve = encoderWidth + encoderGap
    val headerHeight = 18.dp
}
