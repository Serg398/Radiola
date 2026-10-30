package com.example.sdr.sources.websdr

import com.example.sdr.receiver.ReceiverBand
import kotlin.math.floor
import kotlin.math.log2

/** WebSDR zoom uses 1024 bins per full band, independent of requested row width. */
internal data class WebSdrWaterfallWindow(val width: Int, val zoom: Int, val start: Int) {
    companion object {
        fun forViewport(band: ReceiverBand, centerHz: Long, spanHz: Int): WebSdrWaterfallWindow {
            val width = if (band.maxZoom > 0) 2048 else 1024
            val zoom = floor(log2(band.spanHz.toDouble() * width / 1024 / spanHz.coerceAtLeast(1)))
                .toInt().coerceIn(0, band.maxZoom)
            val window = width.toLong() shl (band.maxZoom - zoom)
            val extent = 1024L shl band.maxZoom
            val start = (((centerHz - band.lowHz).toDouble() / band.spanHz * extent - window / 2)
                .toLong().coerceIn(0L, (extent - window).coerceAtLeast(0)) shr (band.maxZoom - zoom) shl (band.maxZoom - zoom)).toInt()
            return WebSdrWaterfallWindow(width, zoom, start)
        }
    }
}
