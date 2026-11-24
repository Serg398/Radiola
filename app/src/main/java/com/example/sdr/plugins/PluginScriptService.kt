package com.example.sdr.plugins

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Function
import org.mozilla.javascript.ScriptableObject

internal const val PROCESS_PCM = 1

/** Runs bundled JS without exposing Java objects to the script. */
class PluginScriptService : Service() {
    private val worker = HandlerThread("PluginScript")
    private val engine = object : ContextFactory() {
        private var deadline = 0L

        override fun makeContext(): Context = super.makeContext().apply {
            optimizationLevel = -1
            languageVersion = Context.VERSION_ES6
            instructionObserverThreshold = 1000
            setClassShutter { false }
        }

        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            check(System.nanoTime() < deadline) { "Plugin execution timed out" }
        }

        fun setDeadline() { deadline = System.nanoTime() + 40_000_000L }
    }
    private lateinit var messenger: Messenger
    private var program: Pair<ScriptableObject, Function>? = null

    override fun onCreate() {
        super.onCreate()
        worker.start()
        messenger = Messenger(Handler(worker.looper) { message ->
            if (message.what != PROCESS_PCM) return@Handler false
            val input = message.data.getShortArray("pcm")
            val output = if (input != null && input.size in 1..8192) {
                runCatching { process(input, message.data.getDouble("gain", 1.0)) }.getOrNull()
            } else null
            val reply = Message.obtain(null, PROCESS_PCM, message.arg1, 0)
            reply.data = Bundle().apply { if (output != null) putShortArray("pcm", output) }
            runCatching { message.replyTo?.send(reply) }
            true
        })
    }

    private fun process(input: ShortArray, gain: Double): ShortArray = engine.call { cx ->
        engine.setDeadline()
        val (scope, function) = program ?: run {
            val safeScope = cx.initSafeStandardObjects()
            val script = assets.open("plugins/gain.js").bufferedReader().use { it.readText() }
            cx.evaluateString(safeScope, script, "gain.js", 1, null)
            (safeScope to ScriptableObject.getProperty(safeScope, "processPcm") as Function).also { program = it }
        }
        val samples = cx.newObject(scope, "Int16Array", arrayOf(input.size))
        for (i in input.indices) samples.put(i, samples, input[i].toInt())
        function.call(cx, scope, scope, arrayOf(samples, gain))
        ShortArray(input.size) { (samples.get(it, samples) as Number).toShort() }
    }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onDestroy() {
        worker.quitSafely()
        super.onDestroy()
    }
}
