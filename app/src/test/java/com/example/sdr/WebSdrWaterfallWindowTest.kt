package com.example.sdr

import com.example.sdr.receiver.ReceiverBand
import com.example.sdr.sources.websdr.WebSdrWaterfallDecoder
import com.example.sdr.sources.websdr.WebSdrWaterfallWindow
import org.junit.Assert.*
import org.junit.Test

class WebSdrWaterfallWindowTest {
    private val band = ReceiverBand(0, "40m", 7_100_000, 384_000, 7_100_000, 5)

    @Test fun fullBandRetainsTwiceTheHorizontalSamples() {
        val window = WebSdrWaterfallWindow.forViewport(band, band.centerHz, band.spanHz)
        assertEquals(2048, window.width)
        assertEquals(1, window.zoom)
        assertEquals(0, window.start)
    }

    @Test fun zoomRequestsRealResolutionWithinBandLimits() {
        val span = band.spanHz / 16
        val window = WebSdrWaterfallWindow.forViewport(band, band.centerHz, span)
        assertEquals(5, window.zoom)
        assertEquals(span, band.spanHz * window.width / 1024 / (1 shl window.zoom))
        assertEquals(15360, window.start)
        val edge = WebSdrWaterfallWindow.forViewport(band, band.highHz, span)
        assertEquals((1024 shl band.maxZoom) - window.width, edge.start)
    }

    @Test fun serverWithoutZoomUsesNativeFullBandWidth() {
        assertEquals(WebSdrWaterfallWindow(1024, 0, 0),
            WebSdrWaterfallWindow.forViewport(band.copy(maxZoom = 0), band.centerHz, 100))
    }

    @Test fun tuningInsideCapturedCoverageDoesNotMoveNetworkWindow() {
        val span = 18_000
        val initial = WebSdrWaterfallWindow.forViewport(band, band.centerHz, span)
        for (offset in -1_000L..1_000L step 100) {
            assertEquals(initial, WebSdrWaterfallWindow.forViewport(band, band.centerHz + offset, span, initial))
        }
        val shifted = WebSdrWaterfallWindow.forViewport(band, band.centerHz + 5_000, span, initial)
        assertNotEquals(initial, shifted)
        assertEquals(initial.zoom, shifted.zoom)
        assertEquals(initial.width, shifted.width)
    }

    @Test fun zoomChangesStillRequestMatchingResolution() {
        val initial = WebSdrWaterfallWindow.forViewport(band, band.centerHz, 80_000)
        val zoomed = WebSdrWaterfallWindow.forViewport(band, band.centerHz, 18_000, initial)
        assertTrue(zoomed.zoom > initial.zoom)
        assertEquals(WebSdrWaterfallWindow.forViewport(band, band.centerHz, 18_000), zoomed)
    }

    @Test fun edgeWindowCanBeReusedWithoutArtificialOuterMargin() {
        val initial = WebSdrWaterfallWindow.forViewport(band, band.lowHz, 18_000)
        assertEquals(initial, WebSdrWaterfallWindow.forViewport(band, band.lowHz + 1_000, 18_000, initial))
    }

    @Test fun gradualPanMovesWindowInSmallStepsWithoutLosingCoverageOrResolution() {
        val span = 18_000
        val initial = WebSdrWaterfallWindow.forViewport(band, band.centerHz, span)
        var previous = initial
        var changes = 0
        val unitsPerHz = (1024L shl band.maxZoom).toDouble() / band.spanHz
        for (offset in 100L..20_000L step 100) {
            val next = WebSdrWaterfallWindow.forViewport(band, band.centerHz + offset, span, previous)
            assertEquals(initial.zoom, next.zoom)
            assertEquals(initial.width, next.width)
            if (next != previous) {
                changes++
                val shiftHz = (next.start - previous.start) / unitsPerHz
                assertTrue("Gradual movement must not recenter in large steps: $shiftHz", shiftHz in 0.0..span / 8.0)
            }
            val low = band.lowHz + next.start / unitsPerHz
            val capturedSpan = band.spanHz.toDouble() * next.width / 1024 / (1L shl next.zoom)
            assertTrue(low <= band.centerHz + offset - span / 2)
            assertTrue(low + capturedSpan >= band.centerHz + offset + span / 2)
            previous = next
        }
        assertTrue(changes > 0)
    }

    @Test fun largeJumpDoesNotChaseFrequencyOneSmallStepAtATime() {
        val initial = WebSdrWaterfallWindow.forViewport(band, band.centerHz, 18_000)
        val jumped = WebSdrWaterfallWindow.forViewport(band, band.centerHz + 80_000, 18_000, initial)
        assertEquals(WebSdrWaterfallWindow.forViewport(band, band.centerHz + 80_000, 18_000), jumped)
    }

    @Test fun gradualPanToLowerFrequenciesAlsoUsesSmallSteps() {
        val span = 18_000
        var previous = WebSdrWaterfallWindow.forViewport(band, band.centerHz, span)
        val unitsPerHz = (1024L shl band.maxZoom).toDouble() / band.spanHz
        for (offset in 100L..20_000L step 100) {
            val next = WebSdrWaterfallWindow.forViewport(band, band.centerHz - offset, span, previous)
            if (next != previous) assertTrue((previous.start - next.start) / unitsPerHz in 0.0..span / 8.0)
            val low = band.lowHz + next.start / unitsPerHz
            val capturedSpan = band.spanHz.toDouble() * next.width / 1024 / (1L shl next.zoom)
            assertTrue(low <= band.centerHz - offset - span / 2)
            assertTrue(low + capturedSpan >= band.centerHz - offset + span / 2)
            previous = next
        }
    }

    @Test fun legacyServerStillReturnsNative1024BinRows() {
        val decoder = WebSdrWaterfallDecoder()
        var rows = 0
        repeat(8) { n ->
            val packet = javaClass.getResourceAsStream("/websdr/wide-%02d.bin".format(n))!!.use { it.readBytes() }
            if (decoder.positionKnown || (packet[0].toInt() and 255 == 255 && packet[1].toInt() and 255 in 1..2)) {
                decoder.decode(packet)?.let { assertEquals(1024, it.size); rows++ }
            }
        }
        assertTrue(rows >= 4)
    }
}
