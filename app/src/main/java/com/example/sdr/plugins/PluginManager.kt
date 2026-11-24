package com.example.sdr.plugins

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class PluginManager(private val context: Context) {
    internal class Block(val id: Int, val endpoint: Messenger) {
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
    val processedBlocks = AtomicLong()
    val fallbackBlocks = AtomicLong()
    var gain by mutableStateOf(1.5)
        private set
    @Volatile private var activeGain = 1.5
    @Volatile private var endpoint: Messenger? = null
    private val pending = ConcurrentHashMap<Int, Block>()
    private val sequence = java.util.concurrent.atomic.AtomicInteger()
    private val handler = Handler(Looper.getMainLooper())
    private val replyThread by lazy { HandlerThread("PluginReplies").apply { start() } }
    private val reply by lazy { Messenger(Handler(replyThread.looper) { message ->
        val block = pending.remove(message.arg1)
        if (block != null) {
            block.output = message.data.getShortArray("pcm")
            block.ready.countDown()
            if (block.output == null) reportFailure(block, "Script processing failed")
        }
        true
    }) }
    private var bound = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (bound && playing) {
                endpoint = Messenger(service)
                ready = true
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
        playing = true
        bound = context.bindService(Intent(context, PluginScriptService::class.java), connection, Context.BIND_AUTO_CREATE)
        if (!bound) fail("Cannot start script process")
    }

    fun stop() {
        playing = false
        ready = false
        endpoint = null
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
        handler.post { if (endpoint === block.endpoint) fail(message) }
    }

    /** Accept bursts of network PCM without waiting for the script. */
    @Synchronized
    fun submit(samples: ShortArray): Block? {
        val target = endpoint ?: return null
        if (samples.size !in 1..8192 || pending.size >= 16) return null
        val block = Block(sequence.incrementAndGet(), target)
        pending[block.id] = block
        val request = Message.obtain(null, PROCESS_PCM, block.id, 0).apply {
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
            }, 200)
        } catch (_: android.os.RemoteException) {
            if (pending.remove(block.id, block)) reportFailure(block, "Script process disconnected")
        }
        return block
    }

    fun output(block: Block?): ShortArray? {
        val target = endpoint ?: return null
        // Spend at most a small part of the playback buffer waiting on the worker.
        val output = if (block?.endpoint === target && block.ready.await(12, TimeUnit.MILLISECONDS)) block.output else null
        if (target !== endpoint) return null
        if (output == null) fallbackBlocks.incrementAndGet() else processedBlocks.incrementAndGet()
        return output
    }
}
