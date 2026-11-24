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
internal const val ANALYZE_PCM = 2
internal const val SPECTRUM_SAMPLES = 512

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

        fun setDeadline(budgetMs: Long) { deadline = System.nanoTime() + budgetMs * 1_000_000L }
    }
    private lateinit var messenger: Messenger
    private val programs = mutableMapOf<Int, Pair<ScriptableObject, Function>>()

    override fun onCreate() {
        super.onCreate()
        worker.start()
        messenger = Messenger(Handler(worker.looper) { message ->
            val operation = message.what
            if (operation != PROCESS_PCM && operation != ANALYZE_PCM) return@Handler false
            val input = message.data.getShortArray("pcm")
            val reply = Message.obtain(null, operation, message.arg1, 0)
            reply.data = runCatching {
                require(input != null && input.size in 1..8192)
                process(operation, input, message.data.getDouble("gain", 1.0))
            }.getOrElse {
                android.util.Log.w("PluginScript", "Audio block processing failed for operation $operation", it)
                Bundle()
            }
            runCatching { message.replyTo?.send(reply) }
            true
        })
    }

    private fun process(operation: Int, input: ShortArray, gain: Double): Bundle = engine.call { cx ->
        engine.setDeadline(if (operation == ANALYZE_PCM) 250 else 40)
        val (scope, function) = programs.getOrPut(operation) {
            val (file, entry) = if (operation == PROCESS_PCM) "gain.js" to "processPcm"
                else "audio-spectrum.js" to "analyzePcm"
            val safeScope = cx.initSafeStandardObjects()
            val script = assets.open("plugins/$file").bufferedReader().use { it.readText() }
            cx.evaluateString(safeScope, script, file, 1, null)
            safeScope to (ScriptableObject.getProperty(safeScope, entry) as Function)
        }
        val samples = cx.newObject(scope, "Int16Array", arrayOf(input.size))
        for (i in input.indices) samples.put(i, samples, input[i].toInt())
        Bundle().apply {
            if (operation == PROCESS_PCM) {
                function.call(cx, scope, scope, arrayOf(samples, gain))
                putShortArray("pcm", ShortArray(input.size) { (samples.get(it, samples) as Number).toShort() })
            } else {
                require(input.size == SPECTRUM_SAMPLES)
                val bins = function.call(cx, scope, scope, arrayOf(samples)) as org.mozilla.javascript.Scriptable
                putFloatArray("spectrum", FloatArray(input.size / 2 + 1) { (bins.get(it, bins) as Number).toFloat() })
            }
        }
    }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onDestroy() {
        worker.quitSafely()
        super.onDestroy()
    }
}
