package com.example.sdr

/** Same 3 dB hysteresis, 120 ms hold and 5 ms gain ramp as the USB squelch. */
internal class WebSdrSquelch {
    private var open = false
    private var holdSeconds = 0.0
    private var gain = 0f

    fun process(pcm: ShortArray, count: Int, rate: Int, setting: ReceiverSquelch, dbm: Float?): Boolean {
        val level = dbm?.plus(37f) ?: Float.NEGATIVE_INFINITY
        when {
            !setting.enabled -> { open = true; holdSeconds = 0.0 }
            level >= setting.thresholdDbfs -> { open = true; holdSeconds = .12 }
            open && level >= setting.thresholdDbfs - 3f -> holdSeconds = .12
            else -> { holdSeconds = (holdSeconds - count.toDouble() / rate).coerceAtLeast(0.0)
                if (holdSeconds == 0.0) open = false }
        }
        val target = if (open) 1f else 0f
        val step = 1f / (rate * .005f)
        for (i in 0 until count) {
            gain += (target - gain).coerceIn(-step, step)
            pcm[i] = (pcm[i] * gain).toInt().toShort()
        }
        return open
    }
}
