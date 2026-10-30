package com.example.sdr.decoders.cw

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import java.nio.FloatBuffer
import kotlin.math.*

/** Local ONNX inference. Owned and closed exclusively by the CW worker thread. */
internal class DeepCwDecoder(context: Context) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val meta = JSONObject(context.assets.open("deepcw/model.onnx.json").bufferedReader().use { it.readText() })
    private val rate = meta.getInt("sample_rate")
    private val fft = meta.getInt("fft_length")
    private val hop = meta.getInt("hop_length")
    private val stride = meta.getInt("output_time_stride")
    private val frameSamples = hop * stride
    private val bins = meta.getInt("spectrogram_frequency_bins")
    private val firstBin = ceil(meta.getDouble("spectrogram_min_freq_hz") * fft / rate).toInt()
    private val chars = meta.getJSONArray("chars").let { a -> List(a.length()) { a.getString(it) } }
    private val blank = meta.getInt("blank_index")
    private val space = chars.indexOf(" ")
    private val session = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(2)
        options.setInterOpNumThreads(1)
        env.createSession(context.assets.open("deepcw/model.onnx").use { it.readBytes() }, options)
    }
    private val window = DoubleArray(fft) { .5 - .5 * cos(2 * PI * it / fft) }
    private val cosines = Array(bins) { b -> DoubleArray(fft) { cos(2 * PI * (firstBin + b) * it / fft) } }
    private val sines = Array(bins) { b -> DoubleArray(fft) { sin(2 * PI * (firstBin + b) * it / fft) } }
    private val audio = FloatArray(rate * 12 + rate * 2)
    private var count = 0
    private var baseSample = 0L
    private var nextRun = rate * 2L
    private var inputRate = 0
    private var sourceIndex = 0L
    private var nextPosition = 0.0
    private var previous = 0f
    private var committedFrame = 0L
    private var previousClass = -1
    private var blanks = 0
    private val text = StringBuilder()
    private var preview = ""
    var inferenceMs = 0L; private set

    fun process(samples: ShortArray, sampleRate: Int, channels: Int) {
        require(sampleRate in 4000..48000 && channels in 1..2)
        if (inputRate != 0 && inputRate != sampleRate) reset()
        inputRate = sampleRate
        for (i in 0 until samples.size / channels) {
            val value = if (channels == 2) (samples[i * 2].toInt() + samples[i * 2 + 1]) / 65536f else samples[i] / 32768f
            // Continuous interpolation carries phase across PCM blocks.
            while (nextPosition <= sourceIndex) {
                val fraction = (nextPosition - (sourceIndex - 1)).coerceIn(0.0, 1.0).toFloat()
                append(previous + (value - previous) * fraction)
                nextPosition += sampleRate.toDouble() / rate
            }
            previous = value; sourceIndex++
        }
    }

    private fun append(value: Float) {
        audio[count++] = value
        val end = baseSample + count
        if (end < nextRun) return
        // Keep 12 seconds, aligned to output frames so CTC timestamps stay absolute.
        if (count > rate * 12) {
            val drop = ((count - rate * 12 + frameSamples - 1) / frameSamples) * frameSamples
            audio.copyInto(audio, 0, drop, count); count -= drop; baseSample += drop
        }
        val started = System.nanoTime()
        infer()
        inferenceMs = (System.nanoTime() - started) / 1_000_000
        nextRun = end + rate
    }

    private fun infer() {
        val frames = 1 + count / hop
        val features = FloatArray(frames * bins)
        for (t in 0 until frames) {
            val start = t * hop - fft / 2
            val frame = DoubleArray(fft) { j ->
                val index = start + j
                val reflected = when { index < 0 -> -index; index >= count -> 2 * count - 2 - index; else -> index }
                audio[reflected] * window[j]
            }
            for (b in 0 until bins) {
                var real = 0.0; var imaginary = 0.0
                for (j in 0 until fft) { real += frame[j] * cosines[b][j]; imaginary -= frame[j] * sines[b][j] }
                features[t * bins + b] = hypot(real, imaginary).toFloat()
            }
        }
        val sorted = features.sortedArray()
        val quantile = meta.getDouble("noise_level_quantile")
        val noise = maxOf(sorted[floor(quantile * sorted.lastIndex).toInt()] / sqrt(-2 * ln(1 - quantile)), sorted.last() * .001, .00001)
        for (i in features.indices) features[i] = ln1p(features[i] / noise).toFloat()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(features), longArrayOf(1, 1, frames.toLong(), bins.toLong())).use { input ->
            session.run(mapOf(meta.getString("onnx_input_name") to input)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val scores = (result.get(meta.getString("onnx_output_name")).get().value as Array<Array<FloatArray>>)[0]
                decode(scores)
            }
        }
    }

    private fun decode(scores: Array<FloatArray>) {
        val baseFrame = baseSample / frameSamples
        val freeze = baseFrame + scores.size - ceil(2.0 * rate / frameSamples).toInt()
        var last = previousClass; var blankCount = blanks
        val pending = StringBuilder()
        val first = max(0L, committedFrame - baseFrame).toInt()
        for (i in first until scores.size) {
            val row = scores[i]
            val index = row.indices.maxBy { row[it] }
            val stable = baseFrame + i < freeze
            val target = if (stable) text else pending
            if (index == blank) { last = -1; blankCount++ }
            else {
                if (index != last && index in chars.indices) {
                    if (blankCount >= ceil(134.0 / stride).toInt() && index != space && (text.isNotEmpty() || pending.isNotEmpty()))
                        if ((pending.lastOrNull() ?: text.lastOrNull()) != ' ') target.append(' ')
                    if (index != space || (pending.lastOrNull() ?: text.lastOrNull()) != ' ') target.append(chars[index])
                }
                last = index; blankCount = 0
            }
            if (stable) { previousClass = last; blanks = blankCount; committedFrame = baseFrame + i + 1 }
        }
        if (text.length > 512) text.delete(0, text.length - 512)
        preview = pending.toString()
    }

    fun information() = CwInformation(text = text.toString(), pending = preview)
    fun reset() {
        count = 0; baseSample = 0; nextRun = rate * 2L; inputRate = 0
        sourceIndex = 0; nextPosition = 0.0; previous = 0f
        committedFrame = 0; previousClass = -1; blanks = 0; text.clear(); preview = ""
    }
    override fun close() = session.close()
}
