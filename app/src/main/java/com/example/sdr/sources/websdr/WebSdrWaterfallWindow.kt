package com.example.sdr.sources.websdr

import com.example.sdr.receiver.ReceiverBand
import kotlin.math.floor
import kotlin.math.log2

/** WebSDR zoom uses 1024 bins per full band, independent of requested row width. */
internal data class WebSdrWaterfallWindow(val width: Int, val zoom: Int, val start: Int) {
    companion object {
        fun forViewport(band: ReceiverBand, centerHz: Long, spanHz: Int,
                        previous: WebSdrWaterfallWindow? = null): WebSdrWaterfallWindow {
            val width = if (band.maxZoom > 0) 2048 else 1024
            val zoom = floor(log2(band.spanHz.toDouble() * width / 1024 / spanHz.coerceAtLeast(1)))
                .toInt().coerceIn(0, band.maxZoom)
            if (previous != null && previous.width == width && previous.zoom == zoom) {
                val capturedSpan = band.spanHz.toDouble() * width / 1024 / (1L shl zoom)
                val capturedLow = band.lowHz + previous.start.toDouble() / (1024L shl band.maxZoom) * band.spanHz
                // Keep real FFT resolution, but use the spare coverage before moving the server window.
                // A small margin requests the next window before its edge enters the viewport.
                val margin = minOf((capturedSpan - spanHz).coerceAtLeast(0.0) / 4, spanHz * .1)
                val viewLow = maxOf(band.lowHz.toDouble(), centerHz - spanHz / 2.0)
                val viewHigh = minOf(band.highHz.toDouble(), centerHz + spanHz / 2.0)
                val safeLow = if (capturedLow <= band.lowHz) capturedLow else capturedLow + margin
                val capturedHigh = capturedLow + capturedSpan
                val safeHigh = if (capturedHigh >= band.highHz) capturedHigh else capturedHigh - margin
                if (viewLow >= safeLow && viewHigh <= safeHigh) return previous
            }
            val window = width.toLong() shl (band.maxZoom - zoom)
            val extent = 1024L shl band.maxZoom
            val start = (((centerHz - band.lowHz).toDouble() / band.spanHz * extent - window / 2)
                .toLong().coerceIn(0L, (extent - window).coerceAtLeast(0)) shr (band.maxZoom - zoom) shl (band.maxZoom - zoom)).toInt()
            return WebSdrWaterfallWindow(width, zoom, start)
        }
    }
}
