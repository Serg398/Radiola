package com.example.sdr.sources.openwebrx

import android.os.SystemClock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject

/** Wire protocol owner. No UI state, profile policy, retries, or AudioTrack ownership. */
internal class OpenWebRxStream(client: OkHttpClient, request: Request, private val listener: Listener) {
    enum class ErrorKind { TRANSPORT, PROTOCOL, SPECTRUM }
    interface Listener {
        fun onPacket()
        fun onMessage(message: JSONObject)
        fun onSpectrum(bins: FloatArray, centerHz: Long, spanHz: Int, timestampNanos: Long)
        fun onAudio(rate: Int, samples: ShortArray, signalDbm: Float?)
        fun onError(kind: ErrorKind, message: String)
    }
    @Volatile private var closed = false
    private val decoder = OpenWebRxStreamDecoder()
    private val socket = client.newWebSocket(request, object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (closed) webSocket.cancel() else webSocket.send("SERVER DE CLIENT client=Radiola type=receiver")
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (closed) return
            listener.onPacket()
            try {
                if (text.startsWith("CLIENT DE SERVER")) {
                    webSocket.send(JSONObject().put("type", "connectionproperties").put("params",
                        JSONObject().put("output_rate", 12000).put("hd_output_rate", 48000)).toString())
                    return
                }
                val message = JSONObject(text)
                when (message.optString("type")) {
                    "config" -> decoder.configure(message.getJSONObject("value"))
                    "smeter" -> { decoder.signal(message.optDouble("value", Double.NaN)); return }
                }
                listener.onMessage(message)
            } catch (error: Exception) {
                if (!closed) listener.onError(ErrorKind.PROTOCOL, "Invalid OpenWebRX message: ${error.message}")
            }
        }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (closed || bytes.size < 2) return
            listener.onPacket()
            val kind = bytes[0].toInt()
            try {
                val payload = bytes.substring(1).toByteArray()
                when (kind) {
                    1 -> decoder.spectrum(payload)?.let {
                        listener.onSpectrum(it, decoder.centerHz, decoder.spanHz, SystemClock.elapsedRealtimeNanos())
                    }
                    2, 4 -> {
                        val rate = if (kind == 4) 48000 else 12000
                        val samples = decoder.audio(payload, kind == 4)
                        if (samples.isNotEmpty()) listener.onAudio(rate, samples, decoder.signalDbm)
                    }
                }
            } catch (error: Exception) {
                if (!closed) listener.onError(if (kind == 1) ErrorKind.SPECTRUM else ErrorKind.PROTOCOL,
                    if (kind == 1) "Cannot decode spectrum: ${error.message}" else "Invalid OpenWebRX audio: ${error.message}")
            }
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!closed) listener.onError(ErrorKind.TRANSPORT, "OpenWebRX connection lost")
        }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!closed) listener.onError(ErrorKind.TRANSPORT, reason.ifBlank { "OpenWebRX connection closed" })
        }
    })
    fun send(text: String): Boolean = !closed && socket.send(text)
    fun cancel() { closed = true; socket.cancel() }
}
