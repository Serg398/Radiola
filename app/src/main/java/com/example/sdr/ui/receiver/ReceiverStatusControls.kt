package com.example.sdr.ui.receiver

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sdr.ui.components.toolbarButtonSurface
import com.example.sdr.ui.theme.RadiolaColors

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
        Canvas(Modifier.width(18.dp).height(10.dp)) {
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
        Text(if (percent < 0) "—%" else "$percent%", color = tint, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
internal fun ServerViewToggle(page: String, active: Boolean, onSelect: (String) -> Unit) {
    Row(Modifier.height(28.dp).toolbarButtonSurface().padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf("RADIO" to "Radio", "MAP" to "MAP").forEach { (label, targetPage) ->
            val chosen = active && page == targetPage
            Box(Modifier.width(40.dp).fillMaxHeight()
                .background(if (chosen) RadiolaColors.primaryTint else Color.Transparent, RoundedCornerShape(3.dp))
                .semantics { contentDescription = if (targetPage == "Radio") "Show radio" else "Show server map" }
                .clickable { onSelect(targetPage) }, contentAlignment = Alignment.Center) {
                Text(label, color = if (chosen) RadiolaColors.primary else RadiolaColors.secondaryText, fontSize = 11.sp,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
            }
        }
    }
}
