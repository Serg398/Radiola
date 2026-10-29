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
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.log10

/** Native OpenWebRX client. Socket workers decode; the main thread owns UI state. */
internal class OpenWebRxSession(context: Context) : OnlineReceiverSession {
    override val cwDecoder = CwDecodeWorker()
    override val ftxDecoder = FtxDecodeWorker()
    override var state by mutableStateOf(WebSdrConnectionState.Idle); private set
    override var error by mutableStateOf<String?>(null); private set
    override var waterfallWarning by mutableStateOf<String?>(null); private set
    override var frequencyHz by mutableLongStateOf(7_100_000L); private set
    override var mode by mutableStateOf(DemodulationMode.AM); private set
    override var bandwidthHz by mutableIntStateOf(9000); private set
    override var bandwidthDefault by mutableStateOf(true); private set
    override var bands by mutableStateOf<List<WebSdrTunerBand>>(emptyList()); private set
    override var currentBand by mutableIntStateOf(0); private set
    override var audioSampleRate by mutableIntStateOf(12000); private set
    @Volatile override var squelchSetting = ReceiverSquelch()
    override var squelchOpen by mutableStateOf(true); private set
    override var signalDbm by mutableStateOf<Float?>(null); private set
    override var frame by mutableStateOf<SpectrumFrame?>(null); private set
    override var waterfallHistory by mutableStateOf(WaterfallHistory(1024)); private set
    override var visualFps by mutableIntStateOf(0); private set
    override val active get() = state == WebSdrConnectionState.Connecting || state == WebSdrConnectionState.Connected
    override val audioMetrics get() = output?.metrics ?: WebSdrAudioMetrics()
    override val bandwidthLimit get() = if (mode == DemodulationMode.NFM) 24000 else 11000
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS).build()
    @Volatile private var generation = 0L
    private var socket: WebSocket? = null
    private var landing: Call? = null
    private var output: WebSdrAudioOutput? = null
    private var focusHeld = false
    private var url = ""
    private var centerHz = 0L
    private var spanHz = 0
    private var profile = ""
    private var profiles = emptyList<Pair<String, String>>()
    private val knownBands = mutableMapOf<String, WebSdrTunerBand>()
    private var initialized = false
    private var changingProfile = false
    private var frames = 0
    private var frameId = 0L
    private var retry = 0
    @Volatile private var lastAudioAt = 0L
    private var openedAt = 0L
    private var fpsAt = 0L
    private data class Row(val token: Long, val bins: FloatArray, val center: Long, val span: Int, val timestampNanos: Long)
    private val pendingRow = AtomicReference<Row?>()
    private val render = object : Runnable {
        override fun run() {
            if (!active) return
            pendingRow.getAndSet(null)?.let { row ->
                if (row.token == generation && row.center == centerHz && row.span == spanHz) {
                    val captured = SpectrumFrame(row.bins, row.center, ++frameId, row.timestampNanos, row.token, row.span)
                    if (waterfallHistory.binCount != row.bins.size) waterfallHistory = WaterfallHistory(row.bins.size)
                    waterfallHistory.append(captured); frame = captured; frames++; waterfallWarning = null
                }
            }
            handler.postDelayed(this, 34)
        }
    }
    private val tuning = LatestCommand<JSONObject>(0, SystemClock::elapsedRealtime,
        { task, delay -> handler.postDelayed(task, delay); Unit }, handler::removeCallbacks) {
        if (active && initialized && !changingProfile) send("dspcontrol", it)
    }
    private val clearWarning = Runnable { if (error == "Frequency outside this profile") error = null }
    private val reconnect = Runnable { if (active) open() }
    private val health = object : Runnable {
        override fun run() {
            if (!active) return
            val now = SystemClock.elapsedRealtime()
            if (changingProfile && now - openedAt > 15_000) {
                recover("Profile change timed out")
            } else if (openedAt > 0 && now - maxOf(openedAt, lastAudioAt) > 20_000) {
                recover("Audio stream stalled")
            }
            if (now - fpsAt >= 1000) {
                visualFps = (frames * 1000L / (now - fpsAt).coerceAtLeast(1)).toInt()
                frames = 0; fpsAt = now
            }
            handler.postDelayed(this, 1000)
        }
    }
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
            state = WebSdrConnectionState.Error; error = "Invalid OpenWebRX address"; return
        }
        if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            state = WebSdrConnectionState.Error; error = "Audio focus unavailable"; return
        }
        focusHeld = true; this.url = url
        state = WebSdrConnectionState.Connecting
        open(); handler.post(render); handler.post(health)
    }

    private fun publish(token: Long, action: () -> Unit) {
        handler.post { if (token == generation && active) action() }
    }

    private fun open() {
        val token = generation
        openedAt = SystemClock.elapsedRealtime()
        val request = Request.Builder().url(url).build()
        landing = client.newCall(request).also { call ->
            call.timeout().timeout(20, TimeUnit.SECONDS)
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = publish(token) { recover("Cannot reach OpenWebRX") }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val base = it.request.url
                        if (!it.isSuccessful) { publish(token) { fail("Server HTTP ${it.code}") }; return }
                        publish(token) { landing = null; openSocket(base) }
                    }
                }
            })
        }
    }

    private fun openSocket(base: HttpUrl) {
        val token = generation
        val origin = base.resolve("/")!!.toString().removeSuffix("/")
        val request = Request.Builder().url(base.resolve("ws/")!!).header("Origin", origin).build()
        val ownedOutput = WebSdrAudioOutput(
            onPlaying = { publish(token) { state = WebSdrConnectionState.Connected; error = null; retry = 0 } },
            onError = { message -> publish(token) { recover(message) } },
            onSignal = { value -> publish(token) { signalDbm = value } },
            squelchSetting = { squelchSetting },
            onSquelchOpen = { value -> if (squelchOpen != value) publish(token) { squelchOpen = value } },
            needsPlayedPcm = { cwDecoder.acceptsAudio() },
            onPlayedPcm = { rate, samples -> if (token == generation) cwDecoder.offer(samples, samples.size, rate, 1) })
        output = ownedOutput
        socket = client.newWebSocket(request, object : WebSocketListener() {
            var audioCompression = "none"
            var fftCompression = "none"
            var fftSize = 0
            var audio = OpenWebRxAdpcm()
            var hdAudio = OpenWebRxAdpcm()
            var captureCenter = 0L
            var captureSpan = 0
            var signal: Float? = null
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("SERVER DE CLIENT client=Radiola type=receiver")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (token != generation) return
                try {
                    if (text.startsWith("CLIENT DE SERVER")) {
                        webSocket.send(JSONObject().put("type", "connectionproperties").put("params",
                            JSONObject().put("output_rate", 12000).put("hd_output_rate", 48000)).toString())
                        return
                    }
                    val message = JSONObject(text)
                    when (message.optString("type")) {
                        "config" -> {
                            val config = message.getJSONObject("value")
                            if ((config.has("center_freq") && config.optLong("center_freq") != captureCenter) ||
                                (config.has("samp_rate") && config.optInt("samp_rate") != captureSpan)) {
                                audio = OpenWebRxAdpcm(); hdAudio = OpenWebRxAdpcm()
                            }
                            audioCompression = config.optString("audio_compression", audioCompression)
                            fftCompression = config.optString("fft_compression", fftCompression)
                            fftSize = config.optInt("fft_size", fftSize)
                            captureCenter = config.optLong("center_freq", captureCenter)
                            captureSpan = config.optInt("samp_rate", captureSpan)
                            publish(token) { configure(config) }
                        }
                        "profiles" -> {
                            val entries = message.getJSONArray("value")
                            publish(token) { updateProfiles(entries) }
                        }
                        "smeter" -> {
                            val power = message.optDouble("value", Double.NaN)
                            signal = if (power.isFinite() && power > 0) (10 * log10(power)).toFloat() else null
                        }
                        "backoff" -> publish(token) { fail(message.optString("reason", "Server is busy")) }
                        "sdr_error", "demodulator_error" -> publish(token) { fail(message.optString("value", "Receiver unavailable")) }
                    }
                } catch (e: Exception) { publish(token) { fail("Invalid OpenWebRX message: ${e.message}") } }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (token != generation || bytes.size < 2) return
                try {
                    val payload = bytes.substring(1).toByteArray()
                    when (bytes[0].toInt()) {
                        1 -> {
                            if (captureSpan <= 0 || fftSize <= 0) return
                            val bins = OpenWebRxCodec.spectrum(payload, fftCompression, fftSize)
                            pendingRow.set(Row(token, bins, captureCenter, captureSpan, SystemClock.elapsedRealtimeNanos()))
                        }
                        2, 4 -> {
                            val rate = if (bytes[0].toInt() == 4) 48000 else 12000
                            val samples = when (audioCompression) {
                                "adpcm" -> (if (rate == 48000) hdAudio else audio).decode(payload, synced = true)
                                "none" -> OpenWebRxCodec.pcm(payload)
                                else -> error("Unsupported audio compression")
                            }
                            if (samples.isEmpty() || token != generation) return
                            lastAudioAt = SystemClock.elapsedRealtime()
                            ownedOutput.offer(rate, samples, signal)
                            ftxDecoder.offer(samples, samples.size, rate, 1)
                            if (audioSampleRate != rate) publish(token) { audioSampleRate = rate }
                        }
                    }
                } catch (e: Exception) {
                    publish(token) {
                        if (bytes[0].toInt() == 1) waterfallWarning = "Cannot decode spectrum: ${e.message}"
                        else fail("Invalid OpenWebRX audio: ${e.message}")
                    }
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = publish(token) {
                Log.w("OpenWebRx", "Connection failed", t); recover("OpenWebRX connection lost")
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = publish(token) {
                recover(reason.ifBlank { "OpenWebRX connection closed" })
            }
        })
    }

    private fun configure(config: JSONObject) {
        // Global settings arrive before the active SDR description on every connection.
        if (!initialized && !config.has("center_freq") && !config.has("samp_rate")) return
        val nextCenter = config.optLong("center_freq", centerHz)
        val nextSpan = config.optInt("samp_rate", spanHz)
        require(nextSpan in 0..2_000_000_000 && nextCenter >= 0)
        val nextProfile = if (config.has("sdr_id") || config.has("profile_id")) {
            val old = profile.split('|')
            config.optString("sdr_id", old.getOrElse(0) { "" }) + "|" + config.optString("profile_id", old.getOrElse(1) { "" })
        } else profile
        val restoreTuning = !initialized && retry > 0 && nextProfile == profile &&
            nextCenter == centerHz && nextSpan == spanHz
        val changed = !initialized || nextCenter != centerHz || nextSpan != spanHz || nextProfile != profile
        centerHz = nextCenter; spanHz = nextSpan; profile = nextProfile
        if (spanHz <= 0) return
        if (changed) {
            val low = centerHz - spanHz / 2
            val high = centerHz + spanHz / 2
            if (!restoreTuning) frequencyHz = config.optLong("start_freq", centerHz + config.optLong("start_offset_freq", 0)).coerceIn(low, high)
            if (!initialized && !restoreTuning) {
                mode = when (config.optString("start_mod")) {
                    "nfm" -> DemodulationMode.NFM
                    "usb" -> DemodulationMode.USB
                    "lsb" -> DemodulationMode.LSB
                    "cw" -> DemodulationMode.CW
                    else -> DemodulationMode.AM
                }
                bandwidthHz = WebSdrBandwidth.default(mode).coerceAtMost(bandwidthLimit)
            }
            knownBands[profile] = WebSdrTunerBand(0, "Current", centerHz, spanHz, frequencyHz, 0)
            rebuildBands()
            frame = null; pendingRow.set(null); waterfallHistory = WaterfallHistory(1024)
            changingProfile = false; initialized = true
            sendTuning()
            tuning.flush()
            socket?.send(JSONObject().put("type", "dspcontrol").put("action", "start").toString())
            Log.i("OpenWebRx", "Profile $profile center=$centerHz span=$spanHz")
        }
    }

    private fun updateProfiles(entries: JSONArray) {
        profiles = (0 until entries.length()).mapNotNull {
            val item = entries.optJSONObject(it) ?: return@mapNotNull null
            item.optString("id").takeIf(String::isNotBlank)?.let { id -> id to item.optString("name", id) }
        }.distinctBy { it.first }
        rebuildBands()
    }

    private fun rebuildBands() {
        val choices = profiles.toMutableList()
        if (choices.none { it.first == profile }) choices.add(profile to "Current")
        profiles = choices
        bands = choices.mapIndexed { index, (id, name) ->
            knownBands[id]?.copy(index = index, name = name)
                ?: WebSdrTunerBand(index, name, 0, 0, 0, 0)
        }
        currentBand = choices.indexOfFirst { it.first == profile }.coerceAtLeast(0)
    }

    private fun send(type: String, params: JSONObject) {
        socket?.send(JSONObject().put("type", type).put("params", params).toString())
    }

    private fun sendTuning() {
        if (!initialized || changingProfile) return
        cwDecoder.configure(active && mode == DemodulationMode.CW, frequencyHz)
        val half = bandwidthHz / 2
        val low = when (mode) {
            DemodulationMode.USB -> 150
            DemodulationMode.LSB -> -150 - bandwidthHz
            DemodulationMode.CW -> 750 - half
            else -> -half
        }
        tuning.offer(JSONObject().put("mod", mode.name.lowercase())
            .put("offset_freq", frequencyHz - centerHz - if (mode == DemodulationMode.CW) 750 else 0)
            .put("low_cut", low).put("high_cut", low + bandwidthHz)
            .put("squelch_level", -150).put("secondary_mod", false))
    }

    override fun tune(hz: Long) {
        if (!active || !initialized || changingProfile) return
        if (hz !in (centerHz - spanHz / 2)..(centerHz + spanHz / 2)) {
            error = "Frequency outside this profile"
            handler.removeCallbacks(clearWarning); handler.postDelayed(clearWarning, 3000); return
        }
        frequencyHz = hz; error = null; sendTuning()
    }
    override fun selectBand(index: Int) {
        if (!active || index !in profiles.indices || index == currentBand) return
        tuning.clear(); changingProfile = true
        send("selectprofile", JSONObject().put("profile", profiles[index].first))
        openedAt = SystemClock.elapsedRealtime(); lastAudioAt = 0
    }
    override fun requestMode(value: DemodulationMode) {
        if (value == DemodulationMode.WFM) return
        mode = value; bandwidthDefault = true
        bandwidthHz = WebSdrBandwidth.default(value).coerceAtMost(bandwidthLimit)
        sendTuning()
    }
    override fun setBandwidth(hz: Int?) {
        bandwidthDefault = hz == null
        bandwidthHz = (hz ?: WebSdrBandwidth.default(mode)).coerceIn(100, bandwidthLimit)
        sendTuning()
    }
    // OpenWebRX sends the active profile's full FFT; viewport zoom stays local.
    override fun setWaterfallViewport(centerHz: Long, spanHz: Int) = Unit

    private fun closeTransport() {
        generation++; tuning.clear(); pendingRow.set(null)
        landing?.cancel(); landing = null
        socket?.cancel(); socket = null; output?.close(); output = null
        initialized = false; changingProfile = false; openedAt = 0; lastAudioAt = 0
        signalDbm = null; visualFps = 0
        cwDecoder.configure(false, frequencyHz); ftxDecoder.configure(null, frequencyHz)
    }
    private fun recover(message: String) {
        closeTransport(); error = "$message; reconnecting"; state = WebSdrConnectionState.Connecting
        handler.removeCallbacks(reconnect)
        handler.postDelayed(reconnect, (1000L shl retry++.coerceAtMost(5)).coerceAtMost(30_000))
    }
    private fun fail(message: String) {
        disconnect(); state = WebSdrConnectionState.Error; error = message
    }
    override fun disconnect() {
        handler.removeCallbacksAndMessages(null); closeTransport()
        bands = emptyList(); knownBands.clear(); profiles = emptyList(); profile = ""
        centerHz = 0; spanHz = 0; frame = null; waterfallHistory = WaterfallHistory(1024)
        frames = 0; frameId = 0; retry = 0; fpsAt = SystemClock.elapsedRealtime()
        error = null; waterfallWarning = null; state = WebSdrConnectionState.Idle
        if (focusHeld) { audioManager.abandonAudioFocusRequest(focus); focusHeld = false }
    }
}
