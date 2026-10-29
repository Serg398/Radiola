package com.example.sdr

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.*
import okhttp3.mockwebserver.*
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                commands.add(text)
                if (text.startsWith("SERVER DE CLIENT")) {
                    webSocket.send("CLIENT DE SERVER server=openwebrx version=test")
                    webSocket.send("""{"type":"config","value":{"audio_compression":"none","fft_compression":"none","fft_size":1024}}""")
                    webSocket.send("""{"type":"config","value":{"sdr_id":"test","profile_id":"hf","center_freq":7100000,"samp_rate":100000,"start_freq":7100000,"start_mod":"am"}}""")
                    webSocket.send("""{"type":"profiles","value":[{"id":"test|hf","name":"40m"},{"id":"test|vhf","name":"2m"}]}""")
                } else if (JSONObject(text).optString("action") == "start") sockets.addIfAbsent(webSocket)
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
            await("Audio and FFT did not start") { session!!.state == WebSdrConnectionState.Connected && session!!.frame != null }
            main {
                assertEquals(2, session!!.bands.size)
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
            main { session!!.selectBand(1) }
            await("Profile command was not sent") { commands.any { it.contains("selectprofile") && it.contains("test|vhf") } }
            assertEquals(1, sockets.size)
            sockets.first().close(1011, "test reconnect")
            await("Session did not reconnect") { sockets.size > 1 && session!!.state == WebSdrConnectionState.Connected }
            main {
                assertEquals(DemodulationMode.CW, session!!.mode)
                assertEquals(7_101_000L, session!!.frequencyHz)
            }
            sockets.last().close(1011, "test cancel retry")
            await("Retry was not scheduled") { session!!.state == WebSdrConnectionState.Connecting }
            main { session!!.disconnect() }
            val connections = sockets.size
            Thread.sleep(1500)
            assertEquals("Disconnected receiver reconnected", connections, sockets.size)
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
                runtime.selectSource("OpenWebRX")
                runtime.select(WebSdrReceiver(address!!, java.net.URI(address).host, "", 0f, 0f, null, emptyList()))
                runtime.connect()
            }
            await("Live server did not provide audio and FFT: ${runtime.session.error}", 40_000) {
                runtime.session.state == WebSdrConnectionState.Connected && runtime.session.frame != null
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
                runtime.session.requestMode(DemodulationMode.CW)
                runtime.session.tune(runtime.session.frequencyHz + 100)
            }
            activity.recreate()
            await("Playback did not survive recreation") { runtime.session.state == WebSdrConnectionState.Connected }
        } finally { main { runtime.disconnect() }; activity.close() }
    }
}
