package com.example.sdr

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.ByteString
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

internal enum class WebSdrConnectionState { Idle, Connecting, Connected, Error }

/** Direct WebSocket client. Socket callbacks decode on OkHttp workers; snapshots publish on main. */
internal class WebSdrSession(context: Context) {
    var state by mutableStateOf(WebSdrConnectionState.Idle)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var frequencyHz by mutableLongStateOf(7_100_000L)
        private set
    var mode by mutableStateOf(DemodulationMode.AM)
        private set
    var bandwidthHz by mutableIntStateOf(9_000)
        private set
    var bands by mutableStateOf<List<WebSdrTunerBand>>(emptyList())
        private set
    var currentBand by mutableIntStateOf(0)
        private set
    var audioSampleRate by mutableIntStateOf(8000)
        private set
    var frame by mutableStateOf<SpectrumFrame?>(null)
        private set
    var waterfallHistory by mutableStateOf(WaterfallHistory(1024))
        private set
    val active get() = state == WebSdrConnectionState.Connecting || state == WebSdrConnectionState.Connected
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS).build()
    @Volatile private var generation = 0L
    @Volatile private var waterGeneration = 0L
    @Volatile private var lastAudioAt = 0L
    @Volatile private var lastWaterAt = 0L
    private var metadata: Call? = null
    private var sound: WebSocket? = null
    private val waters = mutableListOf<WebSocket>()
    private var output: WebSdrAudioOutput? = null
    private var base: HttpUrl? = null
    private var focusHeld = false
    private var audioPlaying = false
    private var waterReady = false
    private var startedAt = 0L
    private var zoom = 0
    private var waterWidth = 1024
    private var viewportSpanHz: Int? = null
    private var viewportCenterHz: Long? = null
    private var start = 0
    private var frameGeneration = 0L
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setOnAudioFocusChangeListener({ change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) disconnect()
        }, handler).build()

    fun connect(url: String) {
        disconnect()
        val address = url.toHttpUrlOrNull()
        if (address == null || address.username.isNotEmpty() || address.password.isNotEmpty()) { fail("Invalid WebSDR address"); return }
        if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { fail("Audio focus unavailable"); return }
        focusHeld = true
        base = address
        state = WebSdrConnectionState.Connecting
        startedAt = SystemClock.elapsedRealtime()
        val token = generation
        val request = Request.Builder().url(address.resolve("tmp/bandinfo.js")!!).build()
        metadata = client.newCall(request).also { call ->
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = publish(token) { fail("Cannot read WebSDR bands") }
                override fun onResponse(call: Call, response: Response) {
                    val descriptors = runCatching {
                        response.use {
                            check(it.isSuccessful) { "HTTP ${it.code}" }
                            val text = it.body!!.byteStream().bufferedReader().use { reader ->
                                val text = StringBuilder()
                                val buffer = CharArray(8192)
                                while (true) {
                                    val count = reader.read(buffer)
                                    if (count < 0) break
                                    require(text.length + count <= 2_000_000)
                                    text.append(buffer, 0, count)
                                }
                                text.toString()
                            }
                            WebSdrTunerBand.parse(text)
                        }
                    }
                    publish(token) {
                        descriptors.fold(onSuccess = {
                            bands = it
                            currentBand = bands.indexOfFirst { b -> b.contains(frequencyHz) }.coerceAtLeast(0)
                            val band = bands[currentBand]
                            if (!band.contains(frequencyHz)) frequencyHz = band.defaultHz.coerceIn(band.lowHz, band.highHz)
                            beginStreams(token)
                        }, onFailure = { fail("Unsupported WebSDR band description") })
                    }
                }
            })
        }
        handler.postDelayed(health, 1000)
    }

    private fun publish(token: Long, action: () -> Unit) { handler.post { if (generation == token && active) action() } }

    private fun socketRequest(path: String): Request {
        val host = base!!
        val address = host.newBuilder().encodedPath(path.substringBefore('?')).query(path.substringAfter('?', "").ifBlank { null }).build()
        return Request.Builder().url(address).header("Origin", "${host.scheme}://${host.host}${if (host.port == 80 || host.port == 443) "" else ":${host.port}"}").build()
    }

    private fun beginStreams(token: Long) {
        val decoder = WebSdrAudioDecoder()
        output = WebSdrAudioOutput(
            onPlaying = { publish(token) { audioPlaying = true; acceptConnection() } },
            onError = { message -> publish(token) { fail(message) } },
        )
        val ownedOutput = output!!
        sound = client.newWebSocket(socketRequest("/~~stream"), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = publish(token) { sendTuning() }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (generation != token) return
                try {
                    decoder.decode(bytes.toByteArray()) { rate, samples ->
                        lastAudioAt = SystemClock.elapsedRealtime()
                        ownedOutput.offer(rate, samples)
                    }
                    if (decoder.sampleRate != audioSampleRate) publish(token) { audioSampleRate = decoder.sampleRate }
                } catch (e: Exception) { publish(token) { fail("Invalid WebSDR audio: ${e.message}") } }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = publish(token) { fail("WebSDR audio connection failed") }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = publish(token) { fail("WebSDR audio connection closed") }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
        })
        zoom = 0 // Capture the full band; the shared renderer handles viewport zoom.
        openWaterfall(token)
    }

    private fun acceptConnection() {
        if (audioPlaying && waterReady && state != WebSdrConnectionState.Connected) {
            state = WebSdrConnectionState.Connected
            Log.i("WebSdrConnection", "Native WebSockets connected: audio decoded/playing, waterfall received")
        }
    }

    private fun openWaterfall(token: Long = generation) {
        val waterToken = ++waterGeneration
        waters.forEach { it.cancel() }; waters.clear()
        waterReady = false
        val band = bands[currentBand]
        val window = WebSdrWaterfallWindow.forViewport(band, viewportCenterHz ?: frequencyHz, viewportSpanHz ?: band.spanHz)
        zoom = window.zoom
        start = window.start
        waterWidth = window.width
        val requestedZoom = zoom
        val requestedStart = start
        val tileCount = window.width / 1024
        val tileRows = arrayOfNulls<FloatArray>(tileCount)
        val fresh = BooleanArray(tileCount)
        val span = (band.spanHz.toDouble() * window.width / 1024 / (1L shl requestedZoom)).toInt().coerceAtLeast(1)
        val center = (band.lowHz + requestedStart.toDouble() / (1024L shl band.maxZoom) * band.spanHz + span / 2).toLong()
        repeat(tileCount) { tile ->
            // Legacy servers cap each stream at 1024 bins. Adjacent native windows
            // retain real RF detail instead of inventing samples by upscaling.
            val tileStart = requestedStart + (tile * (1024L shl (band.maxZoom - requestedZoom))).toInt()
            val decoder = WebSdrWaterfallDecoder()
            waters += client.newWebSocket(socketRequest("/~~waterstream${band.index}?format=9&width=1024&zoom=$requestedZoom&start=$tileStart"), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (generation != token || waterGeneration != waterToken) { webSocket.cancel(); return }
                    webSocket.send("GET /~~waterparam?band=${band.index}&zoom=$requestedZoom&start=$tileStart&slow=2&scale=1")
                }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (generation != token || waterGeneration != waterToken) return
                    try {
                        val levels = decoder.decode(bytes.toByteArray()) ?: return
                        if (!decoder.positionKnown || decoder.zoom != requestedZoom || decoder.start != tileStart) return
                        val bins = FloatArray(levels.size) { -94f + levels[it] * (69f / 255) }
                        publish(token) {
                            if (waterGeneration != waterToken) return@publish
                            tileRows[tile] = bins
                            fresh[tile] = true
                            if (!fresh.all { it }) return@publish
                            fresh.fill(false)
                            lastWaterAt = SystemClock.elapsedRealtime()
                            val combined = FloatArray(window.width) { x -> tileRows[x / 1024]!![x % 1024] }
                            val captured = SpectrumFrame(combined, center, ++frameGeneration, System.nanoTime(), token, span)
                            // Each historical row retains its own RF coordinates and span.
                            // Keep it across zoom changes while finer rows arrive above it.
                            if (waterfallHistory.binCount != window.width) {
                                waterfallHistory = WaterfallHistory(window.width)
                            }
                            frame = captured
                            waterfallHistory.append(captured)
                            waterReady = true
                            acceptConnection()
                        }
                    } catch (e: Exception) { publish(token) { if (waterGeneration == waterToken) fail("Invalid WebSDR waterfall: ${e.message}") } }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = publish(token) {
                    if (waterGeneration == waterToken) fail("WebSDR waterfall connection failed")
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = publish(token) {
                    if (waterGeneration == waterToken) fail("WebSDR waterfall connection closed")
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
            })
        }
    }

    private fun sendTuning() {
        val width = bandwidthHz / 1000.0
        val low = when (mode) {
            DemodulationMode.USB -> .15
            DemodulationMode.LSB -> -.15 - width
            DemodulationMode.CW -> -.75 - width / 2
            else -> -width / 2
        }
        val frequency = frequencyHz / 1000.0 + if (mode == DemodulationMode.CW) .75 else 0.0
        val nativeMode = when (mode) { DemodulationMode.AM -> 1; DemodulationMode.NFM -> 4; else -> 0 }
        sound?.send(String.format(Locale.US, "GET /~~param?f=%.3f&band=%d&lo=%.5f&hi=%.5f&mode=%d&name=Radiola", frequency, bands[currentBand].index, low, low + width, nativeMode))
    }

    fun tune(hz: Long) {
        if (!active || bands.isEmpty()) return
        val index = bands.indexOfFirst { it.contains(hz) }
        if (index < 0) { error = "Frequency outside this server's bands"; return }
        error = null
        frequencyHz = hz
        val bandChanged = currentBand != index
        currentBand = index
        output?.flush()
        sendTuning()
        if (bandChanged) { zoom = 0; viewportCenterHz = null; viewportSpanHz = null }
        if (bandChanged) { frame = null; waterfallHistory = WaterfallHistory(1024) }
        if (bandChanged) openWaterfall()
    }
    fun selectBand(index: Int) {
        if (index !in bands.indices) return
        tune(bands[index].defaultHz.coerceIn(bands[index].lowHz, bands[index].highHz))
    }
    fun requestMode(value: DemodulationMode) {
        if (!active || value == DemodulationMode.WFM) return
        mode = value
        bandwidthHz = when (value) { DemodulationMode.AM -> 9000; DemodulationMode.NFM -> 10000; DemodulationMode.CW -> 500; else -> 2400 }
        output?.flush(); sendTuning()
    }
    fun setBandwidth(hz: Int?) {
        if (!active) return
        if (hz == null) { requestMode(mode); return }
        bandwidthHz = hz.coerceIn(100, 12_000)
        sendTuning()
    }
    /** Request actual server resolution, independent of the USB rendering path. */
    fun setWaterfallViewport(centerHz: Long, spanHz: Int) {
        if (!active || bands.isEmpty()) return
        val band = bands[currentBand]
        val window = WebSdrWaterfallWindow.forViewport(band, centerHz, spanHz)
        viewportCenterHz = centerHz
        viewportSpanHz = spanHz
        if (zoom == window.zoom && start == window.start && waterWidth == window.width) return
        openWaterfall()
    }

    private val health = object : Runnable {
        override fun run() {
            if (!active) return
            val now = SystemClock.elapsedRealtime()
            if (state == WebSdrConnectionState.Connecting && now - startedAt > 25_000) {
                fail("WebSDR did not start both streams. Try another server."); return
            }
            if (state == WebSdrConnectionState.Connected && (now - lastAudioAt > 15_000 || now - lastWaterAt > 15_000)) {
                fail("WebSDR stream stopped"); return
            }
            handler.postDelayed(this, 1000)
        }
    }
    fun disconnect() {
        generation++; waterGeneration++
        handler.removeCallbacks(health)
        metadata?.cancel(); metadata = null
        sound?.cancel(); sound = null
        waters.forEach { it.cancel() }; waters.clear()
        output?.close(); output = null
        audioPlaying = false; waterReady = false; frameGeneration = 0
        frame = null; waterfallHistory = WaterfallHistory(1024)
        viewportCenterHz = null; viewportSpanHz = null; zoom = 0; start = 0; waterWidth = 1024
        lastAudioAt = 0; lastWaterAt = 0
        if (focusHeld) { audioManager.abandonAudioFocusRequest(focus); focusHeld = false }
        if (active) Log.i("WebSdrConnection", "Native WebSockets disconnected")
        state = WebSdrConnectionState.Idle; error = null
    }
    private fun fail(message: String) { disconnect(); error = message; state = WebSdrConnectionState.Error; Log.w("WebSdrConnection", message) }
}
