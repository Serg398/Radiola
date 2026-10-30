package com.example.sdr

import com.example.sdr.receiver.ReceiverGain
import com.example.sdr.sources.usb.ReceiverController
import com.example.sdr.sources.usb.ReceiverSnapshot
import com.example.sdr.sources.usb.ReceiverState
import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class ReceiverControllerTest {
    @Test fun deferredStartWaitsForForegroundServiceAndRemainsCancellable() {
        val f = Fixture()
        f.controller.start("usb", 100, DemodulationMode.AM, ReceiverGain(), deferred = true)
        f.controller.tune(200)
        f.worker.drain()
        assertTrue(f.hardware.calls.isEmpty())
        assertEquals(ReceiverState.Starting, f.controller.snapshot().state)
        f.controller.stop(); f.worker.drain()
        f.controller.activateStart(); f.worker.drain()
        assertEquals(listOf("stop"), f.hardware.calls)
    }
    @Test fun foregroundServiceActivatesTheLatestPreparedSettings() {
        val f = Fixture()
        f.controller.start("usb", 100, DemodulationMode.AM, ReceiverGain(), deferred = true)
        f.controller.tune(200)
        f.controller.activateStart(); f.worker.drain()
        assertEquals("start:200", f.hardware.calls.first())
        assertEquals(ReceiverState.Running, f.controller.snapshot().state)
    }
    private class Worker : Executor {
        val queue = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { queue.addLast(command) }
        fun one() { queue.removeFirst().run() }
        fun drain() { repeat(100) { if (queue.isEmpty()) return; one() }; error("Unbounded command loop") }
    }
    private class Hardware : ReceiverController.Backend {
        val calls = mutableListOf<String>()
        var connected = true
        var starting: (() -> Unit)? = null
        var tuning: (() -> Unit)? = null
        var tuneResult = 0
        var gainResult = 0
        override fun available(device: String) = connected
        override fun start(device: String, hz: Long, mode: DemodulationMode, gain: ReceiverGain): Int {
            calls += "start:$hz"; starting?.invoke(); return 0
        }
        override fun stop() { calls += "stop" }
        override fun tune(hz: Long): Int { calls += "tune:$hz"; tuning?.invoke(); return tuneResult }
        override fun mode(mode: DemodulationMode) { calls += "mode:${mode.name}" }
        override fun gain(gain: ReceiverGain): Int { calls += "gain:${gain.gainTenthsDb}"; return gainResult }
        override fun warmAudio(enabled: Boolean) { calls += "warm:$enabled" }
        override fun close() { calls += "close" }
    }
    private class Fixture {
        val worker = Worker()
        val hardware = Hardware()
        val events = mutableListOf<ReceiverSnapshot>()
        val controller = ReceiverController(worker, hardware, events::add)
        fun start() { controller.start("usb", 100L, DemodulationMode.AM, ReceiverGain()); worker.drain() }
    }

    @Test fun stopCancelsQueuedStart() {
        val f = Fixture()
        f.controller.start("usb", 100, DemodulationMode.AM, ReceiverGain())
        f.controller.stop()
        f.worker.drain()
        assertEquals(listOf("stop"), f.hardware.calls)
        assertEquals(ReceiverState.Idle, f.controller.snapshot().state)
        assertFalse(f.events.any { it.state == ReceiverState.Running })
    }
    @Test fun stopDuringStartNeverPublishesRunning() {
        val f = Fixture()
        f.hardware.starting = { f.controller.stop() }
        f.start()
        assertEquals(listOf("start:100", "stop"), f.hardware.calls)
        assertFalse(f.events.any { it.state == ReceiverState.Running })
    }
    @Test fun unplugDuringStartClosesHardwareAndReportsError() {
        val f = Fixture()
        f.hardware.starting = { f.hardware.connected = false }
        f.start()
        assertEquals(listOf("start:100", "stop"), f.hardware.calls)
        assertEquals(ReceiverState.Error, f.controller.snapshot().state)
        assertTrue(f.controller.snapshot().error!!.contains("USB"))
        assertFalse(f.events.any { it.state == ReceiverState.Running })
    }
    @Test fun stopPreemptsContinuousTuningAndCancelsOtherSettings() {
        val f = Fixture(); f.start()
        f.hardware.calls.clear()
        f.hardware.tuning = {
            repeat(10000) { f.controller.tune(it.toLong()) }
            f.controller.mode(DemodulationMode.WFM)
            f.controller.gain(ReceiverGain(gainTenthsDb = 372))
            f.controller.stop()
        }
        f.controller.tune(200); f.worker.drain()
        assertEquals(listOf("tune:200", "stop"), f.hardware.calls)
        assertEquals(ReceiverState.Idle, f.controller.snapshot().state)
        assertNull(f.controller.snapshot().appliedHz)
    }
    @Test fun outdatedTuneAcknowledgementCannotReplaceLatestRequest() {
        val f = Fixture(); f.start()
        f.hardware.tuning = { f.hardware.tuning = null; f.controller.tune(300) }
        f.controller.tune(200); f.worker.one()
        assertEquals(300L, f.controller.snapshot().requestedHz)
        assertEquals(100L, f.controller.snapshot().appliedHz)
        f.worker.drain()
        assertEquals(300L, f.controller.snapshot().appliedHz)
    }
    @Test fun obsoleteFailureCannotOverwriteNewTuneOrStop() {
        val f = Fixture(); f.start()
        f.hardware.tuneResult = -5
        f.hardware.tuning = { f.hardware.tuning = null; f.controller.tune(300) }
        f.controller.tune(200); f.worker.one()
        assertNull(f.controller.snapshot().error)
        f.hardware.tuneResult = 0; f.worker.drain()
        assertNull(f.controller.snapshot().error)
        f.hardware.tuning = { f.controller.stop() }
        f.hardware.tuneResult = -5
        f.controller.tune(400); f.worker.drain()
        assertNull(f.controller.snapshot().error)
    }
    @Test fun queuedSettingsAreCoalescedAndCommandsYieldToExecutor() {
        val f = Fixture(); f.start(); f.hardware.calls.clear()
        repeat(1000) {
            f.controller.tune(it.toLong())
            f.controller.gain(ReceiverGain(gainTenthsDb = it))
        }
        f.controller.mode(DemodulationMode.USB)
        f.controller.mode(DemodulationMode.LSB)
        assertEquals(1, f.worker.queue.size)
        f.worker.one()
        assertEquals(1, f.hardware.calls.size)
        f.worker.drain()
        assertEquals(listOf("tune:999", "gain:999", "mode:LSB"), f.hardware.calls)
    }
    @Test fun frequencyChangedDuringStartIsAppliedWithoutDisplayRollback() {
        val f = Fixture()
        f.hardware.starting = { f.controller.tune(200) }
        f.start()
        assertEquals(listOf("start:100", "tune:200"), f.hardware.calls)
        assertEquals(200L, f.controller.snapshot().requestedHz)
        assertEquals(200L, f.controller.snapshot().appliedHz)
    }
    @Test fun disconnectWhileStoppingAndCloseAreSafe() {
        val f = Fixture(); f.start()
        f.controller.stop(); f.hardware.connected = false
        f.controller.close(); f.controller.close()
        f.worker.drain()
        assertEquals(listOf("start:100", "stop", "close"), f.hardware.calls)
        f.controller.tune(123); f.controller.warmAudio(true)
        assertTrue(f.worker.queue.isEmpty())
    }
    @Test fun errorsRemainSeparateFromRequestedFrequency() {
        val f = Fixture(); f.start()
        f.hardware.gainResult = -5
        f.controller.gain(ReceiverGain()); f.worker.drain()
        val error = f.controller.snapshot().error
        f.controller.tune(200)
        assertEquals(error, f.controller.snapshot().error)
        assertEquals(200L, f.controller.snapshot().requestedHz)
        f.worker.drain()
        assertEquals(error, f.controller.snapshot().error)
        f.controller.stop(); f.worker.drain()
        assertEquals(ReceiverState.Error, f.controller.snapshot().state)
        f.start()
        assertNull(f.controller.snapshot().error)
    }
    @Test fun stoppedSessionCommandsCannotAffectRestart() {
        val f = Fixture(); f.start()
        val firstId = f.controller.snapshot().sessionId
        f.controller.tune(200)
        f.controller.gain(ReceiverGain(gainTenthsDb = 372))
        f.controller.mode(DemodulationMode.USB)
        f.controller.stop()
        f.worker.drain()
        f.controller.start("usb", 500, DemodulationMode.AM, ReceiverGain())
        f.worker.drain()
        assertEquals(listOf("start:100", "stop", "start:500"), f.hardware.calls)
        assertTrue(f.controller.snapshot().sessionId > firstId)
        assertEquals(500L, f.controller.snapshot().appliedHz)
        assertTrue(f.events.zipWithNext().all { (a, b) -> b.version > a.version })
    }
}
