package com.example.sdr

import com.example.sdr.sources.websdr.WebSdrAudioStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test

class WebSdrAudioStreamTest {
    @Test fun decodesSocketAudioAndIntentionalCancellationDoesNotRequestRecovery() {
        val server = MockWebServer()
        val client = OkHttpClient()
        val received = CountDownLatch(1)
        val failures = AtomicInteger()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send((byteArrayOf(0x81.toByte(), 0x1f, 0x40) + ByteArray(6) { 0x84.toByte() }).toByteString())
            }
        }))
        server.start()
        val stream = WebSdrAudioStream(client, Request.Builder().url(server.url("/")).build(),
            onOpen = {}, onAudio = { rate, samples, _ ->
                if (rate == 8000 && samples.isNotEmpty()) received.countDown()
            }, onRate = {}, onFailure = { failures.incrementAndGet() })
        try {
            assertTrue(received.await(3, TimeUnit.SECONDS))
            stream.cancel()
            assertFalse(stream.send("GET /~~param?f=7100"))
            assertEquals(0, failures.get())
        } finally {
            stream.cancel()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            server.shutdown()
        }
    }
}
