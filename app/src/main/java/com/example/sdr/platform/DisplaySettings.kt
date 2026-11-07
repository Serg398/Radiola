package com.example.sdr.platform

import android.content.Context
import com.example.sdr.DemodulationMode
import com.example.sdr.receiver.ReceiverGain
import com.example.sdr.receiver.ReceiverSquelch
import com.example.sdr.receiver.TuningStep
import com.example.sdr.ui.receiver.WaterfallFrameRate

class DisplaySettings(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("display", Context.MODE_PRIVATE)
    fun screenAlwaysOn(): Boolean = preferences.getBoolean("screen_always_on", true)

    fun saveScreenAlwaysOn(enabled: Boolean) {
        preferences.edit().putBoolean("screen_always_on", enabled).apply()
    }

    fun bandwidth(mode: DemodulationMode): Int? = preferences.getInt("bandwidth_${mode.name}", 0)
        .takeIf { it in mode.minBandwidthHz..mode.maxBandwidthHz }
    fun saveBandwidth(mode: DemodulationMode, hz: Int?) {
        if (hz == null) preferences.edit().remove("bandwidth_${mode.name}").apply()
        else preferences.edit().putInt("bandwidth_${mode.name}", hz.coerceIn(mode.minBandwidthHz, mode.maxBandwidthHz)).apply()
    }

    fun encoderFeedback(): EncoderFeedback = EncoderFeedback.entries.firstOrNull {
        it.name == preferences.getString("encoder_feedback", EncoderFeedback.LIGHT.name)
    } ?: EncoderFeedback.LIGHT

    fun saveEncoderFeedback(feedback: EncoderFeedback) {
        preferences.edit().putString("encoder_feedback", feedback.name).apply()
    }

    fun wfmStereoEnabled(): Boolean = preferences.getBoolean("wfm_stereo", false)

    fun saveWfmStereo(enabled: Boolean) {
        preferences.edit().putBoolean("wfm_stereo", enabled).apply()
    }

    fun receiverSquelch() = ReceiverSquelch(
        preferences.getBoolean("squelch_enabled", false),
        preferences.getFloat("squelch_threshold", -65f).coerceIn(-90f, -6f)
    )

    fun saveReceiverSquelch(value: ReceiverSquelch) {
        preferences.edit().putBoolean("squelch_enabled", value.enabled)
            .putFloat("squelch_threshold", value.thresholdDbfs).apply()
    }

    fun waterfallFrameRate(): WaterfallFrameRate =
        WaterfallFrameRate.fromStored(preferences.getString("waterfall_frame_rate", null))

    fun saveWaterfallFrameRate(option: WaterfallFrameRate) {
        preferences.edit().putString("waterfall_frame_rate", option.name).apply()
    }

    fun tuningStep(): TuningStep = TuningStep.fromStored(preferences.getString("tuning_step", null))

    fun saveTuningStep(option: TuningStep) {
        preferences.edit().putString("tuning_step", option.name).apply()
    }

    fun receiverGain(): ReceiverGain = ReceiverGain(
        rfAgc = preferences.getBoolean("rf_agc", true),
        audioAgc = preferences.getBoolean("audio_agc", true),
        gainTenthsDb = preferences.getInt("rf_gain", 197)
    )

    fun saveReceiverGain(gain: ReceiverGain) {
        preferences.edit().putBoolean("rf_agc", gain.rfAgc)
            .putBoolean("audio_agc", gain.audioAgc).putInt("rf_gain", gain.gainTenthsDb).apply()
    }
}
