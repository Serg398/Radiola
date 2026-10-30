package com.example.sdr

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.sdr.receiver.ReceiverConnectionState
import com.example.sdr.receiver.ReceiverSource
import com.example.sdr.sources.openwebrx.OpenWebRxSession
import com.example.sdr.sources.websdr.WebSdrReceiver
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.*
import okhttp3.mockwebserver.*
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpenWebRxSessionTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(label: String, timeout: Long = 12_000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline) {
            var done = false
            main { done = predicate() }
            if (done) return
            Thread.sleep(50)
        }
        fail(label)
    }

    @Test fun receivesAudioFftProfilesAndSendsTuningWithoutReopeningSocket() {
        val server = MockWebServer()
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val commands = CopyOnWriteArrayList<String>()
        val sockets = CopyOnWriteArrayList<WebSocket>()
        val connections = java.util.concurrent.atomic.AtomicInteger()
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                commands.add(text)
                if (text.startsWith("SERVER DE CLIENT")) {
                    webSocket.send("CLIENT DE SERVER server=openwebrx version=test")
                    webSocket.send("""{"type":"config","value":{"audio_compression":"none","fft_compression":"none","fft_size":1024}}""")
                    webSocket.send(if (connections.incrementAndGet() == 1)
                        """{"type":"config","value":{"sdr_id":"test","profile_id":"hf","center_freq":7100000,"samp_rate":100000,"start_freq":7100000,"start_mod":"am"}}"""
                    else """{"type":"config","value":{"sdr_id":"test","profile_id":"vhf","center_freq":138000000,"samp_rate":100000,"start_freq":138000000,"start_mod":"am"}}""")
                    webSocket.send("""{"type":"profiles","value":[{"id":"test|hf","name":"40m"},{"id":"test|vhf","name":"2m"}]}""")
                    webSocket.send("""{"type":"features","value":{"voice":false,"wsjt-x":true}}""")
                    webSocket.send("""{"type":"modes","value":[
                        {"modulation":"am","name":"AM","type":"analog","bandpass":{"low_cut":-4000,"high_cut":4000}},
                        {"modulation":"wfm","name":"WFM","type":"analog","bandpass":{"low_cut":-75000,"high_cut":75000}},
                        {"modulation":"cw","name":"CW","type":"analog","bandpass":{"low_cut":500,"high_cut":1000}},
                        {"modulation":"usb","name":"USB","type":"analog","bandpass":{"low_cut":300,"high_cut":3000}},
                        {"modulation":"dmr","name":"DMR","type":"analog","requirements":["voice"]},
                        {"modulation":"ft8","name":"FT8","type":"digimode","requirements":["wsjt-x"],"underlying":["usb"],"bandpass":{"low_cut":0,"high_cut":3000}}
                    ]}""")
                } else {
                    val message = JSONObject(text)
                    if (message.optString("action") == "start") sockets.addIfAbsent(webSocket)
                    if (message.optJSONObject("params")?.optString("secondary_mod") == "ft8") {
                        webSocket.send("""{"type":"secondary_demod","value":{"message":"CQ TEST AA00","db":-12}}""")
                    }
                    if (message.optString("type") == "selectprofile" &&
                        message.optJSONObject("params")?.optString("profile") == "test|hf") {
                        // Some servers publish identity before the new geometry.
                        webSocket.send("""{"type":"config","value":{"profile_id":"hf"}}""")
                        scheduler.schedule({ webSocket.send("""{"type":"config","value":{"center_freq":7100000,"start_freq":7100000,"samp_rate":100000}}""") }, 300, TimeUnit.MILLISECONDS)
                    }
                    if (message.optString("type") == "selectprofile" &&
                        message.optJSONObject("params")?.optString("profile") == "test|vhf") {
                        webSocket.send("""{"type":"config","value":{"profile_id":"vhf"}}""")
                        scheduler.schedule({ webSocket.send("""{"type":"config","value":{"center_freq":138000000,"start_freq":138000000,"samp_rate":100000}}""") }, 300, TimeUnit.MILLISECONDS)
                    }
                }
            }
        }
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest) = if (request.path == "/ws/")
                MockResponse().withWebSocketUpgrade(listener) else MockResponse().setBody("OpenWebRX")
        }
        server.start()
        val serverUrl = server.url("/").toString()
        val fft = ByteBuffer.allocate(1 + 1024 * 4).order(ByteOrder.LITTLE_ENDIAN).put(1).apply {
            repeat(1024) { putFloat(if (it == 512) -20f else -95f) }
        }.array().toByteString()
        val pcm = ByteBuffer.allocate(1 + 240 * 2).order(ByteOrder.LITTLE_ENDIAN).put(2).apply {
            repeat(240) { putShort((120 * kotlin.math.sin(it * .2)).toInt().toShort()) }
        }.array().toByteString()
        scheduler.scheduleAtFixedRate({ sockets.forEach { it.send(pcm); it.send(fft) } }, 0, 20, TimeUnit.MILLISECONDS)
        var session: OpenWebRxSession? = null
        val activity = ActivityScenario.launch(MainActivity::class.java)
        try {
            main {
                session = OpenWebRxSession(instrumentation.targetContext)
                session!!.connect(serverUrl)
            }
            await("Audio and FFT did not start") { session!!.state == ReceiverConnectionState.Connected && session!!.frame != null }
            await("Server modes were not received") { session!!.serverModes.any { it.id == "ft8" } }
            main {
                assertEquals(2, session!!.bands.size)
                assertFalse(session!!.serverModes.any { it.id == "dmr" })
                session!!.requestServerMode("ft8")
                assertEquals("FT8", session!!.modeLabel)
                assertTrue(session!!.serverDataMode)
            }
            await("Server decoder command was not sent") {
                commands.any { it.contains("\"secondary_mod\":\"ft8\"") && it.contains("\"mod\":\"usb\"") && it.contains("\"low_cut\":0") }
            }
            await("Server decoder result was not received") { session!!.decodedText.contains("CQ TEST AA00") }
            main {
                session!!.requestServerMode("wfm")
                assertEquals(150000, session!!.bandwidthHz)
            }
            await("WFM command was not sent") { commands.any { it.contains("\"mod\":\"wfm\"") && it.contains("\"high_cut\":75000") } }
            main {
                assertEquals(100_000, session!!.frame!!.spanHz)
                val capturedAt = session!!.frame!!.timestampNanos
                val now = android.os.SystemClock.elapsedRealtimeNanos()
                assertTrue("Spectrum timestamp must be monotonic nanoseconds", capturedAt in (now - 5_000_000_000L)..now)
                session!!.requestMode(DemodulationMode.CW)
                session!!.tune(7_101_000)
                session!!.setWaterfallViewport(7_101_000, 10_000)
            }
            await("CW tuning was not sent") {
                commands.any { it.contains("\"mod\":\"cw\"") && it.contains("\"offset_freq\":250") }
            }
            main { session!!.tune(7_102_000) }
            await("Frequency-only command was not sent") {
                commands.filter { it.startsWith("{") }.map { JSONObject(it) }.any {
                    val params = it.optJSONObject("params")
                    params?.optLong("offset_freq") == 1250L && params.length() == 1
                }
            }
            main { session!!.tune(7_101_000) }
            assertEquals(1, sockets.size)
            sockets.first().close(1011, "test reconnect")
            await("Session did not reconnect") { sockets.size > 1 && session!!.state == ReceiverConnectionState.Connected }
            main {
                assertEquals(DemodulationMode.CW, session!!.mode)
                assertEquals(7_101_000L, session!!.frequencyHz)
                assertEquals(7_100_000L, session!!.frame!!.centerFrequencyHz)
            }
            assertTrue("Selected HF profile was not restored", commands.any { it.contains("selectprofile") && it.contains("test|hf") })
            main { session!!.selectBand(1) }
            await("Profile command was not sent") { commands.any { it.contains("selectprofile") && it.contains("test|vhf") } }
            Thread.sleep(100)
            main { assertEquals("Partial profile config changed RF frequency", 7_101_000L, session!!.frequencyHz) }
            await("New profile geometry was not applied") { session!!.currentBand == 1 && session!!.frequencyHz == 138_000_000L }
            sockets.last().close(1011, "test cancel retry")
            await("Retry was not scheduled") { session!!.state == ReceiverConnectionState.Connecting }
            main { session!!.disconnect() }
            val connectionCount = sockets.size
            Thread.sleep(1500)
            assertEquals("Disconnected receiver reconnected", connectionCount, sockets.size)
        } finally {
            main { session?.disconnect() }
            scheduler.shutdownNow(); sockets.forEach { it.close(1000, null) }; server.shutdown(); activity.close()
        }
    }

    /** Explicitly opt in from adb with -e openwebrx_url; never contact public servers in the regular suite. */
    @Test fun liveServer() {
        val address = InstrumentationRegistry.getArguments().getString("openwebrx_url")
        assumeTrue(address != null)
        val activity = ActivityScenario.launch(MainActivity::class.java)
        val runtime = (instrumentation.targetContext.applicationContext as RadiolaApplication).webSdr
        try {
            main {
                runtime.selectSource(ReceiverSource.OPEN_WEB_RX)
                runtime.select(runtime.openWebRxReceiver?.takeIf { it.url == address }
                    ?: WebSdrReceiver(address!!, java.net.URI(address).host, "", 0f, 0f, null, emptyList()))
                runtime.connect()
            }
            await("Live server did not provide audio and FFT: ${runtime.session.error}", 40_000) {
                runtime.session.state == ReceiverConnectionState.Connected && runtime.session.frame != null
            }
            val targetProfile = InstrumentationRegistry.getArguments().getString("openwebrx_profile")
            if (targetProfile != null) {
                var index = -1
                main {
                    index = runtime.session.bands.indexOfFirst { it.name.contains(targetProfile, ignoreCase = true) }
                    assertTrue("Requested profile not found", index >= 0)
                    runtime.session.selectBand(index)
                }
                await("Profile did not switch", 30_000) {
                    runtime.session.currentBand == index && runtime.session.frame != null && runtime.session.bands[index].spanHz > 0
                }
            }
            main {
                assertTrue(runtime.session.bands.isNotEmpty())
                assertTrue(runtime.session.frame!!.bins.any { it != 0f })
                assertTrue("Server mode list is empty", runtime.session.serverModes.orEmpty().isNotEmpty())
                runtime.session.requestMode(DemodulationMode.CW)
                runtime.session.tune(runtime.session.frequencyHz + 100)
            }
            val requestedMode = InstrumentationRegistry.getArguments().getString("openwebrx_mode")
            if (requestedMode != null) {
                main { runtime.session.requestServerMode(requestedMode) }
                await("Requested mode did not start", 20_000) {
                    runtime.session.serverModeId == requestedMode && runtime.session.state == ReceiverConnectionState.Connected &&
                        (requestedMode != "wfm" || runtime.session.audioSampleRate == 48000)
                }
            }
            if (InstrumentationRegistry.getArguments().getString("openwebrx_reconnect") == "true") {
                var selected = -1
                var frequency = 0L
                main {
                    selected = runtime.session.currentBand; frequency = runtime.session.frequencyHz
                    val socket = OpenWebRxSession::class.java.getDeclaredField("socket").apply { isAccessible = true }
                    (socket.get(runtime.session) as WebSocket).cancel()
                }
                await("Connection interruption was not detected") { runtime.session.state == ReceiverConnectionState.Connecting }
                await("Selected profile/frequency did not recover", 40_000) {
                    runtime.session.state == ReceiverConnectionState.Connected && runtime.session.currentBand == selected &&
                        runtime.session.frequencyHz == frequency
                }
            }
            activity.recreate()
            await("Playback did not survive recreation") { runtime.session.state == ReceiverConnectionState.Connected }
        } finally { main { runtime.disconnect() }; activity.close() }
    }
}
