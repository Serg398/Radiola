package com.example.sdr.ui.receiver

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sign

/** Drag the RF image under the fixed tuning cursor; two fingers exclusively zoom. */
@Composable
internal fun Modifier.waterfallNavigation(
    visibleSpanHz: Int,
    zoom: Float,
    maxZoom: Float = 16f,
    onStep: (Long) -> Unit,
    onZoom: (Float) -> Unit,
    onContact: () -> Unit,
): Modifier {
    val currentSpan by rememberUpdatedState(visibleSpanHz)
    val currentZoom by rememberUpdatedState(zoom)
    val currentMaxZoom by rememberUpdatedState(maxZoom)
    val currentStep by rememberUpdatedState(onStep)
    val currentZoomChange by rememberUpdatedState(onZoom)
    val currentContact by rememberUpdatedState(onContact)
    return this.pointerInput(Unit) {
        val plotInset = ReceiverPlotGeometry.frequencyInset.toPx()
        val minimumDistance = 24.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            currentContact()
            val hzPerPixel = currentSpan.toDouble() / (size.width - plotInset).coerceAtLeast(1f)
            var lastPosition = down.position
            var accumulatedX = 0f
            var accumulatedY = 0f
            var dragging = false
            var pinched = false
            var pendingHz = 0.0
            var previousDistance = 0f
            var previousIds: List<PointerId> = emptyList()
            do {
                val event = awaitPointerEvent()
                val fingers = event.changes.filter { it.pressed }
                if (fingers.size >= 2) {
                    pinched = true
                    if (fingers.size == 2) {
                        val ids = fingers.map { it.id }
                        val distance = abs(fingers[0].position.x - fingers[1].position.x)
                        if (ids == previousIds && distance >= minimumDistance && previousDistance >= minimumDistance) {
                            currentZoomChange((currentZoom * distance / previousDistance).coerceIn(1f, currentMaxZoom))
                        }
                        previousDistance = distance
                        previousIds = ids
                    } else {
                        previousDistance = 0f
                        previousIds = emptyList()
                    }
                    fingers.forEach { it.consume() }
                } else if (fingers.size <= 1 && !pinched && down.position.x >= plotInset) {
                    // Include the release position: Android can coalesce the last
                    // movement into UP, especially during a fast tuning swipe.
                    val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                    var deltaX = change.position.x - lastPosition.x
                    accumulatedX += deltaX
                    accumulatedY += change.position.y - lastPosition.y
                    lastPosition = change.position
                    if (!dragging && abs(accumulatedX) > viewConfiguration.touchSlop && abs(accumulatedX) > abs(accumulatedY)) {
                        dragging = true
                        deltaX = accumulatedX - sign(accumulatedX) * viewConfiguration.touchSlop
                    }
                    if (dragging) {
                        // Leftward image movement exposes higher RF frequencies.
                        pendingHz -= deltaX * hzPerPixel
                        val deltaHz = pendingHz.toLong()
                        if (deltaHz != 0L) {
                            pendingHz -= deltaHz
                            currentStep(deltaHz)
                        }
                        change.consume()
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    }
}
