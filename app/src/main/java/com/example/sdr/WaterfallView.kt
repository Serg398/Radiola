package com.example.sdr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.View

/** Keeps a small, reusable bitmap instead of drawing thousands of Compose shapes per frame. */
class WaterfallView(context: Context) : View(context) {
    private val plotWidth = 512
    private val plotHeight = 160
    private val pixels = IntArray(plotWidth * plotHeight) { Color.rgb(1, 10, 54) }
    private val bitmap = Bitmap.createBitmap(plotWidth, plotHeight, Bitmap.Config.ARGB_8888)
    private val paint = Paint().apply { isFilterBitmap = true }
    private var lastSpectrum: FloatArray? = null
    private var centerFrequencyHz = 0L
    private val sourceRect = Rect(0, 0, plotWidth, plotHeight)
    private val destinationRect = Rect()

    fun setViewport(start: Float, end: Float) {
        val left = (start * plotWidth).toInt().coerceIn(0, plotWidth - 1)
        val right = (end * plotWidth).toInt().coerceIn(left + 1, plotWidth)
        if (sourceRect.left == left && sourceRect.right == right) return
        sourceRect.set(left, 0, right, plotHeight)
        invalidate()
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
            pixels.fill(Color.rgb(1, 10, 54))
            lastSpectrum = null
        }
        if (spectrum.isEmpty() || spectrum === lastSpectrum) return
        lastSpectrum = spectrum
        System.arraycopy(pixels, 0, pixels, plotWidth, (plotHeight - 1) * plotWidth)
        for (x in 0 until plotWidth) {
            val first = x * spectrum.size / plotWidth
            val last = (x + 1) * spectrum.size / plotWidth
            var db = spectrum[first]
            for (bin in first + 1 until last) db = maxOf(db, spectrum[bin])
            val level = (((db + 85f) / 60f) * 255f).toInt().coerceIn(0, 255)
            pixels[x] = palette[level]
        }
        bitmap.setPixels(pixels, 0, plotWidth, 0, 0, plotWidth, plotHeight)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        destinationRect.set(0, 0, width, height)
        canvas.drawBitmap(bitmap, sourceRect, destinationRect, paint)
    }
}
