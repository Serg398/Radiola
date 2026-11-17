package com.example.sdr.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sdr.audio.HamNoiseSettings
import com.example.sdr.audio.NoiseReductionLevel
import com.example.sdr.ui.theme.RadiolaColors

@Composable
internal fun NoiseReductionControl(available: Boolean, cw: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val config = HamNoiseSettings.config
    val selected = HamNoiseSettings.level
    val active = available && config.enabled
    val menuAnchor = rememberCompactMenuAnchor()
    Box(menuAnchor.modifier) {
        Column(Modifier.width(48.dp).height(28.dp).toolbarButtonSurface(menuOpen = expanded)
            .clickable(enabled = available) { expanded = true }
            .semantics { contentDescription = "Noise reduction ${if (active) "on" else "off"}" },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("NR", Modifier.height(10.dp), color = if (active) RadiolaColors.primary else RadiolaColors.secondaryText,
                fontSize = 8.sp, style = ToolbarCaptionStyle)
            Text(if (active) selected.name else "OFF", Modifier.height(13.dp),
                color = if (active) RadiolaColors.primary else RadiolaColors.secondaryText, fontSize = 11.sp, style = ToolbarValueStyle)
        }
        DropdownMenu(offset = ToolbarMenuOffset, expanded = expanded, onDismissRequest = { expanded = false; HamNoiseSettings.save() },
            modifier = Modifier.width(120.dp).heightIn(max = menuAnchor.maxHeight), containerColor = RadiolaColors.surface) {
            Text(if (cw) "NR / CW" else "NR / VOICE", color = RadiolaColors.secondaryText, fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            NoiseReductionLevel.entries.forEach { level ->
                CompactMenuEntry(level.name, selected == level) {
                    HamNoiseSettings.selectLevel(level); expanded = false
                }
            }
        }
    }
}
