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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
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
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private const val SIGNAL_METER_MAXIMUM = 124f // S9 (54 dB above S0) + 70 dB.

@Composable
internal fun WebSdrSignalMeter(session: OnlineReceiverSession, squelch: ReceiverSquelch, open: Boolean,
    onSquelchChange: (ReceiverSquelch) -> Unit, modifier: Modifier, analog: Boolean = true) {
    val signal = session.signalDbm
    SignalMeter((signal ?: -127f) + 37f, squelch, session.squelchOpen, onSquelchChange, modifier,
        remote = true, signalAvailable = signal != null, analog = analog)
}

@Composable
internal fun SignalMeter(
    dbfs: Float, squelch: ReceiverSquelch, open: Boolean,
    onSquelchChange: (ReceiverSquelch) -> Unit, modifier: Modifier = Modifier,
    remote: Boolean = false, signalAvailable: Boolean = true, analog: Boolean = true,
) {
    val currentSquelch by rememberUpdatedState(squelch)
    val currentSignal by rememberUpdatedState(dbfs)
    val currentSignalAvailable by rememberUpdatedState(signalAvailable)
    var readoutSignal by remember { mutableStateOf(dbfs) }
    // Keep the needle responsive while giving the numeric reading time to settle.
    LaunchedEffect(Unit) {
        while (true) {
            delay(250)
            readoutSignal = if (currentSignalAvailable) {
                readoutSignal + (currentSignal - readoutSignal) * .4f
            } else currentSignal
        }
    }
    val digitalSignal = remember { Animatable(dbfs) }
    LaunchedEffect(dbfs, analog) {
        if (!analog) digitalSignal.animateTo(dbfs, tween(
            durationMillis = if (dbfs > digitalSignal.value) 80 else 180,
            easing = LinearOutSlowInEasing
        ))
    }
    var displayedSignal by remember { mutableFloatStateOf(dbfs.coerceIn(-90f, 34f)) }
    // Critically damped movement retains velocity when readings change, like a moving-coil meter.
    // A quicker attack and slower release follow speech without snapping between samples.
    LaunchedEffect(analog) {
        if (!analog) return@LaunchedEffect
        displayedSignal = currentSignal.coerceIn(-90f, 34f)
        var velocity = 0f
        var previousTime = android.os.SystemClock.elapsedRealtimeNanos()
        while (true) {
            delay(33)
            val now = android.os.SystemClock.elapsedRealtimeNanos()
            val dt = ((now - previousTime) / 1_000_000_000f).coerceIn(0f, .1f)
            previousTime = now
            val target = if (currentSignalAvailable) currentSignal.coerceIn(-90f, 34f) else -90f
            val omega = if (target > displayedSignal) 12f else 7f
            val error = displayedSignal - target
            val momentum = velocity + omega * error
            val decay = exp(-omega * dt)
            displayedSignal = target + (error + momentum * dt) * decay
            velocity = (velocity - omega * momentum * dt) * decay
        }
    }
    val changeSquelch by rememberUpdatedState(onSquelchChange)
    var meterWidth by remember { mutableStateOf(1) }
    val density = LocalDensity.current
    fun setThreshold(x: Float) {
        val left = with(density) { 10.dp.toPx() }
        val right = maxOf(left + 1f, meterWidth - with(density) { 66.dp.toPx() })
        val db = (-90f + SIGNAL_METER_MAXIMUM * ((x - left) / (right - left)).coerceIn(0f, 1f))
            .coerceIn(-90f, -6f).roundToInt().toFloat()
        changeSquelch(currentSquelch.copy(thresholdDbfs = db))
    }
    // Relative display only: raw dBFS is not calibrated antenna power.
    val rawLevel = ((if (analog) displayedSignal else digitalSignal.value) + 90f).coerceAtLeast(0f)
    val maximum = SIGNAL_METER_MAXIMUM
    val level = rawLevel.coerceAtMost(maximum)
    fun position(value: Float): Float = value / maximum
    val readingLevel = (readoutSignal + 90f).coerceIn(0f, maximum)
    val overS9 = (readingLevel - 54f).roundToInt().coerceAtLeast(0)
    val reading = if (!signalAvailable) "S0" else if (readingLevel <= 54f) "S${(readingLevel / 6f).roundToInt()}"
        else if (overS9 == 0) "S9" else "S9 +$overS9"
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
        Spacer(Modifier.width(if (analog) 4.dp else 6.dp))
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
            if (analog) {
            val scaleY = size.height - 10.dp.toPx()
            val glassLeft = left - 8.dp.toPx()
            val glassRight = right + 12.dp.toPx()
            val glassTop = 1.dp.toPx()
            val glassBottom = size.height - 1.dp.toPx()
            val glassSize = Size(glassRight - glassLeft, glassBottom - glassTop)
            val glassCorner = CornerRadius(3.dp.toPx())
            drawRoundRect(Brush.verticalGradient(listOf(
                RadiolaColors.border.copy(alpha = .22f),
                RadiolaColors.plot,
                RadiolaColors.sidebar.copy(alpha = .6f)
            )), Offset(glassLeft, glassTop), glassSize, glassCorner)
            val ticks = listOf(0f to "0", 6f to "1", 18f to "3", 30f to "5", 42f to "7",
                54f to "9", 64f to "+10", 84f to "+30", 104f to "+50", 124f to "+70")
            drawLine(RadiolaColors.secondaryText.copy(alpha = .65f), Offset(left, scaleY),
                Offset(right, scaleY), .65.dp.toPx())
            labelPaint.textAlign = android.graphics.Paint.Align.CENTER
            labelPaint.textSize = 9.sp.toPx()
            for (value in 0..124 step 2) {
                val fraction = position(value.toFloat())
                val x = left + width * fraction
                val y = scaleY
                val major = ticks.any { it.first == value.toFloat() }
                drawLine(if (value > 54) RadiolaColors.warning.copy(alpha = .8f) else RadiolaColors.secondaryText.copy(alpha = .7f),
                    Offset(x, y), Offset(x, y - (if (major) 3f else 1.5f).dp.toPx()), .65.dp.toPx())
            }
            for ((value, label) in ticks) {
                val fraction = position(value)
                labelPaint.color = if (value > 54f) RadiolaColors.warning.toArgb()
                    else RadiolaColors.secondaryText.toArgb()
                drawContext.canvas.nativeCanvas.drawText(label, left + width * fraction,
                    scaleY - 5.dp.toPx(), labelPaint)
            }
            val fraction = position(if (signalAvailable) level else 0f)
            val needleX = left + width * fraction
            val needleTop = glassTop
            val needleBottom = glassBottom - 3.dp.toPx()
            drawLine(Color.Black.copy(alpha = .15f),
                Offset(needleX + 1.dp.toPx(), needleTop + 1.dp.toPx()),
                Offset(needleX + 1.dp.toPx(), needleBottom + 1.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
            val needleColor = RadiolaColors.adaptive(Color(0xFFC62828), Color(0xFFFF5950))
            val needle = Path().apply {
                moveTo(needleX - 1.dp.toPx(), needleTop)
                lineTo(needleX + 1.dp.toPx(), needleTop)
                lineTo(needleX + .65.dp.toPx(), needleBottom - 2.dp.toPx())
                lineTo(needleX, needleBottom)
                lineTo(needleX - .65.dp.toPx(), needleBottom - 2.dp.toPx())
                close()
            }
            drawPath(needle, needleColor)
            val sqlX = left + width * position(squelch.thresholdDbfs + 90f)
            val sqlColor = if (!squelch.enabled) RadiolaColors.secondaryText.copy(alpha = 0.6f) else if (open) RadiolaColors.primary else RadiolaColors.text
            val triangle = Path().apply {
                moveTo(sqlX, size.height - 4.dp.toPx())
                lineTo(sqlX - 2.5.dp.toPx(), size.height - .5.dp.toPx())
                lineTo(sqlX + 2.5.dp.toPx(), size.height - .5.dp.toPx())
                close()
            }
            drawPath(triangle, sqlColor)
            // Curved reflections and edge shading give the cover a convex lens profile.
            val glassOutline = Path().apply {
                addRoundRect(RoundRect(Rect(Offset(glassLeft, glassTop), glassSize), glassCorner))
            }
            clipPath(glassOutline) {
                drawRect(Brush.verticalGradient(
                    0f to Color.White.copy(alpha = if (RadiolaColors.dark) .18f else .48f),
                    .16f to Color.White.copy(alpha = .04f),
                    .5f to Color.Transparent,
                    .83f to Color.Black.copy(alpha = .04f),
                    1f to Color.Black.copy(alpha = if (RadiolaColors.dark) .38f else .18f),
                    startY = glassTop, endY = glassBottom),
                    Offset(glassLeft, glassTop), glassSize)
                val reflection = Path().apply {
                    moveTo(glassLeft, glassTop)
                    lineTo(glassRight, glassTop)
                    lineTo(glassRight, glassTop + glassSize.height * .22f)
                    quadraticBezierTo(glassLeft + glassSize.width * .5f, glassTop + glassSize.height * .68f,
                        glassLeft, glassTop + glassSize.height * .28f)
                    close()
                }
                drawPath(reflection, Brush.verticalGradient(listOf(
                    Color.White.copy(alpha = if (RadiolaColors.dark) .15f else .3f),
                    Color.White.copy(alpha = .02f)), startY = glassTop, endY = glassTop + glassSize.height * .55f))
                drawRect(Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = .17f),
                    .025f to Color.Transparent,
                    .975f to Color.Transparent,
                    1f to Color.Black.copy(alpha = .17f),
                    startX = glassLeft, endX = glassRight), Offset(glassLeft, glassTop), glassSize)
                drawLine(Color.White.copy(alpha = if (RadiolaColors.dark) .2f else .65f),
                    Offset(glassLeft + 4.dp.toPx(), glassTop + 1.dp.toPx()),
                    Offset(glassRight - 4.dp.toPx(), glassTop + 1.dp.toPx()), .7.dp.toPx())
                drawLine(Color.White.copy(alpha = if (RadiolaColors.dark) .1f else .35f),
                    Offset(glassLeft + 6.dp.toPx(), glassBottom - 1.dp.toPx()),
                    Offset(glassRight - 6.dp.toPx(), glassBottom - 1.dp.toPx()), .6.dp.toPx())
            }
            drawRoundRect(RadiolaColors.border.copy(alpha = .9f), Offset(glassLeft, glassTop),
                glassSize, glassCorner, style = Stroke(.75.dp.toPx()))
            } else {
            val barTop = size.height - 11.dp.toPx()
            val barHeight = 5.dp.toPx()
            val labelY = 13.dp.toPx()
            val ticks = listOf(0f to "0", 6f to "1", 18f to "3", 30f to "5", 42f to "7",
                54f to "9", 64f to "+10", 84f to "+30", 104f to "+50", 124f to "+70")
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
            }
            val readoutX = right + 21.dp.toPx()
            labelPaint.textAlign = android.graphics.Paint.Align.LEFT
            labelPaint.color = RadiolaColors.primary.toArgb()
            labelPaint.textSize = 12.sp.toPx()
            labelPaint.isFakeBoldText = true
            if (signalAvailable && overS9 > 10) {
                val prefix = "S9 "
                drawContext.canvas.nativeCanvas.drawText(prefix, readoutX, 12.dp.toPx(), labelPaint)
                val valueX = readoutX + labelPaint.measureText(prefix)
                labelPaint.color = RadiolaColors.adaptive(Color(0xFFC62828), Color(0xFFFF7373)).toArgb()
                drawContext.canvas.nativeCanvas.drawText(reading.removePrefix(prefix), valueX, 12.dp.toPx(), labelPaint)
            } else {
                drawContext.canvas.nativeCanvas.drawText(reading, readoutX, 12.dp.toPx(), labelPaint)
            }
            labelPaint.isFakeBoldText = false
            labelPaint.color = RadiolaColors.secondaryText.toArgb()
            labelPaint.textSize = 9.sp.toPx()
            if (squelch.enabled) {
                val detail = "SQL %.0f dB".format(Locale.US, squelch.thresholdDbfs)
                drawContext.canvas.nativeCanvas.drawText(detail, readoutX, size.height - 5.dp.toPx(), labelPaint)
            }
        }

    }
}
