package com.example.sdr.ui.decoders

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sdr.decoders.cw.CwInformation
import com.example.sdr.ui.theme.RadiolaColors

@Composable
internal fun CwInformationPanel(info: CwInformation, clear: () -> Unit,
    decodeEnabled: Boolean, onDecodeChange: (Boolean) -> Unit) {
    val warm = RadiolaColors.primary
    val muted = RadiolaColors.secondaryText
    val headerStyle = TextStyle(lineHeight = 13.sp, platformStyle = PlatformTextStyle(includeFontPadding = false))
    val scroll = rememberScrollState()
    LaunchedEffect(info.text, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    Row(Modifier.fillMaxSize().border(1.dp, RadiolaColors.border, RoundedCornerShape(6.dp))
        .background(RadiolaColors.surface, RoundedCornerShape(6.dp)).padding(start = 10.dp, end = 10.dp, top = 3.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.width(80.dp).padding(top = 5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("CW", color = warm, fontSize = 18.sp)
            Text(if (info.wpm > 0) "${info.wpm} WPM" else "AUTO WPM", color = muted, fontSize = 11.sp)
            Text(if (info.toneHz > 0) "${info.toneHz} Hz" else "AUTO TONE",
                color = if (info.tonePresent) warm else muted, fontSize = 10.sp)
        }
        Box(Modifier.width(1.dp).fillMaxHeight().padding(top = 5.dp, bottom = 4.dp).background(RadiolaColors.border))
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("MORSE", color = muted, fontSize = 11.sp, style = headerStyle, letterSpacing = .5.sp)
                Text(info.pending, modifier = Modifier.weight(1f).padding(horizontal = 10.dp), color = warm,
                    fontSize = 11.sp, lineHeight = 13.sp, letterSpacing = 1.sp,
                    style = headerStyle, fontFamily = FontFamily.Monospace,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("DECODE", color = if (decodeEnabled) warm else muted, fontSize = 11.sp, style = headerStyle,
                    modifier = Modifier.toggleable(value = decodeEnabled, role = Role.Switch, onValueChange = onDecodeChange)
                        .background(if (decodeEnabled) RadiolaColors.primaryTint else Color.Transparent, RoundedCornerShape(4.dp))
                        .border(1.dp, if (decodeEnabled) RadiolaColors.primary else RadiolaColors.border, RoundedCornerShape(4.dp))
                        .padding(horizontal = 10.dp, vertical = 2.dp))
                Spacer(Modifier.width(8.dp))
                Text("CLEAR", color = muted, fontSize = 11.sp, style = headerStyle,
                    modifier = Modifier.height(20.dp).clickable(onClick = clear).padding(horizontal = 8.dp).wrapContentHeight(Alignment.CenterVertically))
            }
            HorizontalDivider(color = RadiolaColors.border.copy(alpha = .6f), thickness = .5.dp)
            Box(Modifier.weight(1f).fillMaxWidth()
                .background(RadiolaColors.primaryTint.copy(alpha = .12f), RoundedCornerShape(4.dp))
                .verticalScroll(scroll).padding(horizontal = 6.dp, vertical = 4.dp)) {
                Text(info.text.trim(), color = RadiolaColors.text, fontSize = 13.sp,
                    lineHeight = 19.sp, letterSpacing = .25.sp,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                    fontFamily = FontFamily.Monospace)
            }

        }
    }
}
