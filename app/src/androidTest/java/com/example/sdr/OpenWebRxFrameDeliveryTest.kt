package com.example.sdr

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.sdr.sources.openwebrx.OpenWebRxSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpenWebRxFrameDeliveryTest {
    @Test fun networkBurstReachesHistoryInOrderAndStallDoesNotDisconnectAudioTransport() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val server = MockWebServer()
        val sockets = LinkedBlockingQueue<WebSocket>()
        server.enqueue(MockResponse().setBody("OpenWebRX"))
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.startsWith("SERVER DE CLIENT")) {
                    webSocket.send("CLIENT DE SERVER server=openwebrx")
                    webSocket.send("""{"type":"config","value":{"sdr_id":"test","profile_id":"hf","audio_compression":"none","fft_compression":"none","fft_size":1024,"center_freq":7100000,"samp_rate":100000,"start_freq":7100000,"start_mod":"am"}}""")
                    sockets.offer(webSocket)
                }
            }
        }))
        server.start()
        val serverUrl = server.url("/").toString()
        lateinit var session: OpenWebRxSession
        fun main(action: () -> Unit) = instrument.runOnMainSync(action)
        fun await(predicate: () -> Boolean) {
            val end = android.os.SystemClock.elapsedRealtime() + 15_000
            while (android.os.SystemClock.elapsedRealtime() < end) {
                var done = false
                main { done = predicate() }
                if (done) return
                Thread.sleep(20)
            }
            fail("Timed out waiting for FFT delivery")
        }
        fun fft(db: Float) = ByteBuffer.allocate(1 + 1024 * 4).order(ByteOrder.LITTLE_ENDIAN)
            .put(1).apply { repeat(1024) { putFloat(db) } }.array().toByteString()
        try {
            main { session = OpenWebRxSession(instrument.targetContext); session.connect(serverUrl) }
            val socket = sockets.poll(5, TimeUnit.SECONDS)!!
            await { session.bands.isNotEmpty() }
            main {
                // Delay the UI briefly so all three frames arrive in one network burst.
                socket.send(fft(-90f)); socket.send(fft(-80f)); socket.send(fft(-70f))
                Thread.sleep(100)
            }
            await { session.waterfallHistory.size == 3 }
            main {
                val history = session.waterfallHistory
                assertEquals(-70f, history.dbAt(history.rowAtAge(0), 0), .01f)
                assertEquals(-80f, history.dbAt(history.rowAtAge(1), 0), .01f)
                assertEquals(-90f, history.dbAt(history.rowAtAge(2), 0), .01f)
            }
            // Keep PCM flowing while FFT stops: detecting the spectrum stall must not reconnect.
            val pcm = ByteBuffer.allocate(1 + 1200 * 2).order(ByteOrder.LITTLE_ENDIAN)
                .put(2).array().toByteString()
            repeat(115) { socket.send(pcm); Thread.sleep(100) }
            await { session.waterfallWarning == "Spectrum stream stalled" }
            main { assertTrue(session.active) }
            assertEquals(2, server.requestCount)
            socket.send(fft(-60f))
            await { session.waterfallWarning == null && session.waterfallHistory.size == 4 }
            main { session.disconnect() }
            socket.send(fft(-50f))
            Thread.sleep(100)
            main { assertNull(session.frame); assertEquals(0, session.waterfallHistory.size) }
        } finally {
            main { runCatching { session.disconnect() } }
            server.shutdown()
        }
    }
}
