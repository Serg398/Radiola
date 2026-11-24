package com.example.sdr.sources.websdr

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/** Stateful audio wire decoder, independent of playback, UI and retry decisions. */
internal class WebSdrAudioStream(client: OkHttpClient, request: Request,
    private val onOpen: () -> Unit,
    private val onAudio: (Int, ShortArray, Float?) -> Unit,
    private val onRate: (Int) -> Unit,
    private val onFailure: (String) -> Unit,
) {
    @Volatile private var closed = false
    private val decoder = WebSdrAudioDecoder()
    private var meterAt = 0L
    private val socket = client.newWebSocket(request, object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (closed) webSocket.cancel() else onOpen()
        }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (closed) return
            try {
                decoder.decode(bytes.toByteArray()) { rate, samples ->
                    if (!closed) onAudio(rate, samples, decoder.signalDbm)
                }
                val now = System.nanoTime()
                if (!closed && now - meterAt >= 100_000_000L) {
                    meterAt = now
                    onRate(decoder.sampleRate)
                }
            } catch (error: Exception) { if (!closed) onFailure("Invalid server audio: ${error.message}") }
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!closed) onFailure("Audio connection lost")
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!closed) onFailure("Audio connection closed")
        }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
    })
    fun send(command: String): Boolean = !closed && socket.send(command)
    fun cancel() { closed = true; socket.cancel() }
}
