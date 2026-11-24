package com.example.sdr.audio

import androidx.core.content.edit
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.sdr.DemodulationMode
import kotlin.math.abs

internal enum class NoiseReductionLevel(val strength: Float?) {
    OFF(null), LOW(.3f), MEDIUM(.55f), HIGH(.8f);
    companion object {
        fun nearest(strength: Float) = entries.filter { it.strength != null }.minBy { abs(it.strength!! - strength) }
    }
}

internal object HamNoiseSettings {
    data class Config(val enabled: Boolean = false, val strength: Float = .55f)
    private var ui by mutableStateOf(Config())
    @Volatile var current = Config(); private set
    private var preferences: android.content.SharedPreferences? = null
    val config get() = ui
    val level get() = if (ui.enabled) NoiseReductionLevel.nearest(ui.strength) else NoiseReductionLevel.OFF
    fun initialize(context: Context) {
        preferences = context.getSharedPreferences("noise_reduction", Context.MODE_PRIVATE)
        val strength = NoiseReductionLevel.nearest(preferences!!.getFloat("strength", .55f)).strength!!
        publish(Config(preferences!!.getBoolean("enabled", false), strength))
    }
    private fun publish(value: Config) { current = value; ui = value }
    fun selectLevel(level: NoiseReductionLevel) {
        publish(if (level == NoiseReductionLevel.OFF) ui.copy(enabled = false) else Config(true, level.strength!!))
        save()
    }
    fun save() { preferences?.edit { putBoolean("enabled", ui.enabled).putFloat("strength", ui.strength) } }
    fun model(mode: DemodulationMode): Int = when (mode) {
        DemodulationMode.CW -> 1
        DemodulationMode.USB, DemodulationMode.LSB, DemodulationMode.AM, DemodulationMode.NFM -> 2
        else -> 0
    }
}

internal object HamNoiseNative {
    init { System.loadLibrary("sdr_native") }
    external fun create(rate: Int, channels: Int, model: Int): Long
    external fun process(handle: Long, samples: ShortArray, count: Int, model: Int, strength: Float): Boolean
    external fun destroy(handle: Long)
}

/** Owned by the playback worker; returns separate PCM so decoder inputs stay untouched. */
internal class HamNoiseProcessor : AutoCloseable {
    private var handle = 0L
    private var rate = 0
    private var channels = 0
    private var scratch = ShortArray(0)
    fun process(input: ShortArray, count: Int, sampleRate: Int, channelCount: Int, model: Int,
        config: HamNoiseSettings.Config = HamNoiseSettings.current): ShortArray {
        val selected = if (config.enabled) model else 0
        if (handle != 0L && (rate != sampleRate || channels != channelCount)) close()
        if (handle == 0L) {
            if (selected == 0) return input
            rate = sampleRate; channels = channelCount
            handle = HamNoiseNative.create(rate, channels, selected)
            check(handle != 0L) { "Cannot initialize HamNoise" }
        }
        if (scratch.size < count) scratch = ShortArray(count)
        input.copyInto(scratch, 0, 0, count)
        if (!HamNoiseNative.process(handle, scratch, count, selected, config.strength)) close()
        return scratch
    }
    override fun close() { if (handle != 0L) HamNoiseNative.destroy(handle); handle = 0 }
}
