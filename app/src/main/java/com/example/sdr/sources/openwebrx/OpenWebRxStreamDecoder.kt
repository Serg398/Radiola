package com.example.sdr.sources.openwebrx

import kotlin.math.log10
import org.json.JSONObject

/** Per-socket wire state. No Android lifecycle, UI, retry or playback responsibilities. */
internal class OpenWebRxStreamDecoder {
    private var audioCompression = "none"
    private var fftCompression = "none"
    private var fftSize = 0
    private var audio = OpenWebRxAdpcm()
    private var hdAudio = OpenWebRxAdpcm()
    var centerHz = 0L; private set
    var spanHz = 0; private set
    var signalDbm: Float? = null; private set

    fun configure(config: JSONObject) {
        if ((config.has("center_freq") && config.optLong("center_freq") != centerHz) ||
            (config.has("samp_rate") && config.optInt("samp_rate") != spanHz)) {
            audio = OpenWebRxAdpcm(); hdAudio = OpenWebRxAdpcm()
        }
        audioCompression = config.optString("audio_compression", audioCompression)
        fftCompression = config.optString("fft_compression", fftCompression)
        fftSize = config.optInt("fft_size", fftSize)
        centerHz = config.optLong("center_freq", centerHz)
        spanHz = config.optInt("samp_rate", spanHz)
    }
    fun signal(power: Double) {
        signalDbm = if (power.isFinite() && power > 0) (10 * log10(power)).toFloat() else null
    }
    fun spectrum(payload: ByteArray): FloatArray? =
        if (spanHz <= 0 || fftSize <= 0) null else OpenWebRxCodec.spectrum(payload, fftCompression, fftSize)

    fun audio(payload: ByteArray, highDefinition: Boolean): ShortArray = when (audioCompression) {
        "adpcm" -> (if (highDefinition) hdAudio else audio).decode(payload, synced = true)
        "none" -> OpenWebRxCodec.pcm(payload)
        else -> error("Unsupported audio compression")
    }
}
