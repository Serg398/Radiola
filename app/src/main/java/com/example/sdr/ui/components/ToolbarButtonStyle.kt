package com.example.sdr.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sdr.ui.theme.RadiolaColors

internal val ToolbarCaptionStyle = TextStyle(lineHeight = 10.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false))
internal val ToolbarValueStyle = TextStyle(lineHeight = 13.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false))

@Composable
internal fun ToolbarValue(value: String, color: Color = RadiolaColors.primary, arrow: Boolean = true) {
    Row(Modifier.height(13.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value, Modifier.height(13.dp).weight(1f, fill = false), color = color,
            fontSize = 11.sp, style = ToolbarValueStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (arrow) Canvas(Modifier.width(4.dp).height(3.dp)) {
            drawPath(Path().apply {
                moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2, size.height); close()
            }, color)
        }
    }
}

@Composable
internal fun Modifier.toolbarButtonSurface(active: Boolean = false, outlined: Boolean = true, menuOpen: Boolean = false): Modifier {
    val shape = RoundedCornerShape(4.dp)
    return shadow(if (active || menuOpen) 0.dp else if (outlined) .75.dp else 1.5.dp, shape, clip = false)
        .clip(shape)
        .background(if (active || menuOpen) RadiolaColors.primaryTint
            else RadiolaColors.adaptive(Color(0xFFFCFDFE), Color(0xFF30353B)))
        .then(if (outlined) Modifier.border(.65.dp, if (menuOpen) RadiolaColors.primary.copy(alpha = .8f)
            else RadiolaColors.border.copy(alpha = .65f), shape) else Modifier)
}
