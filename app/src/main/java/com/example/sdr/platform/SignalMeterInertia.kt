package com.example.sdr.platform

enum class SignalMeterInertia(val label: String, val attack: Float, val release: Float) {
    DISABLED("Disabled", 0f, 0f),
    LOW("Low", 20f, 12f),
    MEDIUM("Medium", 12f, 7f),
    HIGH("High", 7f, 4f);

    companion object {
        fun fromStored(value: String?): SignalMeterInertia =
            entries.firstOrNull { it.name == value } ?: MEDIUM
    }
}
