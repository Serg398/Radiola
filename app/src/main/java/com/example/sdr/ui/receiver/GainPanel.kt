package com.example.sdr.ui.receiver

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sdr.receiver.ReceiverGain
import com.example.sdr.ui.theme.RadiolaColors
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun GainPanel(gain: ReceiverGain, gainSteps: List<Int>, onChange: (ReceiverGain) -> Unit, modifier: Modifier = Modifier) {
    val currentGain by rememberUpdatedState(gain)
    val currentChange by rememberUpdatedState(onChange)
    val index = gainSteps.indices.minByOrNull { kotlin.math.abs(gainSteps[it] - gain.gainTenthsDb) } ?: 0
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(20.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            AgcToggle("RF AGC", gain.rfAgc, Modifier.weight(0.85f)) { onChange(gain.copy(rfAgc = !gain.rfAgc)) }
            AgcToggle("AUDIO AGC", gain.audioAgc, Modifier.weight(1f)) { onChange(gain.copy(audioAgc = !gain.audioAgc)) }
            AgcToggle("BIAS-T", gain.biasT, Modifier.weight(0.85f)) { onChange(gain.copy(biasT = !gain.biasT)) }
        }
        Row(Modifier.fillMaxWidth().height(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("GAIN", color = RadiolaColors.secondaryText, fontSize = 10.sp)
            Box(Modifier.weight(1f))
            Text(if (gain.rfAgc) "AUTO" else "%.1f dB".format(Locale.US, gain.gainTenthsDb / 10f), color = if (gain.rfAgc) RadiolaColors.secondaryText else RadiolaColors.primary, fontSize = 10.sp)
        }
        Canvas(
            Modifier.fillMaxWidth().height(16.dp)
                .semantics {
                    contentDescription = "RF gain"
                    progressBarRangeInfo = ProgressBarRangeInfo(index.toFloat(), 0f..gainSteps.lastIndex.toFloat(), maxOf(0, gainSteps.size - 2))
                    setProgress { value ->
                        if (!gain.rfAgc && gainSteps.isNotEmpty()) {
                            onChange(gain.copy(gainTenthsDb = gainSteps[value.roundToInt().coerceIn(gainSteps.indices)]))
                            true
                        } else false
                    }
                }
                .pointerInput(gain.rfAgc, gainSteps) {
                    if (!gain.rfAgc && gainSteps.isNotEmpty()) {
                        val inset = 7.dp.toPx()
                        fun update(x: Float) {
                            val fraction = ((x - inset) / (size.width - 2 * inset)).coerceIn(0f, 1f)
                            val selected = (fraction * gainSteps.lastIndex).roundToInt()
                            currentChange(currentGain.copy(gainTenthsDb = gainSteps[selected]))
                        }
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            down.consume()
                            update(down.position.x)
                            do {
                                val event = awaitPointerEvent()
                                val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                                pointer.consume()
                                update(pointer.position.x)
                            } while (pointer.pressed)
                        }
                    }
                }
        ) {
            val inset = 7.dp.toPx()
            val y = size.height / 2
            val x = inset + (size.width - 2 * inset) * index / maxOf(1, gainSteps.lastIndex)
            drawLine(RadiolaColors.border, Offset(inset, y), Offset(size.width - inset, y), 3.dp.toPx())
            drawLine(if (gain.rfAgc) RadiolaColors.secondaryText.copy(alpha = 0.3f) else RadiolaColors.primary, Offset(inset, y), Offset(x, y), 3.dp.toPx())
            drawCircle(RadiolaColors.surface, 6.dp.toPx(), Offset(x, y))
            drawCircle(if (gain.rfAgc) RadiolaColors.secondaryText.copy(alpha = 0.4f) else RadiolaColors.primary, 6.dp.toPx(), Offset(x, y), style = Stroke(1.dp.toPx()))
        }
    }
}

@Composable
internal fun AgcToggle(label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.fillMaxHeight()
            .background(if (enabled) RadiolaColors.primaryTint else Color.Transparent, RoundedCornerShape(4.dp))
            .semantics { contentDescription = "$label ${if (enabled) "on" else "off"}" }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.width(3.dp).height(3.dp).background(if (enabled) RadiolaColors.primary else RadiolaColors.border, RoundedCornerShape(2.dp)))
            Text(label, color = if (enabled) RadiolaColors.primary else RadiolaColors.secondaryText, fontSize = 9.sp, maxLines = 1)
        }
    }
}
