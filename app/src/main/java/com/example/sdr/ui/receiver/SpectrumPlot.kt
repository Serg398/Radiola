package com.example.sdr.ui.receiver

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sdr.SpectrumFrame
import com.example.sdr.telemetry.RenderTimings
import com.example.sdr.ui.theme.RadiolaColors
import java.util.Locale
import kotlin.math.roundToInt

private val emptySpectrum = FloatArray(0)

@Composable
internal fun AmberSpectrum(
    valuesState: State<SpectrumFrame?>,
    spectrumSpanHz: Int,
    receiveBandwidthHz: Int,
    channelOffsetHz: Long,
    passbandOffsetHz: Int,
    visibleCenterHz: Double,
    modifier: Modifier = Modifier,
    relative: Boolean = false,
    displayRange: DisplayDbRange? = null,
) {
    val traceSmoother = remember { SpectrumTraceSmoother() }
    val axisPaint = remember {
        android.graphics.Paint().apply {
            color = RadiolaColors.secondaryText.toArgb()
            isAntiAlias = true
        }
    }
    Canvas(modifier) {
        axisPaint.color = RadiolaColors.secondaryText.toArgb()
        val started = System.nanoTime()
        val left = ReceiverPlotGeometry.frequencyInset.toPx()
        val top = 8.dp.toPx()
        val bottom = size.height
        val width = size.width - left
        val height = bottom - top
        val low = displayRange?.low ?: if (relative) -94f else -120f
        val high = displayRange?.high ?: if (relative) -25f else 0f
        for (step in 0..3) {
            val y = top + height * step / 3f
            drawLine(RadiolaColors.plotGrid, Offset(left, y), Offset(size.width, y), 1f)
        }
        for (step in 0..8) {
            val x = left + width * step / 8f
            drawLine(RadiolaColors.plotGrid, Offset(x, top), Offset(x, bottom), 1f)
        }
        axisPaint.textSize = 11.sp.toPx()
        for (step in 0..3) {
            drawContext.canvas.nativeCanvas.drawText(
                if (relative && displayRange == null) "${100 - (100f * step / 3).roundToInt()}"
                else "${(high - (high - low) * step / 3).roundToInt()}", 2.dp.toPx(), top + height * step / 3f + 3.dp.toPx(),
                axisPaint
            )
        }
        val frame = valuesState.value
        val values = frame?.let { traceSmoother.update(it) } ?: emptySpectrum
        val captureSpan = frame?.spanHz ?: spectrumSpanHz
        val captureCenter = frame?.centerFrequencyHz?.toDouble() ?: visibleCenterHz
        val viewportStart = (0.5 + (visibleCenterHz - captureCenter - spectrumSpanHz * .5) / captureSpan).toFloat()
        val viewportEnd = viewportStart + spectrumSpanHz.toFloat() / captureSpan
        if (values.isNotEmpty()) {
            val trace = Path()
            val fill = Path()
            val startBin = (viewportStart * values.size).toInt().coerceIn(0, values.size)
            val endBin = (viewportEnd * values.size).toInt().coerceIn(startBin, values.size)
            val binCount = endBin - startBin
            val columns = minOf(512, binCount)
            for (index in 0 until columns) {
                val first = startBin + index * binCount / columns
                val last = startBin + (index + 1) * binCount / columns
                var db = values[first]
                for (bin in first + 1 until last) db = maxOf(db, values[bin])
                val fraction = (first.toFloat() / values.size - viewportStart) / (viewportEnd - viewportStart)
                val x = left + width * fraction
                val y = bottom - ((db - low) / (high - low)).coerceIn(0f, 1f) * height
                if (index == 0) {
                    trace.moveTo(x, y)
                    fill.moveTo(x, bottom)
                    fill.lineTo(x, y)
                } else {
                    trace.lineTo(x, y)
                    fill.lineTo(x, y)
                }
            }
            if (columns > 0) {
                val endX = left + width * (endBin.toFloat() / values.size - viewportStart) / (viewportEnd - viewportStart)
                fill.lineTo(endX, bottom)
            }
            fill.close()
            drawPath(fill, Brush.verticalGradient(listOf(RadiolaColors.adaptive(Color(0xB3205BCB), Color(0xD9EDA345)), RadiolaColors.adaptive(Color(0x66205BCB), Color(0x804F351B)), RadiolaColors.adaptive(Color(0x26205BCB), Color(0x332E2115))), startY = top, endY = bottom))
            drawPath(trace, RadiolaColors.primary, style = Stroke(1.dp.toPx()))
        }
        val center = left + width * (0.5f + channelOffsetHz.toFloat() / spectrumSpanHz)
        val channelWidth = width * (receiveBandwidthHz.toFloat() / spectrumSpanHz)
        val passbandCenter = center + width * passbandOffsetHz / spectrumSpanHz
        val channelLeft = passbandCenter - channelWidth / 2f
        val channelRight = passbandCenter + channelWidth / 2f
        clipRect(left = left, top = top, right = size.width, bottom = bottom) {
        drawRect(
            Brush.horizontalGradient(
                listOf(RadiolaColors.adaptive(Color(0x19205BCB), Color(0x19E8B36A)), RadiolaColors.adaptive(Color(0x38205BCB), Color(0x38E8B36A)), RadiolaColors.adaptive(Color(0x19205BCB), Color(0x19E8B36A))),
                startX = channelLeft, endX = channelRight
            ),
            Offset(channelLeft, top), Size(channelWidth, height)
        )
        drawLine(RadiolaColors.adaptive(Color(0x80205BCB), Color(0x80E8B36A)), Offset(channelLeft, top), Offset(channelLeft, bottom), 1.dp.toPx())
        drawLine(RadiolaColors.adaptive(Color(0x80205BCB), Color(0x80E8B36A)), Offset(channelRight, top), Offset(channelRight, bottom), 1.dp.toPx())
        drawLine(RadiolaColors.adaptive(Color(0xC9205BCB), Color(0xC9E8B36A)), Offset(center, top), Offset(center, bottom), 1.dp.toPx())
        }
        RenderTimings.spectrum.record(System.nanoTime() - started)
    }
}

internal fun formatFrequency(hz: Long): String = String.format(Locale.US, "%d.%03d.%03d", hz / 1_000_000, (hz / 1_000) % 1_000, hz % 1_000)
