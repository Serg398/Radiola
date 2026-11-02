package com.example.sdr.sources.openwebrx

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
import com.example.sdr.receiver.BandwidthOptions
import com.example.sdr.receiver.LatestCommand
import com.example.sdr.receiver.OnlineReceiverSession
import com.example.sdr.receiver.ReceiverAudioMetrics
import com.example.sdr.receiver.ReceiverBand
import com.example.sdr.receiver.ReceiverConnectionState
import com.example.sdr.receiver.ReceiverModeOption
import com.example.sdr.receiver.ReceiverSquelch
import com.example.sdr.receiver.WaterfallHistory
import com.example.sdr.receiver.offsetForBandwidth
import com.example.sdr.sources.websdr.WebSdrBandwidth
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject

/** Native OpenWebRX client. Socket workers decode; the main thread owns UI state. */
internal class OpenWebRxSession(context: Context) : OnlineReceiverSession {
    private var modeDescriptors by mutableStateOf<List<OpenWebRxMode>>(emptyList())
    private var features by mutableStateOf<Map<String, Boolean>>(emptyMap())
    private var selectedMode by mutableStateOf<OpenWebRxMode?>(null)
    private var initialModeId = "am"
    private val availableModes get() = modeDescriptors.filter { candidate -> candidate.requirements.all { features[it] == true } }
    override val serverModes get() = availableModes.map {
        ReceiverModeOption(it.id, it.label, it.secondary || it.id !in setOf("am", "nfm", "fm", "wfm", "usb", "lsb", "cw", "sam"))
    }
    override val serverModeId get() = selectedMode?.id
    override val modeLabel get() = selectedMode?.label ?: mode.label
    override val serverDataMode get() = selectedMode?.secondary == true
    override var decodedText by mutableStateOf(""); private set
    private val sstv = OpenWebRxSstv()
    override var sstvImage by mutableStateOf<com.example.sdr.receiver.SstvImage?>(null); private set
    override fun clearDecodedText() { decodedText = ""; sstv.clear(); sstvImage = null }
    override val passbandOffsetHz get() = selectedMode?.let {
        (it.lowCut(bandwidthHz) ?: -bandwidthHz / 2) + bandwidthHz / 2 - it.cwTone
    } ?: mode.offsetForBandwidth(bandwidthHz)
    private val needsAudio get() = selectedMode?.hasAudio != false
    override val bandwidthAdjustable get() = selectedMode?.let { it.low != null } ?: true
    override val bandwidthOptions get() = BandwidthOptions(selectedMode?.defaultWidth ?: WebSdrBandwidth.default(mode),
        100, bandwidthLimit, listOf(100, 200, 300, 500, 1000, 2400, 3000, 5000, 6000, 8000, 9000,
            10000, 12500, 15000, 20000, 25000, 50000, 100000, 150000, 200000, 250000).filter { it <= bandwidthLimit })
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
    override var state by mutableStateOf(ReceiverConnectionState.Idle); private set
    override var error by mutableStateOf<String?>(null); private set
    override var waterfallWarning by mutableStateOf<String?>(null); private set
    override var frequencyHz by mutableLongStateOf(7_100_000L); private set
    override var mode by mutableStateOf(DemodulationMode.AM); private set
    override var bandwidthHz by mutableIntStateOf(9000); private set
    override var bandwidthDefault by mutableStateOf(true); private set
    override var bands by mutableStateOf<List<ReceiverBand>>(emptyList()); private set
    override var currentBand by mutableIntStateOf(0); private set
    override var audioSampleRate by mutableIntStateOf(12000); private set
    @Volatile override var squelchSetting = ReceiverSquelch()
    override var squelchOpen by mutableStateOf(true); private set
    override var signalDbm by mutableStateOf<Float?>(null); private set
    override var frame by mutableStateOf<SpectrumFrame?>(null); private set
    override var waterfallHistory by mutableStateOf(WaterfallHistory(1024)); private set
    override var visualFps by mutableIntStateOf(0); private set
    override val active get() = state == ReceiverConnectionState.Connecting || state == ReceiverConnectionState.Connected
    override val audioMetrics get() = output?.metrics ?: ReceiverAudioMetrics()
    override val bandwidthLimit get() = maxOf(if (mode == DemodulationMode.WFM) 250000 else 24000,
        selectedMode?.defaultWidth ?: 0).coerceAtMost(20_000_000)
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
    private val knownBands = mutableMapOf<String, ReceiverBand>()
    private data class Resume(val profile: String, val frequency: Long?, val mode: DemodulationMode,
        val descriptor: OpenWebRxMode?, val bandwidth: Int, val defaultBandwidth: Boolean)
    private var resume: Resume? = null
    private var requestedProfile: String? = null
    private var profilesFresh = false
    private var restoreSent = false
    private var serverConfig = JSONObject()
    private var geometryRevision = 0L
    private var requestedGeometryRevision = -1L
    private var configScheduled = false
    private var profileRequestedAt = 0L
    private val applyConfig = Runnable { configScheduled = false; configure(serverConfig) }
    private fun queueConfig(config: JSONObject) {
        if (config.has("center_freq") || config.has("samp_rate")) geometryRevision++
        // Servers may send only the relative start frequency for the next profile.
        // An absolute value retained from the previous profile must not override it.
        if (config.has("start_offset_freq") && !config.has("start_freq")) serverConfig.remove("start_freq")
        config.keys().forEach { serverConfig.put(it, config.get(it)) }
        // A profile's identity, center and start frequency can arrive separately.
        if (!configScheduled) {
            configScheduled = true
            handler.postDelayed(applyConfig, 50)
        }
    }
    private var initialized = false
    private var changingProfile = false
    private var frames = 0
    private var frameId = 0L
    private var retry = 0
    @Volatile private var lastAudioAt = 0L
    @Volatile private var lastPacketAt = 0L
    private var openedAt = 0L
    private var fpsAt = 0L
    private val receivedFft = AtomicInteger()
    private val receivedAudio = AtomicInteger()
    private val replacedFft = AtomicInteger()
    private var diagnosticsAt = 0L
    private var renderedFft = 0
    private data class Row(val token: Long, val bins: FloatArray, val center: Long, val span: Int, val timestampNanos: Long)
    private val pendingRow = AtomicReference<Row?>()
    private var lastSentTuning: JSONObject? = null
    private val render = object : Runnable {
        override fun run() {
            if (!active) return
            pendingRow.getAndSet(null)?.let { row ->
                if (initialized && !changingProfile && row.token == generation && row.center == centerHz && row.span == spanHz) {
                    val captured = SpectrumFrame(row.bins, row.center, ++frameId, row.timestampNanos, row.token, row.span)
                    if (waterfallHistory.binCount != row.bins.size) waterfallHistory = WaterfallHistory(row.bins.size)
                    waterfallHistory.append(captured); frame = captured; frames++; renderedFft++; waterfallWarning = null
                    if (!needsAudio && initialized && state != ReceiverConnectionState.Connected) {
                        state = ReceiverConnectionState.Connected; error = null; retry = 0
                    }
                }
            }
            handler.postDelayed(this, 34)
        }
    }
    // Keep gesture updates local; the remote DSP only needs the latest frequency.
    private val tuning = LatestCommand<JSONObject>(34, SystemClock::elapsedRealtime,
        { task, delay -> handler.postDelayed(task, delay); Unit }, handler::removeCallbacks) {
        if (active && initialized && !changingProfile) {
            val previous = lastSentTuning
            val changed = JSONObject()
            it.keys().forEach { key ->
                if (previous == null || !previous.has(key) || previous.opt(key) != it.opt(key))
                    changed.put(key, it.get(key))
            }
            if (changed.length() > 0 && send("dspcontrol", changed)) lastSentTuning = it
        }
    }
    private val clearWarning = Runnable { if (error == "Frequency outside this profile") error = null }
    private val reconnect = Runnable { if (active) open() }
    private val health = object : Runnable {
        override fun run() {
            if (!active) return
            val now = SystemClock.elapsedRealtime()
            if (now - diagnosticsAt >= 10_000) {
                val seconds = (now - diagnosticsAt).coerceAtLeast(1) / 1000.0
                Log.i("OpenWebRx", "Stream ${url.toHttpUrlOrNull()?.host}: window=${seconds}s " +
                    "fft=${receivedFft.getAndSet(0)} rendered=$renderedFft replaced=${replacedFft.getAndSet(0)} " +
                    "audio=${receivedAudio.getAndSet(0)} audioAge=${now - lastAudioAt}ms " +
                    "packetAge=${now - lastPacketAt}ms state=$state profileChange=$changingProfile")
                renderedFft = 0; diagnosticsAt = now
            }
            if (changingProfile && now - openedAt > 15_000) {
                recover("Profile change timed out")
            } else if (openedAt > 0 && now - maxOf(openedAt, if (needsAudio) lastAudioAt else lastPacketAt) > 20_000) {
                recover("Audio stream stalled")
            }
            if (now - fpsAt >= 1000) {
                visualFps = (frames * 1000.0 / (now - fpsAt).coerceAtLeast(1)).roundToInt()
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
            state = ReceiverConnectionState.Error; error = "Invalid OpenWebRX address"; return
        }
        if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            state = ReceiverConnectionState.Error; error = "Audio focus unavailable"; return
        }
        focusHeld = true; this.url = url
        state = ReceiverConnectionState.Connecting
        open(); handler.post(render); handler.post(health)
    }

