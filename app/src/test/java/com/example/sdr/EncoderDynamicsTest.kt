package com.example.sdr

import com.example.sdr.receiver.TuningStep
import com.example.sdr.ui.receiver.EncoderDynamics
import com.example.sdr.ui.receiver.FrequencyMotion
import org.junit.Assert.*
import org.junit.Test

class EncoderDynamicsTest {
    @Test fun inertiaIsIndependentOfFrameRate() {
        fun integrate(fps: Int): Pair<Double, Float> {
            var v = 2500f
            var distance = 0.0
            repeat(fps * 2) {
                val dt = 1f / fps
                distance += EncoderDynamics.displacement(v, dt)
                v = EncoderDynamics.decayedVelocity(v, dt)
            }
            return distance to v
        }
        val reference = integrate(30)
        for (fps in listOf(60, 90, 120, 165)) {
            val result = integrate(fps)
            assertEquals(reference.first, result.first, 0.02)
            assertEquals(reference.second.toDouble(), result.second.toDouble(), 0.02)
        }
    }
    @Test fun springSettlesQuicklyWithoutOvershoot() {
        val motion = FrequencyMotion(100_000_000.0)
        var previous = motion.positionHz
        repeat(30) {
            val x = motion.advance(100_050_000.0, 0.001)
            assertTrue(x >= previous && x <= 100_050_000.0)
            previous = x
        }
        assertTrue(motion.positionHz > 100_047_500.0)
        motion.snapTo(100_050_000.0)
        repeat(10) { assertEquals(100_050_000.0, motion.advance(100_050_000.0, 1.0 / 120), 0.0) }
    }
    @Test fun reversingNeverContinuesInOldDirection() {
        val motion = FrequencyMotion(100_000_000.0)
        motion.advance(101_000_000.0, 0.008)
        var previous = motion.positionHz
        repeat(10) {
            val x = motion.advance(99_000_000.0, 0.008)
            assertTrue(x <= previous && x >= 99_000_000.0)
            previous = x
        }
    }
    @Test fun velocityDoesNotRestartAtEachTarget() {
        val motion = FrequencyMotion(100_000_000.0)
        var previous = motion.positionHz
        repeat(40) {
            val target = 100_000_000.0 + (it + 1) * 10_000
            val x = motion.advance(target, 1.0 / 120)
            assertTrue(x > previous && x <= target)
            assertTrue(target - x < 30_000)
            previous = x
        }
    }
    @Test fun autoSpeedRespondsWithoutLongRamp() {
        val speed = EncoderDynamics.filteredSpeed(0f, 2000f, 0.02f)
        assertTrue(speed > 1200f)
        assertEquals(10_000L, TuningStep.AUTO.hzForSpeed(speed))
        assertEquals(25_000L, TuningStep.AUTO.hzForSpeed(3200f))
        assertEquals(50_000L, TuningStep.AUTO.hzForSpeed(4200f))
        assertEquals(1_000L, TuningStep.AUTO.hzForSpeed(0f))
    }
}
