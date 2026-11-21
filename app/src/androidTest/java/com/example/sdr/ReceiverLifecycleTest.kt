package com.example.sdr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.sdr.receiver.WaterfallHistory
import com.example.sdr.sources.usb.ReceiverState
import com.example.sdr.ui.receiver.ReceiverViewModel
import com.example.sdr.ui.receiver.Sidebar
import com.example.sdr.ui.receiver.WaterfallView
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReceiverLifecycleTest {
    @Test fun zoomRevealsOffCenterHistoryAfterViewRecreation() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val history = WaterfallHistory()
            history.append(SpectrumFrame(FloatArray(8192) { if (it in 7166..7170) -25f else -94f }, 100000, 1, 1000, 1, 1600))
            history.append(SpectrumFrame(FloatArray(8192) { if (it in 6142..6146) -25f else -94f }, 100200, 2, 2000, 1, 1600))
            repeat(2) {
                val view = WaterfallView(context, history)
                view.layout(0, 0, 512, 160)
                view.setTuning(100600, 400)
                val image = Bitmap.createBitmap(512, 160, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(image))
                for (y in 0..1) {
                    val peak = image.getPixel(256, y)
                    assertTrue("Historical off-center RF signal disappeared", Color.red(peak) > 200 && Color.green(peak) > 200)
                }
            }
        }
    }
    private fun clickText(text: String) {
        // Sidebar geometry is fixed in dp; OnePlus restricts accessibility roots for instrumentation.
        val density = context.resources.displayMetrics.density
        val x = (54 * density).toInt()
        val y = ((if (text == "Settings") 100 else 64) * density).toInt()
        val command = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input tap $x $y")
        java.io.FileInputStream(command.fileDescriptor).use { it.readBytes() }
        command.close()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
    @Test fun settingsRetainsWaterfallAndFramesKeepTheirCaptureMetadata() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                start(scenario)
                Thread.sleep(1200)
                lateinit var history: WaterfallHistory
                var before = 0L
                scenario.onActivity {
                    val model = ViewModelProvider(it)[ReceiverViewModel::class.java]
                    history = model.waterfallHistory; before = history.revision
                    assertTrue(history.size > 10)
                    val spectrum = model.spectrumState.value!!
                    val waterfall = model.waterfallState.value!!
                    assertSame(spectrum, waterfall)
                    assertTrue(spectrum.timestampNanos > 0 && spectrum.sessionId > 0)
                    assertEquals(2880000, spectrum.spanHz)
                }
                clickText("Settings")
                Thread.sleep(1000)
                scenario.onActivity {
                    assertSame(history, ViewModelProvider(it)[ReceiverViewModel::class.java].waterfallHistory)
                    assertTrue(history.revision > before)
                }
                clickText("Receiver")
                scenario.onActivity { assertSame(history, ViewModelProvider(it)[ReceiverViewModel::class.java].waterfallHistory) }
                val first = NativeSdr.visualStats()[4]
                Thread.sleep(2000)
                val frames = NativeSdr.visualStats()[4] - first
                assertTrue("Unexpected FFT cadence: $frames per 2s", frames in 50..65)
            } finally { scenario.onActivity { runtime.stop() } }
        }
    }
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
