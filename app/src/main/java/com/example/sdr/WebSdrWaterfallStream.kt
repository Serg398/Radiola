package com.example.sdr

import okhttp3.*
import okio.ByteString

/** Persistent stateful decoders. Every packet is decoded, only confirmed matching RF windows join. */
internal class WebSdrWaterfallStream(
    private val client: OkHttpClient,
    private val request: (String) -> Request,
    private val band: WebSdrTunerBand,
    initialWindow: WebSdrWaterfallWindow,
    private val onRow: (FloatArray, Long, Int, Long, WebSdrWaterfallWindow) -> Unit,
    private val onFailure: (String) -> Unit,
) {
    private val lock = Any()
    private var target = initialWindow
    private val sockets = mutableListOf<WebSocket>()
    private val rows = arrayOfNulls<IntArray>(initialWindow.width / 1024)
    private val fresh = BooleanArray(rows.size)
    private var closed = false
    @Volatile var confirmedAtNanos = 0L
        private set

    init {
        repeat(rows.size) { tile ->
            val decoder = WebSdrWaterfallDecoder()
            val initialStart = tileStart(initialWindow, tile)
            sockets += client.newWebSocket(request("/~~waterstream${band.index}?format=9&width=1024&zoom=${initialWindow.zoom}&start=$initialStart"),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) = synchronized(lock) {
                        if (closed) webSocket.cancel() else webSocket.send(command(target, tile))
                        Unit
                    }
                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        try {
                            // Retain predictor state even while an old window is being discarded.
                            val levels = decoder.decode(bytes.toByteArray()) ?: return
                            synchronized(lock) {
                                if (closed || !decoder.positionKnown || decoder.zoom != target.zoom ||
                                    decoder.start != tileStart(target, tile)) return
                                rows[tile] = levels; fresh[tile] = true
                                if (!fresh.all { it }) return
                                fresh.fill(false)
                                val window = target
                                val span = (band.spanHz.toDouble() * window.width / 1024 / (1L shl window.zoom)).toInt().coerceAtLeast(1)
                                val center = (band.lowHz + window.start.toDouble() / (1024L shl band.maxZoom) * band.spanHz + span / 2).toLong()
                                val bins = FloatArray(window.width) { x -> -94f + rows[x / 1024]!![x % 1024] * (69f / 255) }
                                confirmedAtNanos = System.nanoTime()
                                onRow(bins, center, span, confirmedAtNanos, window)
                            }
                        } catch (error: Exception) { failed("Invalid waterfall: ${error.message}") }
                    }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = failed("Waterfall connection lost")
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = failed("Waterfall connection closed")
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
                })
        }
    }
    fun update(window: WebSdrWaterfallWindow): Boolean = synchronized(lock) {
        if (closed || window.width != target.width) return false
        if (window == target) return true
        target = window; fresh.fill(false)
        sockets.forEachIndexed { tile, socket -> socket.send(command(window, tile)) }
        true
    }
    fun close() = synchronized(lock) {
        closed = true; sockets.forEach { it.cancel() }; sockets.clear(); rows.fill(null)
    }
    private fun failed(message: String) {
        synchronized(lock) { if (closed) return; closed = true; sockets.forEach { it.cancel() }; sockets.clear() }
        onFailure(message)
    }
    private fun tileStart(window: WebSdrWaterfallWindow, tile: Int) =
        window.start + (tile * (1024L shl (band.maxZoom - window.zoom))).toInt()
    private fun command(window: WebSdrWaterfallWindow, tile: Int) =
        "GET /~~waterparam?band=${band.index}&zoom=${window.zoom}&start=${tileStart(window, tile)}&slow=2&scale=1"
}
