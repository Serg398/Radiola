package com.example.sdr.ui.receiver

import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import com.example.sdr.receiver.WaterfallHistory
import com.example.sdr.telemetry.RenderTimings

/** Full-band color cache: each historical row is positioned by its own RF coordinates. */
@android.annotation.SuppressLint("ViewConstructor") // Requires the session-owned history; created by AndroidView.
class WaterfallView(
    context: Context,
    private val history: WaterfallHistory,
    private val minimumRenderWidth: Int = 1024,
    private val separateSurface: Boolean = false,
) : FrameLayout(context) {
    private var surfaceReady = false
    private val surface = if (separateSurface) SurfaceView(context) else null
    private val historyListener: () -> Unit = { refresh() }

    init {
        setWillNotDraw(separateSurface)
        surface?.let { child ->
            addView(child, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            child.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    surfaceReady = true
                }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    refresh()
                    renderSurface()
                }
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    surfaceReady = false
                }
            })
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (separateSurface) history.addListener(historyListener)
    }

    override fun onDetachedFromWindow() {
        history.removeListener(historyListener)
        super.onDetachedFromWindow()
    }

    // Main-thread publication keeps the history and bitmap under a single owner.
    private fun renderSurface() {
        val holder = surface?.holder ?: return
        if (!surfaceReady || !holder.surface.isValid || width == 0 || height == 0) return
        val canvas = holder.lockHardwareCanvas()
        try {
            drawWaterfall(canvas)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private var renderWidth = minOf(minimumRenderWidth, history.binCount)
    private var pixels = IntArray(renderWidth)
    private var bitmap = createBitmap(renderWidth, history.capacity, Bitmap.Config.ARGB_8888)
    private val paint = Paint().apply { isFilterBitmap = false }
    private var cachedRevision = -1L
    private var displayRange: DisplayDbRange? = null
    private val rowLow = FloatArray(history.capacity) { -94f }
    private val rowHigh = FloatArray(history.capacity) { -25f }
    private var assignedRevision = 0L
    internal fun setDisplayRange(range: DisplayDbRange?) {
        if ((displayRange == null) != (range == null)) {
            rowLow.fill(range?.low ?: -94f); rowHigh.fill(range?.high ?: -25f)
            cachedRevision = -1
        }
        displayRange = range
    }
    private var tuningFrequencyHz = 0L
    private var visibleSpanHz = 2_400_000
    private val sourceRect = Rect()
    private val destinationRect = RectF()
    private val palette = IntArray(256) { index ->
        val t = index / 255f
        when {
            t < 0.35f -> Color.rgb(1, (10 + t * 105).toInt(), (54 + t * 530).toInt().coerceAtMost(255))
            t < 0.67f -> Color.rgb(((t - 0.35f) * 210).toInt(), (47 + (t - 0.35f) * 550).toInt().coerceAtMost(255), 255)
            else -> Color.rgb((67 + (t - 0.67f) * 570).toInt().coerceAtMost(255), 255, (255 - (t - 0.67f) * 730).toInt().coerceAtLeast(0))
        }
    }
    init { bitmap.eraseColor(palette[0]) }
    fun setTuning(frequencyHz: Long, spanHz: Int) {
        if (tuningFrequencyHz == frequencyHz && visibleSpanHz == spanHz) return
        tuningFrequencyHz = frequencyHz; visibleSpanHz = spanHz.coerceAtLeast(1)
        refresh()
        if (separateSurface) renderSurface() else invalidate()
    }
    fun refresh() {
        val started = System.nanoTime()
        val newRows = minOf(history.size.toLong(), history.revision - assignedRevision).toInt()
        for (age in 0 until newRows) {
            val row = history.rowAtAge(age)
            rowLow[row] = displayRange?.low ?: -94f
            rowHigh[row] = displayRange?.high ?: -25f
        }
        assignedRevision = history.revision
        // Cache sufficient resolution for the zoom while retaining the complete captured band.
        val minimum = minOf(minimumRenderWidth, history.binCount)
        val wanted = (minimum.toLong() * history.latestSpanHz / visibleSpanHz).coerceIn(minimum.toLong(), history.binCount.toLong()).toInt()
        var width = minimum
        while (width < wanted) width *= 2
        width = minOf(width, history.binCount)
        // Avoid bitmap allocation churn when a pinch oscillates around a resolution boundary.
        if (width < renderWidth && visibleSpanHz < history.latestSpanHz && wanted > renderWidth * .48) width = renderWidth
        if (width != renderWidth) {
            renderWidth = width; pixels = IntArray(width)
            bitmap = createBitmap(width, history.capacity, Bitmap.Config.ARGB_8888).apply { eraseColor(palette[0]) }
            cachedRevision = -1
        }
        if (cachedRevision == history.revision) return
        val rows = if (cachedRevision < 0) history.size else minOf(history.size.toLong(), history.revision - cachedRevision).toInt()
        for (age in 0 until rows) renderRow(history.rowAtAge(age))
        cachedRevision = history.revision
        RenderTimings.cache.record(System.nanoTime() - started)
        if (separateSurface) renderSurface() else invalidate()
    }
    private fun renderRow(row: Int) {
        for (x in 0 until renderWidth) {
            val first = x * history.binCount / renderWidth
            val end = maxOf(first + 1, (x + 1) * history.binCount / renderWidth)
            var level = 0
            for (bin in first until end) {
                val value = if (displayRange == null) history.levelAt(row, bin)
                    else (((history.dbAt(row, bin) - rowLow[row]) / (rowHigh[row] - rowLow[row])) * 255f)
                        .toInt().coerceIn(0, 255)
                level = maxOf(level, value)
            }
            pixels[x] = palette[level]
        }
        bitmap.setPixels(pixels, 0, renderWidth, 0, row, renderWidth, 1)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!separateSurface) drawWaterfall(canvas)
    }

    private fun drawWaterfall(canvas: Canvas) {
        val started = System.nanoTime()
        canvas.withClip(0, 0, width, height) {
            canvas.drawColor(palette[0])
            val rowHeight = maxOf(1, (height + history.capacity - 1) / history.capacity)
            var age = 0
            while (age < history.size && age * rowHeight < height) {
                val row = history.rowAtAge(age)
                val center = history.centerAt(row)
                val span = history.spanAt(row)
                var rows = 1
                while (age + rows < history.size && row + rows < history.capacity &&
                    history.centerAt(row + rows) == center && history.spanAt(row + rows) == span) rows++
                val left = (history.imageLeftFraction(row, tuningFrequencyHz.toDouble(), visibleSpanHz) * width).toFloat()
                val imageWidth = span.toFloat() / visibleSpanHz * width
                if (left < width && left + imageWidth > 0) {
                    sourceRect.set(0, row, renderWidth, row + rows)
                    destinationRect.set(left, (age * rowHeight).toFloat(), left + imageWidth, ((age + rows) * rowHeight).toFloat())
                    canvas.drawBitmap(bitmap, sourceRect, destinationRect, paint)
                }
                age += rows
            }
        }
        RenderTimings.waterfall.record(System.nanoTime() - started)
    }
}
