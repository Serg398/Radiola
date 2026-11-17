package com.example.sdr.decoders.cw

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.*

/** CWformer v0.2.0 streaming ONNX adapter. All mutable state belongs to the CW worker. */
internal class CwFormerDecoder(context: Context) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val assets = context.assets
    private val config = JSONObject(assets.open("cwformer/mel_config.json").bufferedReader().use { it.readText() })
    private val rate = config.getInt("sample_rate")
    private val fft = config.getInt("n_fft")
    private val hop = config.getInt("hop_length")
    private val mels = config.getInt("n_mels")
    private val frequencies = fft / 2 + 1
    private val layers = config.getInt("n_layers")
    private val heads = config.getInt("n_heads")
    private val keySize = config.getInt("d_k")
    private val width = config.getInt("d_model")
    private val chunk = FloatArray(rate / 2)
    private var chunkCount = 0
    private val window = table("mel_window.f32", fft)
    private val basis = table("mel_basis.f32", mels * frequencies)
    // Only bins used by the exported filterbank need a DFT (including tiny boundary weights).
    private val activeBins = (0 until frequencies).filter { b -> (0 until mels).any { basis[it * frequencies + b] != 0f } }
    private val cosines = activeBins.map { b -> DoubleArray(fft) { cos(2 * PI * b * it / fft) } }
    private val sines = activeBins.map { b -> DoubleArray(fft) { sin(2 * PI * b * it / fft) } }
    private var overlap = FloatArray(fft / 2)
    private data class State(val values: FloatArray, val shape: LongArray)
    private val states = linkedMapOf<String, State>()
    private var position = 0L
    private var inputRate = 0
    private var inputChannels = 0
    private var sourceIndex = 0L
    private var nextPosition = 0.0
    private var previous = 0f
    private var previousToken = -1
    private var framesSinceEmission = 0
    private var emitted = false
    private val text = StringBuilder()
    private val vocabulary = listOf("<blank>", " ") + ('A'..'Z').map { it.toString() } +
        ('0'..'9').map { it.toString() } + ".,?/(&=+".map { it.toString() } + listOf("AR", "SK", "BT", "KN", "AS", "CT")
    private val session = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(2)
        options.setInterOpNumThreads(1)
        assets.open("cwformer/model.onnx").use { env.createSession(it.readBytes(), options) }
    }
    var inferenceMs = 0L; private set

    init { resetState() }

    private fun table(name: String, size: Int): FloatArray {
        val bytes = assets.open("cwformer/$name").use { it.readBytes() }
        check(bytes.size == size * 4) { "Invalid CWformer feature table: $name" }
        return FloatArray(size).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
    }

    private fun resetState() {
        states.clear(); position = 0
        for (layer in 0 until layers) {
            states["kv_k_layer$layer"] = State(FloatArray(0), longArrayOf(1, heads.toLong(), 0, keySize.toLong()))
            states["kv_v_layer$layer"] = State(FloatArray(0), longArrayOf(1, heads.toLong(), 0, keySize.toLong()))
            val pad = config.getInt("conv_kernel") - 1
            states["conv_buf_layer$layer"] = State(FloatArray(width * pad), longArrayOf(1, width.toLong(), pad.toLong()))
        }
        states["sub_buf1"] = State(FloatArray(2 * mels), longArrayOf(1, 1, 2, mels.toLong()))
        val channels = config.getInt("subsample_channels")
        val freq = (mels + 1) / 2
        states["sub_buf2"] = State(FloatArray(channels * 2 * freq), longArrayOf(1, channels.toLong(), 2, freq.toLong()))
    }

    fun process(samples: ShortArray, sampleRate: Int, channels: Int) {
        require(sampleRate in 4000..48000 && channels in 1..2 && samples.size % channels == 0)
        if (inputRate != 0 && (inputRate != sampleRate || inputChannels != channels)) reset()
        inputRate = sampleRate; inputChannels = channels
        for (i in 0 until samples.size / channels) {
            val value = if (channels == 2) (samples[i * 2].toInt() + samples[i * 2 + 1]) / 65536f else samples[i] / 32768f
            while (nextPosition <= sourceIndex) {
                val fraction = (nextPosition - (sourceIndex - 1)).coerceIn(0.0, 1.0).toFloat()
                chunk[chunkCount++] = previous + (value - previous) * fraction
                nextPosition += sampleRate.toDouble() / rate
                if (chunkCount == chunk.size) {
                    val started = System.nanoTime()
                    infer()
                    inferenceMs = (System.nanoTime() - started) / 1_000_000
                    chunkCount = 0
                }
            }
            previous = value; sourceIndex++
        }
    }

    private fun features(): FloatArray {
        val audio = overlap + chunk
        val frames = (audio.size - fft) / hop + 1
        val features = FloatArray(frames * mels)
        val frame = FloatArray(fft)
        val power = DoubleArray(frequencies)
        for (t in 0 until frames) {
            for (j in 0 until fft) frame[j] = audio[t * hop + j] * window[j]
            for ((index, bin) in activeBins.withIndex()) {
                var real = 0.0; var imaginary = 0.0
                for (j in 0 until fft) { real += frame[j] * cosines[index][j]; imaginary -= frame[j] * sines[index][j] }
                power[bin] = real * real + imaginary * imaginary
            }
            for (m in 0 until mels) {
                var sum = 0.0
                for (b in activeBins) sum += power[b] * basis[m * frequencies + b]
                features[t * mels + m] = ln(sum + 1e-6).toFloat()
            }
        }
        overlap = audio.copyOfRange(frames * hop, audio.size)
        return features
    }

    private fun infer() {
        val features = features()
        val inputs = linkedMapOf<String, OnnxTensor>()
        try {
            inputs["mel_chunk"] = OnnxTensor.createTensor(env, FloatBuffer.wrap(features), longArrayOf(1, (features.size / mels).toLong(), mels.toLong()))
            inputs["pos_offset"] = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(position)), longArrayOf(1))
            for ((name, state) in states) inputs[name] = OnnxTensor.createTensor(env, FloatBuffer.wrap(state.values), state.shape)
            session.run(inputs).use { result ->
                val scores = result[0] as OnnxTensor
                val shape = scores.info.shape
                check(shape.size == 3 && shape[1] == 1L && shape[2] == vocabulary.size.toLong()) { "Unexpected CWformer output" }
                val values = scores.floatBuffer
                for (t in 0 until shape[0].toInt()) {
                    var best = 0; var maximum = Float.NEGATIVE_INFINITY
                    repeat(vocabulary.size) { c -> val value = values.get(); if (value > maximum) { maximum = value; best = c } }
                    framesSinceEmission++
                    if (best != 0 && best != previousToken) {
                        text.append(vocabulary[best]); framesSinceEmission = 0; emitted = true
                    }
                    previousToken = best
                }
                position = (result[1] as OnnxTensor).longBuffer.get(0)
                var output = 2
                for (layer in 0 until layers) for (kind in listOf("k", "v")) {
                    val tensor = result[output++] as OnnxTensor
                    val dims = tensor.info.shape
                    val length = dims[2].toInt()
                    val kept = minOf(length, 250) // Same bounded five-second attention cache as upstream.
                    val data = FloatArray(heads * kept * keySize)
                    val buffer = tensor.floatBuffer
                    for (head in 0 until heads) {
                        buffer.position((head * length + length - kept) * keySize)
                        buffer.get(data, head * kept * keySize, kept * keySize)
                    }
                    states["kv_${kind}_layer$layer"] = State(data, longArrayOf(1, heads.toLong(), kept.toLong(), keySize.toLong()))
                }
                for (name in (0 until layers).map { "conv_buf_layer$it" } + listOf("sub_buf1", "sub_buf2")) {
                    val tensor = result[output++] as OnnxTensor
                    val buffer = tensor.floatBuffer
                    states[name] = State(FloatArray(buffer.remaining()).also { buffer.get(it) }, tensor.info.shape)
                }
            }
        } finally { inputs.values.forEach { it.close() } }
        if (text.length > 512) text.delete(0, text.length - 512)
        // Clear stale attention after five seconds without emitted symbols, retaining transcript and STFT overlap.
        if (emitted && framesSinceEmission >= 250) { resetState(); framesSinceEmission = 0 }
    }

    fun information() = CwInformation(text = text.toString().trimStart())
    fun reset() {
        resetState(); overlap = FloatArray(fft / 2); chunkCount = 0
        inputRate = 0; inputChannels = 0; sourceIndex = 0; nextPosition = 0.0; previous = 0f
        previousToken = -1; framesSinceEmission = 0; emitted = false; text.clear(); inferenceMs = 0
    }
    override fun close() = session.close()
}
