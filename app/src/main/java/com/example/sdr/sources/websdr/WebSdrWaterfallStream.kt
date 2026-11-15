package com.example.sdr.sources.websdr

import com.example.sdr.receiver.ReceiverBand
import okhttp3.*
import okio.ByteString

/** Persistent stateful decoders. Every packet is decoded, only confirmed matching RF windows join. */
internal class WebSdrWaterfallStream(
    private val client: OkHttpClient,
    private val request: (String) -> Request,
    private val band: ReceiverBand,
    initialWindow: WebSdrWaterfallWindow,
    private val onRow: (FloatArray, Long, Int, Long, WebSdrWaterfallWindow) -> Unit,
    private val onFailure: (String) -> Unit,
) {
    private val lock = Any()
    private var target = initialWindow
    private var confirmedWindow: WebSdrWaterfallWindow? = null
    private val sockets = mutableListOf<WebSocket>()
    private val rows = arrayOfNulls<IntArray>(initialWindow.width / 1024)
    private val fresh = BooleanArray(rows.size)
    private val rowWindows = arrayOfNulls<WebSdrWaterfallWindow>(rows.size)
    private var closed = false
    private val received = IntArray(rows.size)
    private val matching = IntArray(rows.size)
    private val overwritten = IntArray(rows.size)
    private var joined = 0
    private var waitingSince = 0L
    private var maximumWaitNs = 0L
    fun takeDiagnostics(): String = synchronized(lock) {
        val value = "tiles=${received.joinToString("/")} matched=${matching.joinToString("/")} " +
            "overwritten=${overwritten.joinToString("/")} joined=$joined joinMaxMs=${maximumWaitNs / 1_000_000}"
        received.fill(0); matching.fill(0); overwritten.fill(0); joined = 0; maximumWaitNs = 0
        value
    }
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
                                received[tile]++
                                if (closed || !decoder.positionKnown) return
                                fun matches(window: WebSdrWaterfallWindow) = decoder.zoom == window.zoom &&
                                    decoder.start == tileStart(window, tile)
                                val coordinates = when {
                                    matches(target) -> target
                                    confirmedWindow?.let { matches(it) } == true -> confirmedWindow!!
                                    else -> return
                                }
                                matching[tile]++
                                val now = System.nanoTime()
                                if (fresh.none { it }) waitingSince = now
                                if (fresh[tile]) overwritten[tile]++
                                rows[tile] = levels; fresh[tile] = true; rowWindows[tile] = coordinates
                                if (!fresh.all { it } || rowWindows.any { it != coordinates }) return
                                joined++
                                maximumWaitNs = maxOf(maximumWaitNs, now - waitingSince)
                                fresh.fill(false)
                                val window = coordinates
                                if (window == target) confirmedWindow = window
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
        target = window; fresh.fill(false); rowWindows.fill(null); waitingSince = 0
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
