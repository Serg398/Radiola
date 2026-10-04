package com.example.sdr

data class ReceiverGain(
    val rfAgc: Boolean = true,
    val audioAgc: Boolean = true,
    val gainTenthsDb: Int = 197
) {
    companion object {
        // R82xx defaults; replaced by the driver's supported gains after opening USB.
        val defaultSteps = listOf(0, 9, 14, 27, 37, 77, 87, 125, 144, 157, 166, 197, 207,
            229, 254, 280, 297, 328, 338, 364, 372, 386, 402, 421, 434, 439, 445, 480, 496)
    }
}
