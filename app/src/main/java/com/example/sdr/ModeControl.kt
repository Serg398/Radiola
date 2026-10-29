package com.example.sdr

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun ModeControl(
    mode: DemodulationMode, ftxMode: FtxMode?, remote: Boolean, ftxEnabled: Boolean,
    onMode: (DemodulationMode) -> Unit, onFtx: (FtxMode) -> Unit,
    serverModes: List<OpenWebRxMode>? = null,
    serverModeId: String? = null,
    serverModeLabel: String? = null,
    onServerMode: (String) -> Unit = {},
) {
    var expanded by remember(remote) { mutableStateOf(false) }
    val modes = listOf("AM" to DemodulationMode.AM) +
        (if (remote) emptyList() else listOf("WFM" to DemodulationMode.WFM)) +
        listOf("FM" to DemodulationMode.NFM, "USB" to DemodulationMode.USB,
            "LSB" to DemodulationMode.LSB, "CW" to DemodulationMode.CW)
    val selected = if (serverModes != null) serverModeLabel.orEmpty()
        else ftxMode?.name ?: modes.firstOrNull { it.second == mode }?.first.orEmpty()
    Box {
        Column(Modifier.width(72.dp).height(28.dp)
            .toolbarButtonSurface()
            .clickable { expanded = true }, horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Text("MODE", Modifier.height(10.dp), color = RadiolaColors.secondaryText, fontSize = 8.sp, style = ToolbarCaptionStyle)
            ToolbarValue(selected)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
            containerColor = RadiolaColors.surface) {
            @Composable
            fun Entry(label: String, enabled: Boolean = true, action: () -> Unit) {
                val active = label == selected
                Box(Modifier.width(40.dp).height(30.dp)
                    .background(if (active) RadiolaColors.activeMode else Color.Transparent, RoundedCornerShape(4.dp))
                    .clickable(enabled = enabled) { expanded = false; action() },
                    contentAlignment = Alignment.Center) {
                    val color = if (active) RadiolaColors.onActiveMode else RadiolaColors.text
                    Text(label, color = color.copy(alpha = if (enabled) 1f else .4f), fontSize = 11.sp)
                }
            }
            if (serverModes != null) {
                if (serverModes.isEmpty()) Text("Connect to load modes", color = RadiolaColors.secondaryText,
                    fontSize = 11.sp, modifier = Modifier.padding(12.dp))
                serverModes.chunked(5).forEach { row ->
                    Row(Modifier.padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        row.forEach { option ->
                            val active = option.id == serverModeId
                            Box(Modifier.width(82.dp).height(36.dp)
                                .background(if (active) RadiolaColors.activeMode else Color.Transparent, RoundedCornerShape(4.dp))
                                .clickable { expanded = false; onServerMode(option.id) }, contentAlignment = Alignment.Center) {
                                Text(option.label, color = if (active) RadiolaColors.onActiveMode else RadiolaColors.text,
                                    fontSize = 11.sp, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            } else Row(Modifier.padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically) {
            modes.forEach { (label, value) -> Entry(label) { onMode(value) } }
            FtxMode.entries.forEach { value -> Entry(value.name, ftxEnabled) { onFtx(value) } }
            }
        }
    }
}
