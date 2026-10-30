package com.example.sdr.receiver

import com.example.sdr.DemodulationMode
import java.util.Locale
import kotlin.math.abs

fun DemodulationMode.offsetForBandwidth(hz: Int): Int = when (this) {
    DemodulationMode.USB, DemodulationMode.LSB ->
        (if (this == DemodulationMode.USB) 1 else -1) * (abs(passbandOffsetHz) - bandwidthHz / 2 + hz / 2)
    else -> 0
}

fun bandwidthLabel(hz: Int): String = if (hz < 1000) "$hz Hz" else
    String.format(Locale.US, "%s kHz", String.format(Locale.US, "%.2f", hz / 1000.0).trimEnd('0').trimEnd('.'))
