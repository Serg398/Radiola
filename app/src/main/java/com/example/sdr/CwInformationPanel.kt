package com.example.sdr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun CwInformationPanel(info: CwInformation, clear: () -> Unit,
    decodeEnabled: Boolean, onDecodeChange: (Boolean) -> Unit) {
    val warm = RadiolaColors.primary
    val muted = RadiolaColors.secondaryText
    val scroll = rememberScrollState()
    LaunchedEffect(info.text, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    Row(Modifier.fillMaxSize().border(1.dp, RadiolaColors.border, RoundedCornerShape(6.dp))
        .background(RadiolaColors.surface, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.width(80.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("CW", color = warm, fontSize = 18.sp)
            Text(if (info.wpm > 0) "${info.wpm} WPM" else "AUTO WPM", color = muted, fontSize = 11.sp)
            Text(if (info.toneHz > 0) "${info.toneHz} Hz" else "AUTO TONE",
                color = if (info.tonePresent) warm else muted, fontSize = 10.sp)
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(RadiolaColors.border))
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("MORSE", color = muted, fontSize = 10.sp, letterSpacing = 1.sp)
                Spacer(Modifier.weight(1f))
                Text("DECODE", color = if (decodeEnabled) warm else muted, fontSize = 10.sp,
                    modifier = Modifier.toggleable(value = decodeEnabled, role = Role.Switch, onValueChange = onDecodeChange)
                        .background(if (decodeEnabled) RadiolaColors.primaryTint else Color.Transparent, RoundedCornerShape(4.dp))
                        .border(1.dp, if (decodeEnabled) RadiolaColors.primary else RadiolaColors.border, RoundedCornerShape(4.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp))
                Spacer(Modifier.width(8.dp))
                Text("CLEAR", color = muted, fontSize = 10.sp,
                    modifier = Modifier.height(28.dp).clickable(onClick = clear).padding(horizontal = 8.dp).wrapContentHeight(Alignment.CenterVertically))
            }
            Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)) {
                Text(info.text.trimStart(), color = RadiolaColors.text, fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace)
            }
            Text(info.pending, modifier = Modifier.height(14.dp), color = warm, fontSize = 11.sp,
                fontFamily = FontFamily.Monospace, maxLines = 1)
        }
    }
}
