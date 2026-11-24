package com.example.sdr.plugins

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class PluginManager(private val context: Context) {
    private val store = PluginStore(context)
    private val importLock = Mutex()
    var installed by mutableStateOf(emptyList<PluginManifest>())
        private set
    private var loaded = false

    suspend fun loadInstalled() = importLock.withLock {
        if (loaded) return@withLock
        installed = withContext(Dispatchers.IO) { store.list() }
        loaded = true
    }

    suspend fun importPlugin(uri: Uri) = importLock.withLock {
        val plugin = withContext(Dispatchers.IO) { store.install(uri) }
        if (selected?.id == plugin.id) select(null)
        installed = (installed.filterNot { it.id == plugin.id } + plugin).sortedBy { it.name }
    }
    internal data class AudioSpectrum(val sampleRate: Int, val bins: FloatArray)
    internal data class PcmBlock(val rate: Int, val startMs: Double, val index: Long, val epoch: Long, val bytes: ByteArray)
    private val pcmQueue = ArrayDeque<PcmBlock>()
    private var pcmRate = 0
    private var pcmStartMs = 0.0
    private var pcmIndex = 0L
    private var pcmEpoch = 0L
    private var lastPcmMs = 0L

    @Synchronized
    fun drainPcm(): List<PcmBlock> = pcmQueue.toList().also { pcmQueue.clear() }

    internal class Block(val id: Int, val endpoint: Messenger, val sampleCount: Int, val analysisRate: Int = 0) {
        @Volatile var output: ShortArray? = null
        val ready = CountDownLatch(1)
    }

    var selected by mutableStateOf<PluginManifest?>(null)
        private set
    var playing by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var ready by mutableStateOf(false)
        private set
    var spectrum by mutableStateOf<AudioSpectrum?>(null)
        private set
    val processedBlocks = AtomicLong()
    val fallbackBlocks = AtomicLong()
    var gain by mutableDoubleStateOf(1.5)
        private set
    @Volatile private var activeGain = 1.5
    @Volatile private var endpoint: Messenger? = null
    private var startupEndpoint: Messenger? = null
    @Volatile private var activePlugin: PluginManifest? = null
    private val analysisWindow = ShortArray(SPECTRUM_SAMPLES)
    private var analysisCount = 0
    private var analysisRate = 0
    private var nextAnalysisNs = 0L
    private val pending = ConcurrentHashMap<Int, Block>()
    private val sequence = java.util.concurrent.atomic.AtomicInteger()
    private val handler = Handler(Looper.getMainLooper())
    private val replyThread by lazy { HandlerThread("PluginReplies").apply { start() } }
    private var startupId = 0
    private val reply by lazy { Messenger(Handler(replyThread.looper) { message ->
        if (message.what == LOAD_PLUGIN) {
            val id = message.arg1
            val success = message.arg2 == 1
            handler.post {
                if (playing && id == startupId) {
                    if (success) {
                        endpoint = startupEndpoint
                        ready = true
                    } else fail("Cannot load plugin script")
                }
            }
            return@Handler true
        }
        val block = pending.remove(message.arg1)
        if (block != null) {
            if (block.analysisRate > 0) {
                val bins = message.data.getFloatArray("spectrum")
                if (bins == null || bins.size != SPECTRUM_SAMPLES / 2 + 1) {
                    reportFailure(block, "Spectrum processing failed")
                } else {
                    handler.post {
                        if (endpoint === block.endpoint) spectrum = AudioSpectrum(block.analysisRate, bins)
                    }
                }
            } else {
                block.output = message.data.getShortArray("pcm")?.takeIf { it.size == block.sampleCount }
                if (block.output == null) reportFailure(block, "Script processing failed")
            }
            block.ready.countDown()
        }
        true
    }) }
    private var bound = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (bound && playing) {
                val plugin = activePlugin ?: return
                val id = sequence.incrementAndGet()
                startupId = id
                val target = Messenger(service)
                val request = Message.obtain(null, LOAD_PLUGIN, id, 0).apply {
                    replyTo = reply
                    data = Bundle().apply {
                        putString("script", plugin.script)
                        putBoolean("analysis", plugin.output == PluginPort.CARD)
                    }
                }
                try {
                    target.send(request)
                } catch (_: android.os.RemoteException) {
                    fail("Script process disconnected")
                    return
                }
                handler.postDelayed({
                    if (playing && startupId == id && !ready) fail("Script startup timed out")
                }, 2000)
                startupEndpoint = target
            }
        }

        override fun onServiceDisconnected(name: ComponentName) = fail("Script process disconnected")
        override fun onBindingDied(name: ComponentName) = fail("Script process stopped")
        override fun onNullBinding(name: ComponentName) = fail("Script process unavailable")
    }

    fun select(plugin: PluginManifest?) {
        stop()
        error = null
        processedBlocks.set(0)
        fallbackBlocks.set(0)
        selected = plugin
    }

    fun updateGain(value: Double) {
        gain = value.coerceIn(.25, 2.0)
        activeGain = gain
    }

    fun play() {
        if (playing || selected == null) return
        error = null
        processedBlocks.set(0)
        fallbackBlocks.set(0)
        activePlugin = selected
        playing = true
        if (selected?.webWorker == true) {
            ready = true
            return
        }
        bound = context.bindService(Intent(context, PluginScriptService::class.java), connection, Context.BIND_AUTO_CREATE)
        if (!bound) fail("Cannot start script process")
    }

    @Synchronized fun stop() {
        playing = false
        ready = false
        activePlugin = null
        endpoint = null
        startupEndpoint = null
        startupId = 0
        spectrum = null
        pcmQueue.clear()
        pcmRate = 0
        analysisCount = 0
        analysisRate = 0
        nextAnalysisNs = 0
        pending.values.forEach { it.ready.countDown() }
        pending.clear()
        if (bound) {
            bound = false
            context.unbindService(connection)
        }
    }

    private fun fail(message: String) {
        stop()
        error = message
    }

    private fun reportFailure(block: Block, message: String) {
        block.ready.countDown()
        handler.post { if (endpoint === block.endpoint) fail(message) }
    }

    /** Accept bursts of network PCM without waiting for the script. */
    @Synchronized
    fun submit(samples: ShortArray): Block? {
        if (activePlugin?.output != PluginPort.PCM) return null
        val target = endpoint ?: return null
        if (samples.size !in 1..8192 || pending.size >= 16) return null
        return send(target, samples, 0)
    }

    @Synchronized
    fun observePcm(samples: ShortArray, count: Int, rate: Int, channels: Int) {
        val plugin = activePlugin ?: return
        if (plugin.input != PluginPort.PCM || plugin.output != PluginPort.CARD || rate <= 0 || channels !in 1..2) return
        if (plugin.webWorker) {
            queuePcm(samples, count, rate, channels)
            return
        }
        val target = endpoint ?: return
        val now = System.nanoTime()
        if (now < nextAnalysisNs || pending.isNotEmpty() || rate <= 0 || channels !in 1..2) return
        if (analysisRate != rate) {
            analysisRate = rate
            analysisCount = 0
        }
        for (index in 0 until (minOf(count, samples.size) / channels) * channels step channels) {
            val sample = if (channels == 1) samples[index]
                else ((samples[index].toInt() + samples[index + 1].toInt()) / 2).toShort()
            analysisWindow[analysisCount++] = sample
            if (analysisCount == SPECTRUM_SAMPLES) {
                analysisCount = 0
                nextAnalysisNs = now + 100_000_000L
                send(target, analysisWindow.copyOf(), rate)
                break
            }
        }
    }

    private fun queuePcm(samples: ShortArray, count: Int, rate: Int, channels: Int) {
        val frames = minOf(count, samples.size) / channels
        if (frames <= 0) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (pcmRate != rate || now - lastPcmMs > 1500) {
            pcmQueue.clear()
            pcmRate = rate
            pcmIndex = 0
            pcmEpoch++
            pcmStartMs = System.currentTimeMillis() - frames * 1000.0 / rate
        }
        lastPcmMs = now
        val bytes = ByteArray(frames * 2)
        for (frame in 0 until frames) {
            val input = frame * channels
            val sample = if (channels == 1) samples[input].toInt()
                else (samples[input].toInt() + samples[input + 1].toInt()) / 2
            bytes[frame * 2] = sample.toByte()
            bytes[frame * 2 + 1] = (sample shr 8).toByte()
        }
        if (pcmQueue.size == 32) pcmQueue.removeFirst()
        pcmQueue.addLast(PcmBlock(rate, pcmStartMs, pcmIndex, pcmEpoch, bytes))
        pcmIndex += frames
    }

    private fun send(target: Messenger, samples: ShortArray, rate: Int): Block {
        val block = Block(sequence.incrementAndGet(), target, samples.size, rate)
        pending[block.id] = block
        val request = Message.obtain(null, if (rate == 0) PROCESS_PCM else ANALYZE_PCM, block.id, 0).apply {
            replyTo = reply
            data = Bundle().apply {
                putShortArray("pcm", samples)
                putDouble("gain", activeGain)
            }
        }
        try {
            target.send(request)
            handler.postDelayed({
                if (pending.remove(block.id, block)) reportFailure(block, "Script process did not respond")
            }, if (rate == 0) 200 else 400)
        } catch (_: android.os.RemoteException) {
            if (pending.remove(block.id, block)) reportFailure(block, "Script process disconnected")
        }
        return block
    }

    fun output(block: Block?): ShortArray? {
        if (activePlugin?.output != PluginPort.PCM) return null
        val target = endpoint ?: return null
        // Spend at most a small part of the playback buffer waiting on the worker.
        val output = if (block?.endpoint === target && block.ready.await(12, TimeUnit.MILLISECONDS)) block.output else null
        if (target !== endpoint) return null
        if (output == null) fallbackBlocks.incrementAndGet() else processedBlocks.incrementAndGet()
        return output
    }
}
