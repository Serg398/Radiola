package com.example.sdr.ui.receiver

import kotlin.math.exp

/** Frame-rate independent mechanics; the caller normalizes AUTO thresholds to dp/s. */
internal object EncoderDynamics {
    const val frictionPerSecond = 1.8
    fun filteredSpeed(previous: Float, measured: Float, seconds: Float): Float {
        val timeConstant = if (measured > previous) 0.018f else 0.04f
        return previous + (measured - previous) * (1f - exp(-seconds / timeConstant))
    }
    fun displacement(velocity: Float, seconds: Float): Float =
        (velocity * (1.0 - exp(-frictionPerSecond * seconds)) / frictionPerSecond).toFloat()
    fun decayedVelocity(velocity: Float, seconds: Float): Float =
        (velocity * exp(-frictionPerSecond * seconds)).toFloat()
}

/** Critically damped motion, preserving velocity across target changes. */
internal class FrequencyMotion(initialHz: Double) {
    var positionHz = initialHz
        private set
    private var velocity = 0.0

    fun snapTo(hz: Double) { positionHz = hz; velocity = 0.0 }

    fun advance(targetHz: Double, seconds: Double): Double {
        if (seconds <= 0.0) return positionHz
        val omega = 160.0 // 95% of a step in about 30 ms, rather than 150 ms.
        val previous = positionHz
        val error = previous - targetHz
        val coefficient = velocity + omega * error
        val decay = exp(-omega * seconds)
        val next = targetHz + (error + coefficient * seconds) * decay
        // Direction changes must not briefly continue in the old direction.
        positionHz = next.coerceIn(minOf(previous, targetHz), maxOf(previous, targetHz))
        velocity = if (positionHz == next) (velocity - omega * coefficient * seconds) * decay else 0.0
        if (kotlin.math.abs(targetHz - positionHz) < 0.5) snapTo(targetHz)
        return positionHz
    }
}
