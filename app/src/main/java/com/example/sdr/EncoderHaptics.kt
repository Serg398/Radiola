package com.example.sdr

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings

enum class EncoderFeedback(val label: String) { OFF("OFF"), LIGHT("LIGHT"), MEDIUM("MEDIUM") }

/** Short tactile detents. Reuse effects and never queue a burst for missed notches. */
class EncoderHaptics(context: Context) {
    private val appContext = context.applicationContext
    private val vibrator = if (Build.VERSION.SDK_INT >= 31)
        appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
    else @Suppress("DEPRECATION") (appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build()
    private val effects = mapOf(
        EncoderFeedback.LIGHT to effect(0.3f, 40),
        EncoderFeedback.MEDIUM to effect(0.65f, 85)
    )
    private var lastTickMs = 0L

    private fun effect(scale: Float, amplitude: Int): VibrationEffect {
        if (Build.VERSION.SDK_INT >= 30 && vibrator?.arePrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)?.firstOrNull() == true) {
            return VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, scale).compose()
        }
        if (vibrator?.hasAmplitudeControl() == true) return VibrationEffect.createOneShot(6, amplitude)
        // Some OEMs expose neither primitives nor amplitude control; their TICK
        // fallback can last 40 ms. Use tiny on/off pulses instead of a long buzz.
        return VibrationEffect.createOneShot(if (amplitude <= 40) 2L else 4L, VibrationEffect.DEFAULT_AMPLITUDE)
    }

    fun tick(feedback: EncoderFeedback) {
        if (feedback == EncoderFeedback.OFF || vibrator?.hasVibrator() != true) return
        val now = SystemClock.uptimeMillis()
        if (now - lastTickMs < 35) return
        if (Settings.System.getInt(appContext.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) return
        lastTickMs = now
        @Suppress("DEPRECATION")
        vibrator.vibrate(effects.getValue(feedback), attributes)
    }

    fun cancel() { vibrator?.cancel() }
}
