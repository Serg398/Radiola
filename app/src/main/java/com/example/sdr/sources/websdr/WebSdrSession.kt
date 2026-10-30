package com.example.sdr.sources.websdr

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.*
import com.example.sdr.DemodulationMode
import com.example.sdr.SpectrumFrame
import com.example.sdr.audio.WebSdrAudioOutput
import com.example.sdr.decoders.cw.CwDecodeWorker
import com.example.sdr.decoders.ftx.FtxDecodeWorker
import com.example.sdr.decoders.ftx.FtxMode
import com.example.sdr.receiver.LatestCommand
import com.example.sdr.receiver.OnlineReceiverSession
import com.example.sdr.receiver.ReceiverAudioMetrics
import com.example.sdr.receiver.ReceiverBand
import com.example.sdr.receiver.ReceiverConnectionState
import com.example.sdr.receiver.ReceiverSquelch
import com.example.sdr.receiver.WaterfallHistory
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.ByteString

/** Application-owned session. All controls run on main; stateful network decoders stay on socket workers. */
internal class WebSdrSession(context: Context) : OnlineReceiverSession {
    override val bandwidthOptions get() = WebSdrBandwidth.options(mode, bandwidthLimit)
    private val preferences = context.getSharedPreferences("websdr", Context.MODE_PRIVATE)
    override fun configureFtx(mode: FtxMode?, frequency: Long) = ftxDecoder.configure(mode, frequency)
    override fun ftxInformation() = ftxDecoder.snapshot()
    override fun clearFtx() = ftxDecoder.clear()
    override fun cwInformation() = cwDecoder.snapshot()
    override val cwBackend get() = cwDecoder.backend
    override fun selectCwBackend(value: com.example.sdr.decoders.cw.CwBackend) = cwDecoder.selectBackend(value)
    override val cwDecodingEnabled get() = cwDecoder.decodingEnabled
    override fun clearCw() = cwDecoder.clear()
    override fun enableCw(value: Boolean) = cwDecoder.requestDecoding(value)
    private val cwDecoder = CwDecodeWorker(context)
    private val ftxDecoder = FtxDecodeWorker()
    override var state by mutableStateOf(ReceiverConnectionState.Idle)
        private set
    override var error by mutableStateOf<String?>(null)
        private set
    override var waterfallWarning by mutableStateOf<String?>(null)
        private set
    override var frequencyHz by mutableLongStateOf(preferences.getLong("frequency", 7_100_000L))
        private set
    override var mode by mutableStateOf(runCatching { DemodulationMode.valueOf(preferences.getString("mode", "AM")!!) }
        .getOrDefault(DemodulationMode.AM).let { if (it == DemodulationMode.WFM) DemodulationMode.AM else it })
        private set
    override var bandwidthHz by mutableIntStateOf(preferences.getInt("bandwidth_${mode.name}", WebSdrBandwidth.default(mode)))
        private set
    override var bandwidthDefault by mutableStateOf(!preferences.contains("bandwidth_${mode.name}"))
        private set
    override var bands by mutableStateOf<List<ReceiverBand>>(emptyList())
        private set
    override var currentBand by mutableIntStateOf(0)
        private set
    override var audioSampleRate by mutableIntStateOf(8000)
        private set
    @Volatile override var squelchSetting = ReceiverSquelch()
    override var squelchOpen by mutableStateOf(true)
        private set
    override var signalDbm by mutableStateOf<Float?>(null)
        private set
    override var frame by mutableStateOf<SpectrumFrame?>(null)
        private set
    override var waterfallHistory by mutableStateOf(WaterfallHistory(1024))
        private set
    override var visualFps by mutableIntStateOf(0)
        private set
    override val active get() = state == ReceiverConnectionState.Connecting || state == ReceiverConnectionState.Connected
    override val audioMetrics get() = output?.metrics ?: ReceiverAudioMetrics()
    private val handler = Handler(Looper.getMainLooper())
    private val clearFrequencyWarning = Runnable {
        if (error == "Frequency outside this server's bands") error = null
    }
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS).build()
    @Volatile private var generation = 0L
    @Volatile private var waterGeneration = 0L
    @Volatile private var audioGeneration = 0L
    @Volatile private var lastAudioAt = 0L
    @Volatile private var lastWaterAt = 0L
    private var metadata: Call? = null
    private var sound: WebSocket? = null
    private var water: WebSdrWaterfallStream? = null
    @Volatile private var window: WebSdrWaterfallWindow? = null
    @Volatile private var windowConfirmed = false
    private var output: WebSdrAudioOutput? = null
    private var base: HttpUrl? = null
    private var sourceUrl = ""
    private var focusHeld = false
    private var audioPlaying = false
    private var audioOpenedAt = 0L
    private var waterOpenedAt = 0L
    private var windowRequestedAt = 0L
    private var viewportSpanHz: Int? = null
    private var viewportCenterHz: Long? = null
    private var frameGeneration = 0L
    private var framesInWindow = 0
    private var fpsAt = 0L
    private var reconnectAttempt = 0
    private var audioAttempt = 0
    private var waterAttempt = 0
    private var lastMetricsAt = 0L
    private data class PendingFrame(val session: Long, val water: Long, val bins: FloatArray,
        val center: Long, val span: Int, val at: Long)
    private val pendingFrame = AtomicReference<PendingFrame?>()
    private val frameScheduled = AtomicBoolean(false)
    private val render = object : Runnable {
        override fun run() {
            frameScheduled.set(false)
            val row = pendingFrame.getAndSet(null) ?: return
            if (!active || row.session != generation || row.water != waterGeneration) return
            val captured = SpectrumFrame(row.bins, row.center, ++frameGeneration, row.at, row.session, row.span)
            if (waterfallHistory.binCount != row.bins.size) waterfallHistory = WaterfallHistory(row.bins.size)
            waterfallHistory.append(captured); frame = captured
            waterfallWarning = null; waterAttempt = 0; framesInWindow++
        }
    }
    // Coalesce calls within a UI turn without imposing a tuning delay.
    private val tuning = LatestCommand<String>(0, SystemClock::elapsedRealtime,
        { task, delay -> handler.postDelayed(task, delay); Unit }, handler::removeCallbacks) { command ->
        if (active) sound?.send(command)
    }
    private val viewport = LatestCommand<WebSdrWaterfallWindow>(100, SystemClock::elapsedRealtime,
        { task, delay -> handler.postDelayed(task, delay); Unit }, handler::removeCallbacks) { target ->
        if (active && bands.isNotEmpty()) updateWaterfall(target)
    }
    private val persist = Runnable {
        preferences.edit().putLong("frequency", frequencyHz).putString("mode", mode.name).also {
            if (sourceUrl.isNotBlank()) it.putInt("band_$sourceUrl", currentBand)
        }.apply()
    }
    private val reconnect = Runnable { if (active) readMetadata() }
    private val retryAudio = Runnable { if (active && bands.isNotEmpty()) openAudio() }
    private val retryWater = Runnable { if (active && bands.isNotEmpty()) openWaterfall() }
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setOnAudioFocusChangeListener({ change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) disconnect()
        }, handler).build()

    override fun connect(url: String) {
        disconnect()
        val address = url.toHttpUrlOrNull()
        if (address == null || address.username.isNotEmpty() || address.password.isNotEmpty()) {
            state = ReceiverConnectionState.Error; error = "Invalid WebSDR address"; return
        }
        if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            state = ReceiverConnectionState.Error; error = "Audio focus unavailable"; return
        }
        focusHeld = true; sourceUrl = url; base = address
        bands = emptyList(); state = ReceiverConnectionState.Connecting
        readMetadata(); handler.postDelayed(health, 1000)
    }
    private fun readMetadata() {
        val token = generation
        val address = sourceUrl.toHttpUrlOrNull() ?: return
        val request = Request.Builder().url(address.resolve("tmp/bandinfo.js")!!).build()
        metadata = client.newCall(request).also { call ->
            call.timeout().timeout(20, TimeUnit.SECONDS)
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = publish(token) { reconnectSession("Cannot read server bands") }
                override fun onResponse(call: Call, response: Response) {
                    val descriptors = runCatching {
                        response.use {
                            check(it.isSuccessful) { "HTTP ${it.code}" }
                            val text = it.body!!.byteStream().bufferedReader().use { reader ->
                                val text = StringBuilder(); val buffer = CharArray(8192)
                                while (true) {
                                    val count = reader.read(buffer)
                                    if (count < 0) break
                                    require(text.length + count <= 2_000_000)
                                    text.append(buffer, 0, count)
                                }
                                text.toString()
                            }
                            Pair(WebSdrBandParser.parse(text), it.request.url.resolve("../")!!)
                        }
                    }
                    publish(token) {
                        metadata = null
                        descriptors.fold(onSuccess = { (descriptions, resolvedBase) ->
                            // Audio and waterfall must use the final redirect destination too.
                            base = resolvedBase; bands = descriptions
                            val savedBand = preferences.getInt("band_$sourceUrl", -1)
                            currentBand = if (bands.getOrNull(savedBand)?.contains(frequencyHz) == true) savedBand
                                else bands.indexOfFirst { it.contains(frequencyHz) }.coerceAtLeast(0)
                            val band = bands[currentBand]
                            if (!band.contains(frequencyHz)) frequencyHz = band.defaultHz.coerceIn(band.lowHz, band.highHz)
                            bandwidthHz = bandwidthHz.coerceIn(100, bandwidthLimit)
                            saveSoon(); openAudio(); openWaterfall()
                        }, onFailure = { reconnectSession("Unsupported server band description") })
                    }
                }
            })
        }
    }
    private fun publish(token: Long, action: () -> Unit) { handler.post { if (generation == token && active) action() } }
    private fun socketRequest(path: String): Request {
        val host = base!!
        return Request.Builder().url(host.resolve(path.removePrefix("/"))!!)
            .header("Origin", "${host.scheme}://${host.host}${if (host.port == 80 || host.port == 443) "" else ":${host.port}"}")
            .build()
    }
    private fun openAudio() {
        val epoch = ++audioGeneration; val token = generation
        sound?.cancel(); output?.close(); tuning.clear()
        audioPlaying = false; lastAudioAt = 0; audioOpenedAt = SystemClock.elapsedRealtime()
        val decoder = WebSdrAudioDecoder()
        var meterAt = 0L
        fun reportFailure(message: String) = publish(token) {
            if (epoch == audioGeneration) recoverAudio(message)
        }
        val ownedOutput = WebSdrAudioOutput(
            onPlaying = { publish(token) {
                if (epoch == audioGeneration) {
                    audioPlaying = true; error = null; state = ReceiverConnectionState.Connected
                    audioAttempt = 0; reconnectAttempt = 0
                    Log.i("WebSdrConnection", "Audio playing; waterfall recovery is independent")
                }
            } }, onError = ::reportFailure,
            onSignal = { value -> publish(token) { if (epoch == audioGeneration) signalDbm = value } },
            squelchSetting = { squelchSetting },
            onSquelchOpen = { value -> if (value != squelchOpen) publish(token) {
                if (epoch == audioGeneration) squelchOpen = value
            } },
            noiseModel = { com.example.sdr.audio.HamNoiseSettings.model(mode) },
            needsPlayedPcm = { cwDecoder.acceptsAudio() },
            onPlayedPcm = { rate, samples ->
                if (generation == token && audioGeneration == epoch) cwDecoder.offer(samples, samples.size, rate, 1)
            })
        output = ownedOutput
        sound = client.newWebSocket(socketRequest("/~~stream"), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = publish(token) {
                if (epoch == audioGeneration) { sendTuning(); tuning.flush() }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (generation != token || audioGeneration != epoch) return
                try {
                    decoder.decode(bytes.toByteArray()) { rate, samples ->
                        if (generation != token || audioGeneration != epoch) return@decode
                        lastAudioAt = SystemClock.elapsedRealtime()
                        ownedOutput.offer(rate, samples, decoder.signalDbm)
                        ftxDecoder.offer(samples, samples.size, rate, 1)
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (now - meterAt >= 100) {
                        meterAt = now
                        val rate = decoder.sampleRate
                        publish(token) { if (epoch == audioGeneration) { audioSampleRate = rate } }
                    }
                } catch (e: Exception) { reportFailure("Invalid server audio: ${e.message}") }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = reportFailure("Audio connection lost")
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = reportFailure("Audio connection closed")
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
        })
    }
    private fun desiredWindow() = WebSdrWaterfallWindow.forViewport(bands[currentBand],
        viewportCenterHz ?: frequencyHz, viewportSpanHz ?: bands[currentBand].spanHz)
    private fun openWaterfall() {
        val epoch = ++waterGeneration; val token = generation
        water?.close(); pendingFrame.set(null)
        val target = desiredWindow(); window = target; windowConfirmed = false
        waterOpenedAt = SystemClock.elapsedRealtime(); windowRequestedAt = waterOpenedAt; lastWaterAt = 0
        water = WebSdrWaterfallStream(client, ::socketRequest, bands[currentBand], target,
            onRow = { bins, center, span, at, coordinates ->
                if (token == generation && epoch == waterGeneration && window == coordinates) {
                    windowConfirmed = true
                    lastWaterAt = SystemClock.elapsedRealtime()
                    pendingFrame.set(PendingFrame(token, epoch, bins, center, span, at))
                    if (frameScheduled.compareAndSet(false, true)) handler.postDelayed(render, 34)
                }
            }, onFailure = { message -> publish(token) { if (epoch == waterGeneration) recoverWater(message) } })
        Log.d("WebSdrControl", "water_open generation=$epoch zoom=${target.zoom} start=${target.start}")
    }
    private fun updateWaterfall(target: WebSdrWaterfallWindow) {
        if (window == target && water != null) return
        // Let a requested window return data before superseding it. Otherwise a slower
        // server can stay permanently behind a continuous drag and never publish a row.
        if (water != null && !windowConfirmed) { viewport.offer(target); return }
        pendingFrame.set(null)
        window = target; windowConfirmed = false
        if (water?.update(target) == true) {
            windowRequestedAt = SystemClock.elapsedRealtime()
            Log.d("WebSdrControl", "water_update zoom=${target.zoom} start=${target.start}")
        } else openWaterfall()
    }
    private fun sendTuning() {
        cwDecoder.configure(active && mode == DemodulationMode.CW, frequencyHz)
        if (bands.isEmpty()) return
        val width = bandwidthHz / 1000.0
        val low = when (mode) {
            DemodulationMode.USB -> .15
            DemodulationMode.LSB -> -.15 - width
            DemodulationMode.CW -> -.75 - width / 2
            else -> -width / 2
        }
        val frequency = frequencyHz / 1000.0 + if (mode == DemodulationMode.CW) .75 else 0.0
        val nativeMode = when (mode) { DemodulationMode.AM -> 1; DemodulationMode.NFM -> 4; else -> 0 }
        tuning.offer(String.format(Locale.US, "GET /~~param?f=%.3f&band=%d&lo=%.5f&hi=%.5f&mode=%d&name=Radiola", frequency, bands[currentBand].index, low, low + width, nativeMode))
    }
    override fun tune(hz: Long) {
        if (!active || bands.isEmpty()) return
        val index = if (bands.getOrNull(currentBand)?.contains(hz) == true) currentBand else bands.indexOfFirst { it.contains(hz) }
        if (index < 0) {
            error = "Frequency outside this server's bands"
            handler.removeCallbacks(clearFrequencyWarning)
            handler.postDelayed(clearFrequencyWarning, 3000)
            return
        }
        tuneBand(index, hz)
    }
    private fun tuneBand(index: Int, hz: Long) {
        handler.removeCallbacks(clearFrequencyWarning)
        error = null; frequencyHz = hz
        val changed = currentBand != index; currentBand = index
        if (changed) {
            viewport.clear(); viewportCenterHz = null; viewportSpanHz = null
            bandwidthHz = bandwidthHz.coerceIn(100, bandwidthLimit)
            frame = null; waterfallHistory = WaterfallHistory(1024); openWaterfall()
        }
        sendTuning(); saveSoon()
    }
    override fun selectBand(index: Int) {
        if (!active || index !in bands.indices) return
        tuneBand(index, bands[index].defaultHz.coerceIn(bands[index].lowHz, bands[index].highHz))
    }
    override val bandwidthLimit get() = WebSdrBandwidth.maximum(mode, bands.getOrNull(currentBand))
    override fun requestMode(value: DemodulationMode) {
        if (value == DemodulationMode.WFM) return
        mode = value
        bandwidthDefault = !preferences.contains("bandwidth_${value.name}")
        bandwidthHz = preferences.getInt("bandwidth_${value.name}", WebSdrBandwidth.default(value)).coerceIn(100, bandwidthLimit)
        sendTuning(); saveSoon()
    }
    override fun setBandwidth(hz: Int?) {
        bandwidthDefault = hz == null
        bandwidthHz = (hz ?: WebSdrBandwidth.default(mode)).coerceIn(100, bandwidthLimit)
        val edit = preferences.edit()
        if (hz == null) edit.remove("bandwidth_${mode.name}") else edit.putInt("bandwidth_${mode.name}", bandwidthHz)
        edit.apply(); sendTuning()
    }
    override fun setWaterfallViewport(centerHz: Long, spanHz: Int) {
        if (!active || bands.isEmpty()) return
        viewportCenterHz = centerHz; viewportSpanHz = spanHz
        viewport.offer(desiredWindow())
    }
    private fun saveSoon() { handler.removeCallbacks(persist); handler.postDelayed(persist, 500) }
    private fun backoff(attempt: Int) = (1000L shl attempt.coerceIn(0, 5)).coerceAtMost(30_000)
    private fun recoverAudio(message: String) {
        ++audioGeneration; sound?.cancel(); sound = null; output?.close(); output = null
        tuning.clear(); cwDecoder.clear(); ftxDecoder.clear(); audioPlaying = false; signalDbm = null
        state = ReceiverConnectionState.Connecting; error = "$message; reconnecting"
        handler.removeCallbacks(retryAudio); handler.postDelayed(retryAudio, backoff(audioAttempt++))
        audioOpenedAt = 0
    }
    private fun recoverWater(message: String) {
        ++waterGeneration; water?.close(); water = null; pendingFrame.set(null); viewport.clear()
        waterfallWarning = "$message; retrying"; waterOpenedAt = 0
        handler.removeCallbacks(retryWater); handler.postDelayed(retryWater, backoff(waterAttempt++))
    }
    private fun reconnectSession(message: String) {
        closeStreams()
        state = ReceiverConnectionState.Connecting; error = "$message; reconnecting"
        handler.postDelayed(reconnect, backoff(reconnectAttempt++))
    }
    private val health = object : Runnable {
        override fun run() {
            if (!active) return
            val now = SystemClock.elapsedRealtime()
            if (audioOpenedAt > 0 && now - maxOf(audioOpenedAt, lastAudioAt) > 10_000) recoverAudio("Audio stream stalled")
            // Network watchdogs use elapsedRealtime throughout, including device suspend time.
            if (waterOpenedAt > 0 && ((windowRequestedAt > lastWaterAt && now - windowRequestedAt > 2500) ||
                now - maxOf(waterOpenedAt, lastWaterAt) > 10_000)) recoverWater("Waterfall stream stalled")
            val elapsed = now - fpsAt
            if (elapsed >= 1000) { visualFps = (framesInWindow * 1000L / elapsed.coerceAtLeast(1)).toInt(); framesInWindow = 0; fpsAt = now }
            if (now - lastMetricsAt >= 5000) {
                Log.d("WebSdrMetrics", "visual_fps=$visualFps audio=$audioMetrics water_retry=$waterAttempt")
                lastMetricsAt = now
            }
            handler.postDelayed(this, 1000)
        }
    }
    private fun closeStreams() {
        generation++; audioGeneration++; waterGeneration++
        tuning.clear(); viewport.clear()
        handler.removeCallbacks(reconnect); handler.removeCallbacks(retryAudio); handler.removeCallbacks(retryWater)
        metadata?.cancel(); metadata = null
        sound?.cancel(); sound = null; water?.close(); water = null; window = null
        output?.close(); output = null; audioPlaying = false
        audioOpenedAt = 0; waterOpenedAt = 0; lastAudioAt = 0; lastWaterAt = 0
        pendingFrame.set(null); handler.removeCallbacks(render); frameScheduled.set(false)
        cwDecoder.configure(false, frequencyHz)
        ftxDecoder.configure(null, frequencyHz)
    }
    override fun disconnect() {
        handler.removeCallbacks(persist); persist.run()
        closeStreams(); handler.removeCallbacks(health)
        frame = null; waterfallHistory = WaterfallHistory(1024)
        viewportCenterHz = null; viewportSpanHz = null; frameGeneration = 0
        reconnectAttempt = 0; audioAttempt = 0; waterAttempt = 0
        visualFps = 0; framesInWindow = 0; fpsAt = SystemClock.elapsedRealtime()
        signalDbm = null; error = null; waterfallWarning = null
        if (focusHeld) { audioManager.abandonAudioFocusRequest(focus); focusHeld = false }
        state = ReceiverConnectionState.Idle
    }
}
