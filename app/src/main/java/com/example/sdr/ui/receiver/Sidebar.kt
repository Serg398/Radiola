package com.example.sdr.ui.receiver

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sdr.R
import com.example.sdr.ui.theme.RadiolaColors
import java.util.Locale

@Composable
internal fun Sidebar(selected: String, onSelect: (String) -> Unit,
    screenAlwaysOn: Boolean, onScreenAlwaysOnChange: (Boolean) -> Unit) {
    var collapsed by rememberSaveable { mutableStateOf(false) }
    val sidebarWidth by animateDpAsState(if (collapsed) 40.dp else 116.dp,
        animationSpec = tween(220), label = "Sidebar width")
    Column(Modifier.width(sidebarWidth).fillMaxHeight().clipToBounds().background(RadiolaColors.sidebar)
        .pointerInput(Unit) {
            val threshold = 24.dp.toPx()
            var distance = 0f
            detectHorizontalDragGestures(
                onDragStart = { distance = 0f },
                onHorizontalDrag = { change, amount ->
                    change.consume()
                    distance += amount
                },
                onDragEnd = {
                    if (distance <= -threshold) collapsed = true
                    else if (distance >= threshold) collapsed = false
                    distance = 0f
                },
                onDragCancel = { distance = 0f },
            )
        }) {
        val labelAlpha = ((sidebarWidth.value - 40f) / 76f).coerceIn(0f, 1f)
        Box(Modifier.wrapContentWidth(Alignment.Start, unbounded = true).requiredWidth(116.dp).height(46.dp).padding(start = 10.dp),
            contentAlignment = Alignment.CenterStart) {
            Text(stringResource(R.string.app_name).uppercase(Locale.ROOT),
                modifier = Modifier.alpha(labelAlpha), color = RadiolaColors.primary, fontSize = 16.sp,
                fontWeight = FontWeight.Light, letterSpacing = 0.sp, maxLines = 1)
        }
        listOf("Receiver", "Favorites", "Settings").forEach { name ->
            val active = selected == name
            Box(
                Modifier.fillMaxWidth().height(36.dp)
                    .background(if (active) RadiolaColors.primary.copy(alpha = .16f) else Color.Transparent)
                    .semantics { contentDescription = name }
                    .clickable(role = Role.Tab) { onSelect(name) },
            ) {
                if (active) Box(Modifier.align(Alignment.CenterStart).width(3.dp).height(22.dp)
                    .background(RadiolaColors.primary, RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp)))
                Row(Modifier.wrapContentWidth(Alignment.Start, unbounded = true).requiredWidth(116.dp).fillMaxHeight().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    SidebarIcon(name, if (active) RadiolaColors.primary else RadiolaColors.secondaryText)
                    Text(name, Modifier.padding(start = 8.dp).alpha(labelAlpha), color = if (active) RadiolaColors.primary else RadiolaColors.text,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 12.sp, maxLines = 1)
                }
            }
        }
        Box(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth().padding(bottom = 8.dp), contentAlignment = Alignment.CenterStart) {
            Box(Modifier.width(40.dp).height(48.dp)
                .semantics { contentDescription = "Keep screen on" }
                .toggleable(value = screenAlwaysOn, role = Role.Switch,
                    onValueChange = onScreenAlwaysOnChange), contentAlignment = Alignment.Center) {
                SidebarIcon(if (screenAlwaysOn) "ScreenLocked" else "ScreenAuto",
                    if (screenAlwaysOn) RadiolaColors.primary else RadiolaColors.secondaryText)
            }
        }
    }
}

@Composable
internal fun SidebarIcon(name: String, tint: Color) {
    Box(Modifier.width(24.dp).height(24.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.width(20.dp).height(20.dp)) {
            val unit = size.width / 24f
            val stroke = 1.7f * unit
            fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
            if (name == "Receiver") {
                val wave = Path().apply {
                    moveTo(2f * unit, 12f * unit)
                    listOf(6f to 12f, 8.5f to 5f, 12f to 19f, 15.5f to 8f,
                        18f to 12f, 22f to 12f).forEach { (x, y) -> lineTo(x * unit, y * unit) }
                }
                drawPath(wave, tint, style = Stroke(stroke, cap = StrokeCap.Round,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round))
            } else if (name == "Favorites") {
                val star = Path().apply {
                    repeat(10) { i ->
                        val a = i * Math.PI / 5 - Math.PI / 2
                        val r = if (i % 2 == 0) 10f else 4.5f
                        val x = (12 + kotlin.math.cos(a).toFloat() * r) * unit
                        val y = (12 + kotlin.math.sin(a).toFloat() * r) * unit
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                    }
                    close()
                }
                drawPath(star, tint, style = Stroke(stroke, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            } else if (name == "ScreenLocked" || name == "ScreenAuto") {
                drawCircle(tint, 6f * unit, point(12f, 12f), style = Stroke(1.4f * unit))
                repeat(8) { index ->
                    val angle = index * Math.PI / 4
                    fun ray(radius: Float) = point(12f + kotlin.math.cos(angle).toFloat() * radius,
                        12f + kotlin.math.sin(angle).toFloat() * radius)
                    drawLine(tint, ray(8.5f), ray(11f), strokeWidth = 1.4f * unit, cap = StrokeCap.Round)
                }
                val symbolStroke = 1.1f * unit
                if (name == "ScreenAuto") {
                    val letter = Path().apply {
                        moveTo(9f * unit, 15.5f * unit)
                        lineTo(12f * unit, 8.5f * unit)
                        lineTo(15f * unit, 15.5f * unit)
                        moveTo(10.1f * unit, 13f * unit)
                        lineTo(13.9f * unit, 13f * unit)
                    }
                    drawPath(letter, tint, style = Stroke(symbolStroke, cap = StrokeCap.Round,
                        join = androidx.compose.ui.graphics.StrokeJoin.Round))
                } else {
                    val shackle = Path().apply {
                        moveTo(10f * unit, 11f * unit)
                        lineTo(10f * unit, 9.5f * unit)
                        cubicTo(10f * unit, 6.5f * unit, 14f * unit, 6.5f * unit, 14f * unit, 9.5f * unit)
                        lineTo(14f * unit, 11f * unit)
                    }
                    drawPath(shackle, tint, style = Stroke(symbolStroke, cap = StrokeCap.Round))
                    drawRoundRect(tint, topLeft = point(9f, 11f), size = androidx.compose.ui.geometry.Size(6f * unit, 5f * unit),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(.8f * unit), style = Stroke(symbolStroke))
                }
            } else {
                val gear = Path().apply {
                    // Eight rounded teeth, with the same visual bounds as the receiver icon.
                    repeat(32) { index ->
                        val angle = index * Math.PI / 16 - Math.PI / 2
                        val radius = if (index % 4 == 0 || index % 4 == 3) 10f else 8f
                        val x = (12f + kotlin.math.cos(angle).toFloat() * radius) * unit
                        val y = (12f + kotlin.math.sin(angle).toFloat() * radius) * unit
                        if (index == 0) moveTo(x, y) else lineTo(x, y)
                    }
                    close()
                }
                drawPath(gear, tint, style = Stroke(stroke,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round))
                drawCircle(tint, 3.2f * unit, point(12f, 12f), style = Stroke(stroke))
            }
        }
    }
}
