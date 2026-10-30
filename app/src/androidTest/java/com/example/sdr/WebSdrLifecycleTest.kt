package com.example.sdr

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.sdr.receiver.ReceiverConnectionState
import com.example.sdr.receiver.ReceiverSource
import com.example.sdr.sources.websdr.WebSdrReceiver
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.*
import okhttp3.mockwebserver.*
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebSdrLifecycleTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(label: String, predicate: () -> Boolean) {
        val end = System.currentTimeMillis() + 12_000
        while (System.currentTimeMillis() < end) {
            var done = false; main { done = predicate() }
            if (done) return
            Thread.sleep(50)
        }
        fail(label)
    }

    @Test fun streamsSurviveViewportChangesWaterFailureAndActivityRecreation() {
        val server = MockWebServer()
        val timer = Executors.newSingleThreadScheduledExecutor()
        val waterSockets = ConcurrentHashMap<WebSocket, Pair<Int, Int>>()
        val soundSockets = ConcurrentHashMap.newKeySet<WebSocket>()
        val waterConnections = AtomicInteger()
        val audioPackets = AtomicInteger()
        val audioConnections = AtomicInteger()
        fun audioListener() = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { soundSockets.add(webSocket); audioConnections.incrementAndGet() }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { soundSockets.remove(webSocket) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { soundSockets.remove(webSocket) }
        }
        fun waterListener() = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { waterConnections.incrementAndGet() }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val zoom = Regex("zoom=(\\d+)").find(text)!!.groupValues[1].toInt()
                val start = Regex("start=(\\d+)").find(text)!!.groupValues[1].toInt()
                waterSockets[webSocket] = zoom to start
                webSocket.send(byteArrayOf(255.toByte(), 1, zoom.toByte(), start.toByte(), (start ushr 8).toByte(),
                    (start ushr 16).toByte(), (start ushr 24).toByte(), 0).toByteString())
                webSocket.send(byteArrayOf(255.toByte(), 2, 0, 4).toByteString())
                webSocket.send(ByteArray(128).toByteString())
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { waterSockets.remove(webSocket) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { waterSockets.remove(webSocket) }
        }
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.endsWith("bandinfo.js") -> MockResponse().setBody(
                    "var bandinfo=[{centerfreq:50000,samplerate:100000,vfo:7100,maxzoom:5,maxlinbw:12,name:'Test'}];")
                request.path!!.startsWith("/~~stream") -> MockResponse().withWebSocketUpgrade(audioListener())
                else -> MockResponse().withWebSocketUpgrade(waterListener())
            }
        }
        server.start()
        val serverUrl = server.url("/").toString()
        timer.scheduleAtFixedRate({
            soundSockets.forEach { socket ->
                if (socket.send((byteArrayOf(0x81.toByte(), 0x1f, 0x40) + ByteArray(6) { 0x84.toByte() }).toByteString())) audioPackets.incrementAndGet()
            }
            waterSockets.keys.forEach { it.send(ByteArray(128).toByteString()) }
        }, 100, 100, TimeUnit.MILLISECONDS)
        val runtime = (context.applicationContext as RadiolaApplication).webSdr
        var oldUrl: String? = null; var oldName = ""; var oldSource = ReceiverSource.USB; var oldMode = DemodulationMode.AM
        var oldFrequency = 0L; var oldPage = "Radio"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                main {
                    oldUrl = runtime.receiverUrl; oldName = runtime.receiverName; oldSource = runtime.source
                    oldPage = runtime.page
                    runtime.disconnect()
                    runtime.selectSource(ReceiverSource.WEB_SDR)
                    oldMode = runtime.session.mode; oldFrequency = runtime.session.frequencyHz
                    runtime.select(WebSdrReceiver(serverUrl, "Test receiver", "", 0f, 0f, null, emptyList()))
                    runtime.connect()
                }
                await("Audio did not start") { runtime.session.state == ReceiverConnectionState.Connected && runtime.session.frame != null }
                val owned = runtime.session
                val waterCount = waterConnections.get()
                Thread.sleep(3100)
                assertEquals("Healthy waterfall was restarted by watchdog", waterCount, waterConnections.get())
                repeat(10) { i -> main { owned.setWaterfallViewport(7_000_000L + i * 20000, 1_000_000) }; Thread.sleep(80) }
                Thread.sleep(400)
                assertEquals("Viewport reopened sockets", waterCount, waterConnections.get())
                scenario.recreate()
                await("Session did not survive Activity recreation") { runtime.session === owned && owned.state == ReceiverConnectionState.Connected }
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                Thread.sleep(300)
                main { assertEquals(ReceiverConnectionState.Connected, owned.state) }
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                val packets = audioPackets.get()
                waterSockets.keys.toList().forEach { it.close(1011, "test water failure") }
                await("Waterfall did not reconnect") { waterConnections.get() > waterCount && owned.waterfallWarning == null }
                main { assertEquals(ReceiverConnectionState.Connected, owned.state) }
                assertTrue("Audio stopped during waterfall recovery", audioPackets.get() > packets)
                val audioCount = audioConnections.get()
                soundSockets.toList().forEach { it.close(1011, "test audio failure") }
                await("Audio did not reconnect") { audioConnections.get() > audioCount && owned.state == ReceiverConnectionState.Connected }
                main { owned.tune(oldFrequency); owned.requestMode(oldMode) }
                soundSockets.toList().forEach { it.close(1011, "test cancel retry") }
                await("Audio retry was not scheduled") { owned.state == ReceiverConnectionState.Connecting }
                main { context.startService(Intent(context, WebSdrService::class.java).setAction(WebSdrService.STOP)) }
                await("STOP failed to cancel receiver") { !owned.active && runtime.serviceOwner == null }
                val count = server.requestCount
                Thread.sleep(1500)
                assertEquals("Receiver reconnected after STOP", count, server.requestCount)
            } finally {
                main {
                    runtime.disconnect()
                    runtime.selectServer(oldUrl, oldName)
                    runtime.selectSource(oldSource); runtime.selectPage(oldPage)
                }
                timer.shutdownNow(); server.shutdown()
            }
        }
    }
}
