package com.example.sdr

enum class WaterfallFrameRate(val framesPerSecond: Int) {
    FPS_30(30);

    companion object {
        fun fromStored(value: String?): WaterfallFrameRate =
            entries.firstOrNull { it.name == value } ?: FPS_30
    }
}
