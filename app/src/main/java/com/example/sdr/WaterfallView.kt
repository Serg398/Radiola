package com.example.sdr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.View

/** Stores full FFT rows in a circular bitmap; zoom crops history without losing bins. */
class WaterfallView(context: Context) : View(context) {
    private val plotWidth = 8192
    private val renderWidth = 1024
    private val plotHeight = 160
    private val history = ByteArray(plotWidth * plotHeight)
    private val pixels = IntArray(renderWidth)
    private var firstBin = 0
    private var endBin = plotWidth
    private val bitmap = Bitmap.createBitmap(renderWidth, plotHeight, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.rgb(1, 10, 54))
    }
    private val paint = Paint().apply { isFilterBitmap = false }
    private var newestRow = 0
    private var lastSpectrum: FloatArray? = null
    private val rowCentersHz = LongArray(plotHeight)
    private var tuningFrequencyHz = 0L
    private var visibleSpanHz = 2_400_000

    fun setTuning(frequencyHz: Long, spanHz: Int) {
        if (tuningFrequencyHz == frequencyHz && visibleSpanHz == spanHz) return
        tuningFrequencyHz = frequencyHz
        visibleSpanHz = spanHz.coerceAtLeast(1)
        invalidate()
    }
    private val sourceRect = Rect(0, 0, renderWidth, plotHeight)
    private val destinationRect = Rect()

    fun setViewport(start: Float, end: Float) {
        val left = (start * plotWidth).toInt().coerceIn(0, plotWidth - 1)
        val right = (end * plotWidth).toInt().coerceIn(left + 1, plotWidth)
        if (firstBin == left && endBin == right) return
        firstBin = left
        endBin = right
        for (row in 0 until plotHeight) renderRow(row)
        invalidate()
    }

    private fun renderRow(row: Int) {
        val offset = row * plotWidth
        val count = endBin - firstBin
        for (x in 0 until renderWidth) {
            val first = firstBin + x * count / renderWidth
            val last = maxOf(first + 1, firstBin + (x + 1) * count / renderWidth)
            var level = 0
            for (bin in first until last) level = maxOf(level, history[offset + bin].toInt() and 255)
            pixels[x] = palette[level]
        }
        bitmap.setPixels(pixels, 0, renderWidth, 0, row, renderWidth, 1)
    }
    private val palette = IntArray(256) { index ->
        val t = index / 255f
        when {
            t < 0.35f -> Color.rgb(1, (10 + t * 105).toInt(), (54 + t * 530).toInt().coerceAtMost(255))
            t < 0.67f -> Color.rgb(((t - 0.35f) * 210).toInt(), (47 + (t - 0.35f) * 550).toInt().coerceAtMost(255), 255)
            else -> Color.rgb((67 + (t - 0.67f) * 570).toInt().coerceAtMost(255), 255, (255 - (t - 0.67f) * 730).toInt().coerceAtLeast(0))
        }
    }

    fun updateSpectrum(spectrum: FloatArray, centerHz: Long) {
        if (spectrum.isEmpty() || spectrum === lastSpectrum) return
        lastSpectrum = spectrum
        newestRow = (newestRow + plotHeight - 1) % plotHeight
        rowCentersHz[newestRow] = centerHz
        for (x in 0 until plotWidth) {
            val first = x * spectrum.size / plotWidth
            val last = (x + 1) * spectrum.size / plotWidth
            var db = spectrum[first]
            for (bin in first + 1 until last) db = maxOf(db, spectrum[bin])
            // The 8x larger FFT lowers the noise in each bin by about 9 dB.
            val level = (((db + 94f) / 69f) * 255f).toInt().coerceIn(0, 255)
            history[newestRow * plotWidth + x] = level.toByte()
        }
        renderRow(newestRow)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val checkpoint = canvas.save()
        canvas.clipRect(0, 0, width, height)
        canvas.drawColor(palette[0])
        // Store every row at its own RF center; move history by absolute frequency.
        // Tuning only translates cached rows, without resampling all FFT history.
        val rowHeight = maxOf(1, (height + plotHeight - 1) / plotHeight)
        var screenRow = 0
        while (screenRow < plotHeight && screenRow * rowHeight < height) {
            val row = (newestRow + screenRow) % plotHeight
            val center = rowCentersHz[row]
            // At a fixed frequency this remains just two bitmap draws for the ring.
            var rows = 1
            while (screenRow + rows < plotHeight && row + rows < plotHeight
                && rowCentersHz[row + rows] == center) rows++
            val shift = ((center - tuningFrequencyHz).toDouble() * width / visibleSpanHz).toInt()
            if (center != 0L && shift > -width && shift < width) {
                sourceRect.set(0, row, renderWidth, row + rows)
                destinationRect.set(shift, screenRow * rowHeight, shift + width, (screenRow + rows) * rowHeight)
                canvas.drawBitmap(bitmap, sourceRect, destinationRect, paint)
            }
            screenRow += rows
        }
        canvas.restoreToCount(checkpoint)
    }
}
