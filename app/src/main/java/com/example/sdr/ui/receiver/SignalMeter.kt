package com.example.sdr.ui.receiver

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sdr.receiver.OnlineReceiverSession
import com.example.sdr.receiver.ReceiverSquelch
import com.example.sdr.ui.components.toolbarButtonSurface
import com.example.sdr.ui.theme.RadiolaColors
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun WebSdrSignalMeter(session: OnlineReceiverSession, squelch: ReceiverSquelch, open: Boolean,
    onSquelchChange: (ReceiverSquelch) -> Unit, modifier: Modifier) {
    val signal = session.signalDbm
    SignalMeter((signal ?: -127f) + 37f, squelch, session.squelchOpen, onSquelchChange, modifier,
        remote = true, signalAvailable = signal != null)
}

@Composable
internal fun SignalMeter(
    dbfs: Float, squelch: ReceiverSquelch, open: Boolean,
    onSquelchChange: (ReceiverSquelch) -> Unit, modifier: Modifier = Modifier,
    remote: Boolean = false, signalAvailable: Boolean = true,
) {
    val currentSquelch by rememberUpdatedState(squelch)
    val displayedSignal = remember { Animatable(dbfs) }
    LaunchedEffect(dbfs) {
        displayedSignal.animateTo(dbfs, tween(
            durationMillis = if (dbfs > displayedSignal.value) 80 else 180,
            easing = LinearOutSlowInEasing
        ))
    }
    val changeSquelch by rememberUpdatedState(onSquelchChange)
    var meterWidth by remember { mutableStateOf(1) }
    val density = LocalDensity.current
    fun setThreshold(x: Float) {
        val left = with(density) { 10.dp.toPx() }
        val right = maxOf(left + 1f, meterWidth - with(density) { 66.dp.toPx() })
        val db = (-90f + 104f * ((x - left) / (right - left)).coerceIn(0f, 1f))
            .coerceIn(-90f, -6f).roundToInt().toFloat()
        changeSquelch(currentSquelch.copy(thresholdDbfs = db))
    }
    // Relative display only: raw dBFS is not calibrated antenna power.
    val rawLevel = (displayedSignal.value + 90f).coerceAtLeast(0f)
    val maximum = 104f
    val level = rawLevel.coerceAtMost(maximum)
    fun position(value: Float): Float = value / maximum
    val readingLevel = level
    val reading = if (!signalAvailable) "S0" else if (readingLevel <= 54f) "S${(readingLevel / 6f).roundToInt()}"
        else "S9 +${(readingLevel - 54f).roundToInt()}"
    val labelPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }
    Row(modifier.background(RadiolaColors.surface, RoundedCornerShape(5.dp)), verticalAlignment = Alignment.CenterVertically) {

        Column(
            Modifier.width(38.dp).height(28.dp)
                .toolbarButtonSurface(squelch.enabled)
                .clickable { onSquelchChange(squelch.copy(enabled = !squelch.enabled)) }
                .semantics { contentDescription = "Squelch ${if (squelch.enabled) "on" else "off"}" },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
        ) {
            Text("SQL", color = if (squelch.enabled) RadiolaColors.primary else RadiolaColors.secondaryText, fontSize = 8.sp, fontWeight = FontWeight.Normal,
                style = TextStyle(lineHeight = 10.sp, platformStyle = PlatformTextStyle(includeFontPadding = false)))
            Text(if (!squelch.enabled) "OFF" else if (open) "OPEN" else "MUTE",
                color = if (squelch.enabled) RadiolaColors.primary else RadiolaColors.secondaryText, fontSize = 11.sp,
                style = TextStyle(lineHeight = 13.sp, platformStyle = PlatformTextStyle(includeFontPadding = false)))
        }
        Spacer(Modifier.width(2.dp))
        Canvas(
            Modifier.weight(1f).fillMaxHeight().onSizeChanged { meterWidth = it.width }
                .pointerInput(remote) { detectTapGestures(onTap = { setThreshold(it.x) }) }
                .pointerInput(remote) {
                    detectDragGestures(
                        onDragStart = { setThreshold(it.x) },
                        onDrag = { change, _ -> change.consume(); setThreshold(change.position.x) }
                    )
                }
                .semantics {
                    run {
                    progressBarRangeInfo = ProgressBarRangeInfo(squelch.thresholdDbfs, -90f..-6f, 83)
                    setProgress { changeSquelch(currentSquelch.copy(thresholdDbfs = it.coerceIn(-90f, -6f))); true }
                    }
                }
                .background(RadiolaColors.surface, RoundedCornerShape(5.dp))
                .semantics { contentDescription = if (!signalAvailable) "Signal unavailable"
                    else if (remote) "WebSDR signal $reading, ${(dbfs - 37f).roundToInt()} dBm"
                    else "Relative signal $reading, ${dbfs.roundToInt()} dBFS" }
        ) {
            val left = 10.dp.toPx()
            val right = maxOf(left + 1f, size.width - 66.dp.toPx())
            val width = right - left
            val barTop = size.height - 11.dp.toPx()
            val barHeight = 5.dp.toPx()
            val labelY = 13.dp.toPx()
            val ticks = listOf(0f to "0", 6f to "1", 18f to "3", 30f to "5", 42f to "7",
                54f to "9", 64f to "+10", 84f to "+30", 104f to "+50")
            labelPaint.textAlign = android.graphics.Paint.Align.CENTER
            labelPaint.textSize = 9.sp.toPx()
            for ((value, label) in ticks) {
                val x = left + width * position(value)
                labelPaint.color = if (value > 54f) RadiolaColors.warning.toArgb()
                    else RadiolaColors.secondaryText.toArgb()
                drawContext.canvas.nativeCanvas.drawText(label, x, labelY, labelPaint)
                drawLine(RadiolaColors.border, Offset(x, barTop - 3.dp.toPx()), Offset(x, barTop - 1.dp.toPx()), .75.dp.toPx())
            }
            val segments = 42
            val gap = 1.dp.toPx()
            val segmentWidth = maxOf(1f, (width - gap * (segments - 1)) / segments)
            for (index in 0 until segments) {
                val fraction = (index + 1f) / segments
                drawRoundRect(
                    if (!signalAvailable || position(level) < fraction) RadiolaColors.border.copy(alpha = .55f)
                    else if (fraction > position(54f)) RadiolaColors.warning else RadiolaColors.success,
                    Offset(left + index * (segmentWidth + gap), barTop), Size(segmentWidth, barHeight),
                    cornerRadius = CornerRadius(.6.dp.toPx())
                )
            }
            val cursorX = left + width * position(if (signalAvailable) level else 0f)
            drawRoundRect(RadiolaColors.text, Offset(cursorX - .75.dp.toPx(), barTop - 1.dp.toPx()),
                Size(1.5.dp.toPx(), barHeight + 2.dp.toPx()), CornerRadius(.75.dp.toPx()))
            val sqlX = left + width * position(squelch.thresholdDbfs + 90f)
            val sqlColor = if (!squelch.enabled) RadiolaColors.secondaryText.copy(alpha = 0.6f) else if (open) RadiolaColors.primary else RadiolaColors.text
            run {
            drawLine(sqlColor, Offset(sqlX, barTop - 3.dp.toPx()), Offset(sqlX, size.height), 1.5.dp.toPx())
            val triangle = Path().apply {
                moveTo(sqlX, barTop + barHeight + 1.dp.toPx())
                lineTo(sqlX - 3.dp.toPx(), size.height)
                lineTo(sqlX + 3.dp.toPx(), size.height)
                close()
            }
            drawPath(triangle, sqlColor)
            }
            val readoutX = right + 16.dp.toPx()
            labelPaint.textAlign = android.graphics.Paint.Align.LEFT
            labelPaint.color = RadiolaColors.primary.toArgb()
            labelPaint.textSize = 12.sp.toPx()
            labelPaint.isFakeBoldText = true
            drawContext.canvas.nativeCanvas.drawText(reading, readoutX, 12.dp.toPx(), labelPaint)
            labelPaint.isFakeBoldText = false
            labelPaint.color = RadiolaColors.secondaryText.toArgb()
            labelPaint.textSize = 9.sp.toPx()
            val detail = "SQL %.0f dB".format(Locale.US, squelch.thresholdDbfs)
            drawContext.canvas.nativeCanvas.drawText(detail, readoutX, size.height - 5.dp.toPx(), labelPaint)
        }

    }
}
