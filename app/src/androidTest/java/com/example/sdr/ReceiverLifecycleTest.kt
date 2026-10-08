package com.example.sdr

import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReceiverLifecycleTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val runtime get() = (context.applicationContext as RadiolaApplication).receiver
    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 8000
        while (!predicate() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("Receiver transition timed out", predicate())
    }
    private fun start(scenario: ActivityScenario<MainActivity>) {
        val usb = context.getSystemService(UsbManager::class.java)
        val device = usb.deviceList.values.firstOrNull { it.vendorId == 0x0bda && it.productId == 0x2838 }
        assumeTrue("Connected RTL-SDR with permission required", device != null && usb.hasPermission(device))
        scenario.onActivity {
            runtime.stop()
        }
        waitUntil { runtime.session.snapshot().state in listOf(ReceiverState.Idle, ReceiverState.Error) }
        scenario.onActivity { runtime.requestStart(device!!) }
        waitUntil { runtime.session.snapshot().state == ReceiverState.Running }
    }
    @Test fun activityRecreationKeepsTheSameLiveUsbSession() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                start(scenario)
                val id = runtime.session.snapshot().sessionId
                val frequency = runtime.session.snapshot().requestedHz
                val before = runtime.session.stats()
                scenario.recreate()
                Thread.sleep(1000)
                assertEquals(ReceiverState.Running, runtime.session.snapshot().state)
                assertEquals(id, runtime.session.snapshot().sessionId)
                assertEquals(frequency, runtime.session.snapshot().requestedHz)
                val after = runtime.session.stats()
                assertTrue(after[0] > before[0])
                assertEquals(before[8], after[8])
            } finally { scenario.onActivity { runtime.stop() } }
            waitUntil { runtime.session.snapshot().state == ReceiverState.Idle }
        }
    }
    @Test fun competingAudioFocusStopsReceptionAndExposesReason() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val manager = context.getSystemService(AudioManager::class.java)
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                .setOnAudioFocusChangeListener { }.build()
            try {
                start(scenario)
                scenario.onActivity { assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, manager.requestAudioFocus(request)) }
                waitUntil { runtime.session.snapshot().state == ReceiverState.Error }
                assertTrue(runtime.session.snapshot().error!!.contains("focus"))
                assertEquals(0L, runtime.session.stats()[2])
            } finally {
                manager.abandonAudioFocusRequest(request)
                scenario.onActivity { runtime.stop() }
            }
        }
    }
    @Test fun serviceShutdownCannotCancelTheFollowingStart() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                start(scenario)
                repeat(3) {
                    scenario.onActivity { runtime.stop() }
                    waitUntil { runtime.session.snapshot().state == ReceiverState.Idle }
                    val usb = context.getSystemService(UsbManager::class.java)
                    val device = usb.deviceList.values.first { it.vendorId == 0x0bda && it.productId == 0x2838 }
                    scenario.onActivity { runtime.requestStart(device) }
                    waitUntil { runtime.session.snapshot().state == ReceiverState.Running }
                }
            } finally { scenario.onActivity { runtime.stop() } }
        }
    }
    @Test fun freshActivityMustNotTreatExistingUsbAsADisconnect() {
        val first = ActivityScenario.launch(MainActivity::class.java)
        try { start(first) } catch (error: Throwable) { first.close(); throw error }
        val id = runtime.session.snapshot().sessionId
        val frequency = runtime.session.snapshot().requestedHz
        first.close()
        ActivityScenario.launch(MainActivity::class.java).use { returned ->
            try {
                Thread.sleep(1000)
                assertEquals(ReceiverState.Running, runtime.session.snapshot().state)
                assertEquals(id, runtime.session.snapshot().sessionId)
                assertEquals(frequency, runtime.session.snapshot().requestedHz)
                assertTrue(runtime.session.stats()[0] > 0)
            } finally { returned.onActivity { runtime.stop() } }
        }
    }
}
