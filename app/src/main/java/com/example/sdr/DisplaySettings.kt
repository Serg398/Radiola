package com.example.sdr

import android.content.Context

class DisplaySettings(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("display", Context.MODE_PRIVATE)

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
