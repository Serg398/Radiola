package com.example.sdr.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sdr.DemodulationMode
import com.example.sdr.decoders.ftx.FtxMode
import com.example.sdr.receiver.ReceiverModeOption
import com.example.sdr.ui.theme.RadiolaColors

@Composable
internal fun ModeControl(
    mode: DemodulationMode, ftxMode: FtxMode?, remote: Boolean, ftxEnabled: Boolean,
    onMode: (DemodulationMode) -> Unit, onFtx: (FtxMode) -> Unit,
    serverModes: List<ReceiverModeOption>? = null,
    serverModeId: String? = null,
    serverModeLabel: String? = null,
    onServerMode: (String) -> Unit = {},
    faxMode: Boolean = false,
    onFax: () -> Unit = {},
) {
    var expanded by remember(remote, serverModes != null) { mutableStateOf(false) }
    val modes = listOf("AM" to DemodulationMode.AM) +
        (if (remote) emptyList() else listOf("WFM" to DemodulationMode.WFM)) +
        listOf("FM" to DemodulationMode.NFM, "USB" to DemodulationMode.USB,
            "LSB" to DemodulationMode.LSB, "CW" to DemodulationMode.CW)
    val selected = if (faxMode) "FAX" else ftxMode?.name ?: if (serverModes != null) serverModeLabel.orEmpty()
        else modes.firstOrNull { it.second == mode }?.first.orEmpty()
    Box {
        Column(Modifier.width(72.dp).height(28.dp)
            .toolbarButtonSurface()
            .clickable { expanded = true }, horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Text("MODE", Modifier.height(10.dp), color = RadiolaColors.secondaryText, fontSize = 8.sp, style = ToolbarCaptionStyle)
            ToolbarValue(selected)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 340.dp), containerColor = RadiolaColors.surface) {
            @Composable
            fun Entry(label: String, active: Boolean, enabled: Boolean = true, action: () -> Unit) {
                Box(Modifier.width(40.dp).height(30.dp)
                    .background(if (active) RadiolaColors.activeMode else Color.Transparent, RoundedCornerShape(4.dp))
                    .selectable(selected = active, enabled = enabled, role = Role.RadioButton) { expanded = false; action() },
                    contentAlignment = Alignment.Center) {
                    val color = if (active) RadiolaColors.onActiveMode else RadiolaColors.text
                    Text(label, color = color.copy(alpha = if (enabled) 1f else .4f), fontSize = 11.sp)
                }
            }
            @Composable
            fun Header(title: String, subtitle: String) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = RadiolaColors.primary, fontSize = 10.sp, letterSpacing = 1.sp)
                    Text(subtitle, color = RadiolaColors.secondaryText, fontSize = 9.sp,
                        modifier = Modifier.padding(start = 12.dp))
                }
            }
            Header("LOCAL", if (remote) "DECODERS / SERVER AUDIO" else "MODULATION")
            if (!remote) {
                Row(Modifier.padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    modes.forEach { (label, value) -> Entry(label, active = !faxMode && ftxMode == null && mode == value) { onMode(value) } }
                }
                Text("DECODERS", color = RadiolaColors.secondaryText, fontSize = 9.sp,
                    modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 3.dp))
            }
            Row(Modifier.padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                FtxMode.entries.forEach { value -> Entry(value.name, active = ftxMode == value, enabled = ftxEnabled) { onFtx(value) } }
                Entry("FAX", active = faxMode, enabled = ftxEnabled, action = onFax)
            }
            if (remote) {
                HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = RadiolaColors.border)
                Header("SERVER", "MODULATION")
            }
            if (remote && serverModes != null) {
                if (serverModes.isEmpty()) Text("Connect to load modes", color = RadiolaColors.secondaryText,
                    fontSize = 11.sp, modifier = Modifier.padding(12.dp))
                listOf(serverModes.filterNot { it.digital }, serverModes.filter { it.digital }).forEachIndexed { index, options ->
                    if (index == 1 && options.isNotEmpty()) Text("DIGITAL", color = RadiolaColors.secondaryText,
                        fontSize = 9.sp, modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 3.dp))
                    options.chunked(5).forEach { row ->
                        Row(Modifier.padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            row.forEach { option ->
                                val active = !faxMode && ftxMode == null && option.id == serverModeId
                                Box(Modifier.width(82.dp).height(36.dp)
                                    .background(if (active) RadiolaColors.activeMode else Color.Transparent, RoundedCornerShape(4.dp))
                                    .selectable(selected = active, role = Role.RadioButton) { expanded = false; onServerMode(option.id) }, contentAlignment = Alignment.Center) {
                                    Text(option.label, color = if (active) RadiolaColors.onActiveMode else RadiolaColors.text,
                                        fontSize = 11.sp, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            } else if (remote) Row(Modifier.padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                modes.forEach { (label, value) -> Entry(label, active = !faxMode && ftxMode == null && mode == value) { onMode(value) } }
            }
        }
    }
}
