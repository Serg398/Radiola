package com.example.sdr.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sdr.receiver.ReceiverBand
import com.example.sdr.ui.theme.RadiolaColors
import java.util.Locale

@Composable
internal fun WebSdrBandControl(
    bands: List<ReceiverBand>, selected: Int, active: Boolean, onSelect: (Int) -> Unit,
) {
    var expanded by remember(bands, active) { mutableStateOf(false) }
    val surface = RadiolaColors.surface
    val warm = RadiolaColors.primary
    val ivory = RadiolaColors.text
    val menuAnchor = rememberCompactMenuAnchor()
    Box(menuAnchor.modifier) {
        Column(
            Modifier.height(28.dp).widthIn(min = 64.dp, max = 120.dp)
                .toolbarButtonSurface()
                .clickable(enabled = active && bands.isNotEmpty()) { expanded = true }
                .padding(horizontal = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("BAND", Modifier.height(10.dp), color = RadiolaColors.secondaryText, fontSize = 8.sp, style = ToolbarCaptionStyle)
            ToolbarValue(bands.getOrNull(selected)?.name?.ifBlank { "—" } ?: "—",
                color = if (active) warm else ivory.copy(alpha = 0.5f))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
            modifier = Modifier.width(218.dp).heightIn(max = menuAnchor.maxHeight), containerColor = surface) {
            bands.forEachIndexed { index, band ->
                CompactMenuEntry(band.name.ifBlank { "Band ${index + 1}" }, index == selected,
                    subtitle = if (band.spanHz > 0) String.format(Locale.US, "%.3f \u2013 %.3f MHz", band.lowHz / 1e6, band.highHz / 1e6)
                        else "Select to load range") {
                    expanded = false; onSelect(index)
                }
            }
        }
    }
}
