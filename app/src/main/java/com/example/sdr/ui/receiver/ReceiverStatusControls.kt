package com.example.sdr.ui.receiver

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sdr.ui.components.toolbarButtonSurface
import com.example.sdr.ui.theme.RadiolaColors

@Composable
internal fun BatteryClockIndicator(percent: Int, charging: Boolean) {
    val textColor = RadiolaColors.secondaryText.toArgb()
    Column(Modifier.width(IntrinsicSize.Max).height(28.dp), horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.Center) {
        BatteryIndicator(percent, charging, Modifier.height(14.dp))
        AndroidView(factory = { context ->
            android.widget.TextClock(context).apply {
                format24Hour = "HH:mm"
                format12Hour = "h:mm"
                includeFontPadding = false
                textSize = 12f
                fontFeatureSettings = "tnum"
                gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                setTextColor(textColor)
            }
        }, update = { it.setTextColor(textColor) }, modifier = Modifier.width(36.dp).height(14.dp))
    }
}

@Composable
internal fun BatteryIndicator(percent: Int, charging: Boolean, modifier: Modifier = Modifier) {
    val tint = if (percent in 0..20 && !charging) RadiolaColors.warning else RadiolaColors.secondaryText
    Row(
        modifier.semantics {
            contentDescription = if (percent < 0) "Battery level unavailable" else "Battery $percent percent${if (charging) ", charging" else ""}"
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Canvas(Modifier.width(20.dp).height(11.dp)) {
            val bodyWidth = size.width - 2.dp.toPx()
            drawRoundRect(tint, size = Size(bodyWidth, size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx()), style = Stroke(1.dp.toPx()))
            drawRect(tint, Offset(bodyWidth, size.height * 0.3f), Size(2.dp.toPx(), size.height * 0.4f))
            val inset = 2.dp.toPx()
            drawRect(tint.copy(alpha = 0.6f), Offset(inset, inset), Size(maxOf(0f, bodyWidth - 2 * inset) * percent.coerceIn(0, 100) / 100f, maxOf(0f, size.height - 2 * inset)))
            if (charging) {
                val bolt = Path().apply {
                    moveTo(bodyWidth * 0.6f, 0f)
                    lineTo(bodyWidth * 0.3f, size.height * 0.6f)
                    lineTo(bodyWidth * 0.5f, size.height * 0.6f)
                    lineTo(bodyWidth * 0.4f, size.height)
                    lineTo(bodyWidth * 0.75f, size.height * 0.4f)
                    lineTo(bodyWidth * 0.55f, size.height * 0.4f)
                    close()
                }
                drawPath(bolt, RadiolaColors.primary)
            }
        }
        Text(if (percent < 0) "—%" else "$percent%", color = tint, fontSize = 12.sp, maxLines = 1,
            style = TextStyle(fontFeatureSettings = "tnum", platformStyle = PlatformTextStyle(includeFontPadding = false)))
    }
}

@Composable
internal fun ServerViewToggle(page: String, active: Boolean, onSelect: (String) -> Unit) {
    Row(Modifier.height(28.dp).toolbarButtonSurface(outlined = false).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf("RADIO" to "Radio", "MAP" to "MAP").forEach { (label, targetPage) ->
            val chosen = active && page == targetPage
            Box(Modifier.width(40.dp).fillMaxHeight()
                .background(if (chosen) RadiolaColors.primary else Color.Transparent, RoundedCornerShape(3.dp))
                .semantics { contentDescription = if (targetPage == "Radio") "Show radio" else "Show server map" }
                .clickable { onSelect(targetPage) }, contentAlignment = Alignment.Center) {
                Text(label, color = if (chosen) RadiolaColors.onPrimary else RadiolaColors.secondaryText, fontSize = 11.sp,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
            }
        }
    }
}

@Composable
internal fun BacklightControl(alwaysOn: Boolean, onChange: (Boolean) -> Unit) {
    Box(Modifier.width(28.dp).height(28.dp)
        .semantics { contentDescription = if (alwaysOn) "Screen: keep on" else "Screen: auto" }
        .toggleable(value = alwaysOn, role = Role.Switch, onValueChange = onChange),
        contentAlignment = Alignment.Center) {
        SidebarIcon(if (alwaysOn) "ScreenLocked" else "ScreenAuto",
            if (alwaysOn) RadiolaColors.primary else RadiolaColors.secondaryText)
    }
}

@Composable
internal fun BatteryBacklightStatus(percent: Int, charging: Boolean,
    alwaysOn: Boolean, onBacklightChange: (Boolean) -> Unit) {
    Row(Modifier.height(28.dp).padding(end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        BatteryClockIndicator(percent, charging)
        BacklightControl(alwaysOn, onBacklightChange)
    }
}
