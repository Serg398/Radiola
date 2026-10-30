package com.example.sdr.receiver

internal enum class ReceiverSource(val storedValue: String, val online: Boolean) {
    USB("USB", false), WEB_SDR("WebSDR", true), OPEN_WEB_RX("OpenWebRX", true);

    companion object {
        fun fromStored(value: String?) = entries.firstOrNull { it.storedValue == value } ?: USB
    }
}

internal enum class ReceiverConnectionState { Idle, Connecting, Connected, Error }

internal data class ReceiverModeOption(val id: String, val label: String)

internal data class ReceiverBand(
    val index: Int, val name: String, val centerHz: Long, val spanHz: Int, val defaultHz: Long,
    val maxZoom: Int = 0, val maxLinearHz: Int = 12_000,
) {
    val lowHz get() = centerHz - spanHz / 2
    val highHz get() = centerHz + spanHz / 2
    fun contains(hz: Long) = hz in lowHz..highHz
}

internal data class ReceiverAudioMetrics(val queuedMs: Int = 0, val droppedMs: Long = 0,
    val underruns: Int = 0, val recoveries: Int = 0, val targetMs: Int = 40, val route: Int = 0)