    private fun publish(token: Long, action: () -> Unit) {
        handler.post { if (token == generation && active) action() }
    }

    private fun open() {
        val token = generation
        openedAt = SystemClock.elapsedRealtime()
        diagnosticsAt = openedAt; renderedFft = 0
        receivedFft.set(0); receivedAudio.set(0); replacedFft.set(0)
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
            onPlaying = { publish(token) { if (initialized && !changingProfile) { state = ReceiverConnectionState.Connected; error = null; retry = 0 } } },
            onError = { message -> publish(token) { recover(message) } },
            onSignal = { value -> publish(token) { signalDbm = value } },
            squelchSetting = { squelchSetting },
            onSquelchOpen = { value -> if (squelchOpen != value) publish(token) { squelchOpen = value } },
            noiseModel = { if (serverDataMode) 0 else com.example.sdr.audio.HamNoiseSettings.model(mode) },
            needsPlayedPcm = { cwDecoder.acceptsAudio() },
            onPlayedPcm = { rate, samples -> if (token == generation) cwDecoder.offer(samples, samples.size, rate, 1) })
        output = ownedOutput
        socket = client.newWebSocket(request, object : WebSocketListener() {
            val decoder = OpenWebRxStreamDecoder()
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("SERVER DE CLIENT client=Radiola type=receiver")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (token != generation) return
                lastPacketAt = SystemClock.elapsedRealtime()
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
                            decoder.configure(config)
                            publish(token) { queueConfig(config) }
                        }
                        "profiles" -> {
                            val entries = message.getJSONArray("value")
                            publish(token) { updateProfiles(entries) }
                        }
                        "modes" -> {
                            val descriptors = OpenWebRxMode.parse(message.getJSONArray("value"))
                            publish(token) { modeDescriptors = descriptors; adoptInitialMode() }
                        }
                        "features" -> {
                            val value = message.getJSONObject("value")
                            val available = value.keys().asSequence().associateWith { value.optBoolean(it, false) }
                            publish(token) { features = available }
                        }
                        "secondary_demod" -> {
                            val value = message.opt("value")
                            if (value is JSONObject && value.optString("mode").equals("SSTV", ignoreCase = true)) {
                                publish(token) {
                                    if (selectedMode?.id == "sstv") sstv.accept(value)?.let { sstvImage = it }
                                }
                                return
                            }
                            val line = formatDecoded(value)
                            publish(token) {
                                if (serverDataMode && line.isNotEmpty()) {
                                    decodedText = (decodedText + line).takeLast(8000)
                                    if (!needsAudio) state = ReceiverConnectionState.Connected
                                }
                            }
                        }
                        "smeter" -> {
                            val power = message.optDouble("value", Double.NaN)
                            decoder.signal(power)
                        }
                        "backoff" -> publish(token) { fail(message.optString("reason", "Server is busy")) }
                        "sdr_error", "demodulator_error" -> publish(token) { fail(message.optString("value", "Receiver unavailable")) }
                    }
                } catch (e: Exception) { publish(token) { fail("Invalid OpenWebRX message: ${e.message}") } }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (token != generation || bytes.size < 2) return
                lastPacketAt = SystemClock.elapsedRealtime()
                try {
                    val payload = bytes.substring(1).toByteArray()
                    when (bytes[0].toInt()) {
                        1 -> {
                            val bins = decoder.spectrum(payload) ?: return
                            receivedFft.incrementAndGet()
                            if (pendingRow.getAndSet(Row(token, bins, decoder.centerHz, decoder.spanHz,
                                    SystemClock.elapsedRealtimeNanos())) != null) replacedFft.incrementAndGet()
                        }
                        2, 4 -> {
                            val rate = if (bytes[0].toInt() == 4) 48000 else 12000
                            val samples = decoder.audio(payload, rate == 48000)
                            if (samples.isEmpty() || token != generation) return
                            lastAudioAt = SystemClock.elapsedRealtime()
                            receivedAudio.incrementAndGet()
                            ownedOutput.offer(rate, samples, decoder.signalDbm)
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
                Log.i("OpenWebRx", "Socket closed: code=$code reason=$reason")
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
        val saved = resume
        if (saved == null && requestedProfile != null) {
            if (nextProfile != requestedProfile) return
            val known = knownBands[nextProfile]
            val unchangedGeometry = known != null && known.centerHz == nextCenter && known.spanHz == nextSpan
            if (geometryRevision <= requestedGeometryRevision && !unchangedGeometry) return
            val start = config.optLong("start_freq", nextCenter + config.optLong("start_offset_freq", 0))
            if (start !in (nextCenter - nextSpan / 2)..(nextCenter + nextSpan / 2)) return
        }
        if (saved != null) {
            if (nextProfile != saved.profile) { restoreProfile(); return }
            // Do not combine a new profile ID with the previous profile's geometry.
            if (saved.frequency != null && saved.frequency !in
                (nextCenter - nextSpan / 2)..(nextCenter + nextSpan / 2)) return
        }
        val restoreTuning = saved != null
        val changed = !initialized || nextCenter != centerHz || nextSpan != spanHz || nextProfile != profile
        centerHz = nextCenter; spanHz = nextSpan; profile = nextProfile
        if (spanHz <= 0) return
        if (saved != null) {
            mode = saved.mode; selectedMode = saved.descriptor
            initialModeId = selectedMode?.id ?: mode.name.lowercase()
            bandwidthHz = saved.bandwidth.coerceIn(100, bandwidthLimit)
            bandwidthDefault = saved.defaultBandwidth
            frequencyHz = saved.frequency ?: config.optLong("start_freq", centerHz)
            resume = null
        }
        if (changed) {
            lastSentTuning = null
            val low = centerHz - spanHz / 2
            val high = centerHz + spanHz / 2
            if (!restoreTuning) frequencyHz = config.optLong("start_freq", centerHz + config.optLong("start_offset_freq", 0)).coerceIn(low, high)
            if (!initialized && !restoreTuning) {
                selectedMode = null
                initialModeId = config.optString("start_mod", "am")
                mode = when (config.optString("start_mod")) {
                    "nfm" -> DemodulationMode.NFM
                    "wfm" -> DemodulationMode.WFM
                    "usb" -> DemodulationMode.USB
                    "lsb" -> DemodulationMode.LSB
                    "cw" -> DemodulationMode.CW
                    else -> DemodulationMode.AM
                }
                bandwidthHz = WebSdrBandwidth.default(mode).coerceAtMost(bandwidthLimit)
                val initialId = config.optString("start_mod", "am")
                modeDescriptors.firstOrNull { it.id == initialId }?.let { applyServerMode(it) }
            }
            knownBands[profile] = ReceiverBand(0, "Current", centerHz, spanHz, frequencyHz, 0)
            rebuildBands()
            frame = null; pendingRow.set(null); waterfallHistory = WaterfallHistory(1024)
            changingProfile = false; initialized = true; requestedProfile = null
            sendTuning()
            tuning.flush()
            socket?.send(JSONObject().put("type", "dspcontrol").put("action", "start").toString())
            val switchMs = if (profileRequestedAt > 0) SystemClock.elapsedRealtime() - profileRequestedAt else 0
            Log.i("OpenWebRx", "Profile $profile center=$centerHz span=$spanHz switchMs=$switchMs")
            profileRequestedAt = 0
        }
    }

    private fun restoreProfile() {
        val saved = resume ?: return
        if (!profilesFresh || restoreSent) return
        if (profiles.none { it.first == saved.profile }) {
            fail("Selected profile is no longer available"); return
        }
        restoreSent = true
        send("selectprofile", JSONObject().put("profile", saved.profile))
        Log.i("OpenWebRx", "Restoring profile ${saved.profile} frequency=${saved.frequency}")
    }

    private fun updateProfiles(entries: JSONArray) {
        profilesFresh = true
        profiles = (0 until entries.length()).mapNotNull {
            val item = entries.optJSONObject(it) ?: return@mapNotNull null
            item.optString("id").takeIf(String::isNotBlank)?.let { id -> id to item.optString("name", id) }
        }.distinctBy { it.first }
        rebuildBands()
        restoreProfile()
    }

    private fun rebuildBands() {
        val choices = profiles.toMutableList()
        if (profile.isNotBlank() && choices.none { it.first == profile }) choices.add(profile to "Current")
        profiles = choices
        bands = choices.mapIndexed { index, (id, name) ->
            knownBands[id]?.copy(index = index, name = name)
                ?: ReceiverBand(index, name, 0, 0, 0, 0)
        }
        currentBand = choices.indexOfFirst { it.first == profile }.coerceAtLeast(0)
    }

    private fun send(type: String, params: JSONObject): Boolean =
        socket?.send(JSONObject().put("type", type).put("params", params).toString()) == true

    private fun sendTuning() {
        if (!initialized || changingProfile) return
        cwDecoder.configure(active && mode == DemodulationMode.CW, frequencyHz)
        val selected = selectedMode
        val half = bandwidthHz / 2
        val low = if (selected != null) selected.lowCut(bandwidthHz) else when (mode) {
            DemodulationMode.USB -> 150
            DemodulationMode.LSB -> -150 - bandwidthHz
            DemodulationMode.CW -> 750 - half
            else -> -half
        }
        val tone = selected?.cwTone ?: if (mode == DemodulationMode.CW) 750 else 0
        tuning.offer(JSONObject().put("mod", selected?.baseId ?: mode.name.lowercase())
            .put("offset_freq", frequencyHz - centerHz - tone)
            .put("low_cut", low ?: JSONObject.NULL).put("high_cut", low?.plus(bandwidthHz) ?: JSONObject.NULL)
            .put("squelch_level", -150).put("secondary_offset_freq", 1000)
            .put("secondary_mod", if (selected?.secondary == true) selected.id else false))
    }

    private fun adoptInitialMode() {
        if (selectedMode != null) return
        modeDescriptors.firstOrNull { it.id == initialModeId }?.let { applyServerMode(it); sendTuning() }
    }
    private fun applyServerMode(descriptor: OpenWebRxMode) {
        selectedMode = descriptor; mode = descriptor.basicMode
        initialModeId = descriptor.id
        bandwidthDefault = true; bandwidthHz = descriptor.defaultWidth.coerceIn(100, bandwidthLimit)
        clearDecodedText()
        openedAt = SystemClock.elapsedRealtime(); lastAudioAt = 0
    }
    override fun requestServerMode(id: String) {
        val descriptor = availableModes.firstOrNull { it.id == id } ?: return
        if (descriptor.ifRate != null && spanHz > 0 && descriptor.ifRate > spanHz) {
            error = "Profile is too narrow for ${descriptor.label}"; return
        }
        applyServerMode(descriptor); error = null; sendTuning()
    }
    private fun formatDecoded(value: Any?): String = when (value) {
        is String -> value.take(2000)
        is JSONObject -> value.keys().asSequence()
            .filter { it.lowercase() !in setOf("data", "image", "pixels", "samples", "png", "buffer") }
            .take(12).joinToString("  ") { "$it: ${value.opt(it).toString().take(180)}" }.take(2000) + "\n"
        else -> ""
    }

    override fun tune(hz: Long) {
        if (!active || !initialized || changingProfile) return
        if (hz !in (centerHz - spanHz / 2)..(centerHz + spanHz / 2)) {
            error = "Frequency outside this profile"
            handler.removeCallbacks(clearWarning); handler.postDelayed(clearWarning, 3000); return
        }
        if (hz != frequencyHz && selectedMode?.id == "sstv") clearDecodedText()
        frequencyHz = hz; error = null; sendTuning()
    }
    override fun selectBand(index: Int) {
        if (!active || index !in profiles.indices || index == currentBand) return
        tuning.clear(); changingProfile = true
        requestedProfile = profiles[index].first
        profileRequestedAt = SystemClock.elapsedRealtime()
        Log.i("OpenWebRx", "Request profile $requestedProfile")
        requestedGeometryRevision = geometryRevision
        send("selectprofile", JSONObject().put("profile", requestedProfile))
        openedAt = SystemClock.elapsedRealtime(); lastAudioAt = 0
    }
    override fun requestMode(value: DemodulationMode) {
        val id = value.name.lowercase()
        if (modeDescriptors.isNotEmpty()) { requestServerMode(id); return }
        if (value == DemodulationMode.WFM) return
        selectedMode = null
        mode = value; bandwidthDefault = true
        bandwidthHz = WebSdrBandwidth.default(value).coerceAtMost(bandwidthLimit)
        sendTuning()
    }
    override fun setBandwidth(hz: Int?) {
        bandwidthDefault = hz == null
        bandwidthHz = (hz ?: selectedMode?.defaultWidth ?: WebSdrBandwidth.default(mode)).coerceIn(100, bandwidthLimit)
        sendTuning()
    }
    // OpenWebRX sends the active profile's full FFT; viewport zoom stays local.
    override fun setWaterfallViewport(centerHz: Long, spanHz: Int) = Unit

    private fun closeTransport() {
        generation++; tuning.clear(); pendingRow.set(null)
        lastSentTuning = null
        profilesFresh = false; restoreSent = false; serverConfig = JSONObject()
        handler.removeCallbacks(applyConfig)
        configScheduled = false; profileRequestedAt = 0
        landing?.cancel(); landing = null
        socket?.cancel(); socket = null; output?.close(); output = null
        initialized = false; changingProfile = false; openedAt = 0; lastAudioAt = 0; lastPacketAt = 0
        signalDbm = null; visualFps = 0
        cwDecoder.configure(false, frequencyHz); ftxDecoder.configure(null, frequencyHz)
    }
    private fun recover(message: String) {
        if (resume == null && profile.isNotBlank()) {
            val target = requestedProfile ?: profile
            resume = Resume(target, if (target == profile) frequencyHz else knownBands[target]?.defaultHz,
                mode, selectedMode, bandwidthHz, bandwidthDefault)
        }
        Log.i("OpenWebRx", "Recovering: $message; selected=${resume?.profile}")
        closeTransport(); error = "$message; reconnecting"; state = ReceiverConnectionState.Connecting
        handler.removeCallbacks(reconnect)
        handler.postDelayed(reconnect, (1000L shl retry++.coerceAtMost(5)).coerceAtMost(30_000))
    }
    private fun fail(message: String) {
        disconnect(); state = ReceiverConnectionState.Error; error = message
    }
    override fun disconnect() {
        handler.removeCallbacksAndMessages(null); closeTransport()
        bands = emptyList(); knownBands.clear(); profiles = emptyList(); profile = ""
        modeDescriptors = emptyList(); features = emptyMap(); selectedMode = null; clearDecodedText()
        resume = null; requestedProfile = null
        centerHz = 0; spanHz = 0; frame = null; waterfallHistory = WaterfallHistory(1024)
        frames = 0; frameId = 0; retry = 0; fpsAt = SystemClock.elapsedRealtime()
        error = null; waterfallWarning = null; state = ReceiverConnectionState.Idle
        if (focusHeld) { audioManager.abandonAudioFocusRequest(focus); focusHeld = false }
    }
}
