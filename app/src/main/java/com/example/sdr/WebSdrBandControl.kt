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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable
internal fun WebSdrBandControl(
    bands: List<WebSdrTunerBand>, selected: Int, active: Boolean, onSelect: (Int) -> Unit,
) {
    var expanded by remember(bands, active) { mutableStateOf(false) }
    val surface = RadiolaColors.surface
    val warm = RadiolaColors.primary
    val ivory = RadiolaColors.text
    Box {
        Column(
            Modifier.height(28.dp).widthIn(min = 64.dp, max = 120.dp)
                .toolbarButtonSurface()
                .clickable(enabled = active && bands.isNotEmpty()) { expanded = true }
                .padding(horizontal = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("BAND", color = RadiolaColors.secondaryText, fontSize = 8.sp)
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(bands.getOrNull(selected)?.name?.ifBlank { "—" } ?: "—",
                color = if (active) warm else ivory.copy(alpha = 0.5f), fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Text("▾", color = warm, fontSize = 11.sp)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
            modifier = Modifier.width(260.dp), containerColor = surface) {
            bands.forEachIndexed { index, band ->
                Column(Modifier.fillMaxWidth()
                    .background(if (index == selected) warm.copy(alpha = 0.08f) else Color.Transparent)
                    .clickable { expanded = false; onSelect(index) }
                    .padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(band.name.ifBlank { "Band ${index + 1}" },
                        color = if (index == selected) warm else ivory, fontSize = 13.sp)
                    Text(String.format(Locale.US, "%.3f – %.3f MHz", band.lowHz / 1e6, band.highHz / 1e6),
                        color = ivory.copy(alpha = 0.6f), fontSize = 11.sp)
                }
            }
        }
    }
}
