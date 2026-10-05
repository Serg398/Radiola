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
    private var centerFrequencyHz = 0L
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
        if (centerHz != centerFrequencyHz) {
            centerFrequencyHz = centerHz
            bitmap.eraseColor(Color.rgb(1, 10, 54))
            history.fill(0)
            newestRow = 0
            lastSpectrum = null
        }
        if (spectrum.isEmpty() || spectrum === lastSpectrum) return
        lastSpectrum = spectrum
        newestRow = (newestRow + plotHeight - 1) % plotHeight
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
        val firstRows = plotHeight - newestRow
        // Integer pixel heights keep old rows rigid while scrolling. Scaling each
        // ring segment separately to a rounded split made its rows wobble every frame.
        val rowHeight = maxOf(1, (height + plotHeight - 1) / plotHeight)
        val splitY = firstRows * rowHeight
        sourceRect.top = newestRow
        sourceRect.bottom = plotHeight
        destinationRect.set(0, 0, width, splitY)
        canvas.drawBitmap(bitmap, sourceRect, destinationRect, paint)
        if (newestRow > 0) {
            sourceRect.top = 0
            sourceRect.bottom = newestRow
            destinationRect.set(0, splitY, width, plotHeight * rowHeight)
            canvas.drawBitmap(bitmap, sourceRect, destinationRect, paint)
        }
        canvas.restoreToCount(checkpoint)
    }
}
