package com.example.sdr

import okhttp3.*
import okhttp3.mockwebserver.*
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class WebSdrWaterfallStreamTest {
    @Test fun viewportChangesReuseSocketsAndPublishOnlyConfirmedCoordinates() {
        val server = MockWebServer()
        val client = OkHttpClient()
        val rows = LinkedBlockingQueue<Pair<Long, Int>>()
        val failures = LinkedBlockingQueue<String>()
        val band = WebSdrTunerBand(0, "40m", 7100000, 384000, 7100000, 5)
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val zoom = Regex("zoom=(\\d+)").find(text)!!.groupValues[1].toInt()
                val start = Regex("start=(\\d+)").find(text)!!.groupValues[1].toInt()
                val metadata = byteArrayOf(255.toByte(), 1, zoom.toByte(), start.toByte(), (start ushr 8).toByte(),
                    (start ushr 16).toByte(), (start ushr 24).toByte(), 0)
                webSocket.send(metadata.toByteString())
                webSocket.send(byteArrayOf(255.toByte(), 2, 0, 4).toByteString())
                webSocket.send(ByteArray(128).toByteString())
            }
        }
        repeat(2) { server.enqueue(MockResponse().withWebSocketUpgrade(listener)) }
        server.start()
        val stream = WebSdrWaterfallStream(client, { path -> Request.Builder().url(server.url(path)).build() },
            band, WebSdrWaterfallWindow(2048, 2, 0),
            onRow = { bins, center, span, _, _ ->
                assertEquals(2048, bins.size); rows.offer(center to span)
            }, onFailure = { failures.offer(it) })
        try {
            assertNotNull(rows.poll(5, TimeUnit.SECONDS))
            assertTrue(stream.update(WebSdrWaterfallWindow(2048, 3, 2048)))
            val moved = rows.poll(5, TimeUnit.SECONDS)!!
            assertEquals(6980000L, moved.first)
            assertEquals(96000, moved.second)
            assertEquals(2, server.requestCount)
            assertTrue(failures.isEmpty())
        } finally { stream.close(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll(); server.shutdown() }
    }
}
