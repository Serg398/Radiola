package com.example.sdr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun SourcePlaybackControl(
    source: String,
    onSourceChange: (String) -> Unit,
    connected: Boolean,
    action: String,
    enabled: Boolean,
    onAction: () -> Unit,
) {
    val shape = RoundedCornerShape(4.dp)
    val warm = Color(0xFFF7BA6D)
    val muted = Color(0xFF968E84)
    var expanded by remember { mutableStateOf(false) }
    Row(
        Modifier.width(168.dp).height(28.dp).border(1.dp, Color(0xFF30302E), shape).clip(shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
        Row(
            Modifier.width(79.dp).fillMaxHeight().background(Color(0xFF151719))
                .semantics { contentDescription = "Choose receiver source, $source selected" }
                .clickable { expanded = !expanded }.padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(5.dp).background(
                if (connected) Color(0xFF49BC74) else muted, RoundedCornerShape(3.dp),
            ))
            Text(source, color = Color(0xFFF4E8D7), fontSize = 10.sp, fontWeight = FontWeight.Medium)
            Text(if (expanded) "▴" else "▾", color = warm, fontSize = 11.sp)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = Color(0xFF1C1B19),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.width(160.dp).border(1.dp, Color(0xFF765333), RoundedCornerShape(6.dp)),
        ) {
            listOf("USB", "KiwiSDR", "WebSDR", "OpenWebRX").forEach { label ->
                val available = label == "USB" || label == "WebSDR"
                Row(Modifier.fillMaxWidth().height(36.dp)
                    .clickable(enabled = available) { onSourceChange(label); expanded = false }
                    .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, color = if (source == label) warm else if (available) Color(0xFFF4E8D7) else muted,
                        fontSize = 13.sp, modifier = Modifier.weight(1f))
                    if (source == label) Text("✓", color = warm, fontSize = 13.sp)
                }
            }
        }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(Color(0xFF30302E)))
        Box(
            Modifier.width(88.dp).fillMaxHeight()
                .background(if (enabled) warm else Color(0xFF151719))
                .semantics { contentDescription = if (action == "ACCESS") "Grant USB access" else action }
                .clickable(enabled = enabled, onClick = onAction),
            contentAlignment = Alignment.Center,
        ) {
            Text(action, color = if (enabled) Color(0xFF151719) else muted, fontSize = 10.sp,
                fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}
