package com.example.sdr.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sdr.receiver.ReceiverSource
import com.example.sdr.ui.theme.RadiolaColors

@Composable
internal fun SourcePlaybackControl(
    source: ReceiverSource,
    onSourceChange: (ReceiverSource) -> Unit,
    connected: Boolean,
    action: String,
    enabled: Boolean,
    onAction: () -> Unit,
) {
    val shape = RoundedCornerShape(4.dp)
    val warm = RadiolaColors.primary
    val muted = RadiolaColors.secondaryText
    var expanded by remember { mutableStateOf(false) }
    Row(
        Modifier.width(145.dp).height(28.dp).border(1.dp, RadiolaColors.border, shape).clip(shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
        Row(
            Modifier.width(72.dp).fillMaxHeight().background(RadiolaColors.surface)
                .semantics { contentDescription = "Choose receiver source, $source selected" }
                .clickable { expanded = !expanded }.padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(5.dp).background(
                if (connected) RadiolaColors.success else muted, RoundedCornerShape(3.dp),
            ))
            Text(source.storedValue, color = RadiolaColors.text, fontSize = 10.sp, fontWeight = FontWeight.Medium)
            Text(if (expanded) "▴" else "▾", color = warm, fontSize = 11.sp)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = RadiolaColors.surface,
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.width(160.dp).border(1.dp, RadiolaColors.border, RoundedCornerShape(6.dp)),
        ) {
            listOf("USB", "KiwiSDR", "WebSDR", "OpenWebRX").forEach { label ->
                val available = label == "USB" || label == "WebSDR" || label == "OpenWebRX"
                Row(Modifier.fillMaxWidth().height(36.dp)
                    .clickable(enabled = available) { onSourceChange(ReceiverSource.fromStored(label)); expanded = false }
                    .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, color = if (source.storedValue == label) warm else if (available) RadiolaColors.text else muted,
                        fontSize = 13.sp, modifier = Modifier.weight(1f))
                    if (source.storedValue == label) Text("✓", color = warm, fontSize = 13.sp)
                }
            }
        }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(RadiolaColors.border))
        Box(
            Modifier.width(72.dp).fillMaxHeight()
                .background(if (enabled) warm else RadiolaColors.surface)
                .semantics { contentDescription = if (action == "ACCESS") "Grant USB access" else action }
                .clickable(enabled = enabled, onClick = onAction),
            contentAlignment = Alignment.Center,
        ) {
            Text(action, color = if (enabled) RadiolaColors.onPrimary else muted, fontSize = 10.sp,
                fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}
