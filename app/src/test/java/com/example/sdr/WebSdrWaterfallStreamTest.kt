package com.example.sdr

import com.example.sdr.receiver.ReceiverBand
import com.example.sdr.sources.websdr.WebSdrWaterfallStream
import com.example.sdr.sources.websdr.WebSdrWaterfallWindow
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import okhttp3.*
import okhttp3.mockwebserver.*
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test

class WebSdrWaterfallStreamTest {
    @Test fun confirmedOldWindowContinuesUntilBothNewHalvesArrive() {
        val server = MockWebServer()
        val client = OkHttpClient()
        val sockets = LinkedBlockingQueue<Pair<Int, WebSocket>>()
        val rows = LinkedBlockingQueue<WebSdrWaterfallWindow>()
        val commands = java.util.concurrent.CountDownLatch(2)
        fun sendRow(socket: WebSocket, zoom: Int? = null, start: Int = 0) {
            if (zoom != null) {
                socket.send(byteArrayOf(255.toByte(), 1, zoom.toByte(), start.toByte(), (start ushr 8).toByte(),
                    (start ushr 16).toByte(), (start ushr 24).toByte(), 0).toByteString())
                socket.send(byteArrayOf(255.toByte(), 2, 0, 4).toByteString())
            }
            socket.send(ByteArray(128).toByteString())
        }
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val zoom = Regex("zoom=(\\d+)").find(text)!!.groupValues[1].toInt()
                val start = Regex("start=(\\d+)").find(text)!!.groupValues[1].toInt()
                if (zoom == 2) { sockets.offer(start to webSocket); sendRow(webSocket, zoom, start) }
                else commands.countDown()
            }
        }
        repeat(2) { server.enqueue(MockResponse().withWebSocketUpgrade(listener)) }
        server.start()
        val initial = WebSdrWaterfallWindow(2048, 2, 0)
        val moved = WebSdrWaterfallWindow(2048, 3, 2048)
        val stream = WebSdrWaterfallStream(client, { path -> Request.Builder().url(server.url(path)).build() },
            ReceiverBand(0, "40m", 7100000, 384000, 7100000, 5), initial,
            onRow = { _, _, _, _, coordinates -> rows.offer(coordinates) }, onFailure = { fail(it) })
        try {
            assertEquals(initial, rows.poll(5, TimeUnit.SECONDS))
            val ordered = List(2) { sockets.poll(5, TimeUnit.SECONDS)!! }.sortedBy { it.first }.map { it.second }
            assertTrue(stream.update(moved))
            assertTrue(commands.await(5, TimeUnit.SECONDS))
            ordered.forEach { sendRow(it) }
            assertEquals(initial, rows.poll(5, TimeUnit.SECONDS))
            sendRow(ordered[0], 3, 2048)
            assertNull("Different RF windows must never join", rows.poll(100, TimeUnit.MILLISECONDS))
            sendRow(ordered[1], 3, 6144)
            assertEquals(moved, rows.poll(5, TimeUnit.SECONDS))
            assertEquals(2, server.requestCount)
        } finally {
            stream.close(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll(); server.shutdown()
        }
    }

    @Test fun viewportChangesReuseSocketsAndPublishOnlyConfirmedCoordinates() {
        val server = MockWebServer()
        val client = OkHttpClient()
        val rows = LinkedBlockingQueue<Pair<Long, Int>>()
        val failures = LinkedBlockingQueue<String>()
        val band = ReceiverBand(0, "40m", 7100000, 384000, 7100000, 5)
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
