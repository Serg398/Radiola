package com.example.sdr.ui.receiver

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sdr.ui.theme.RadiolaColors

@Composable
internal fun ReceiverErrorNotice(message: String?, modifier: Modifier = Modifier) {
    var dismissed by remember(message) { mutableStateOf(false) }
    if (message == null || dismissed) return
    Row(modifier.widthIn(max = 440.dp)
        .background(RadiolaColors.surface, RoundedCornerShape(6.dp))
        .border(1.dp, RadiolaColors.warning.copy(alpha = .5f), RoundedCornerShape(6.dp))
        .semantics { liveRegion = LiveRegionMode.Polite }
        .padding(start = 12.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("USB · $message", Modifier.weight(1f, fill = false).padding(vertical = 8.dp),
            color = RadiolaColors.warning, fontSize = 12.sp, maxLines = 2)
        Text("×", Modifier.semantics { contentDescription = "Dismiss receiver error" }
            .clickable { dismissed = true }.padding(horizontal = 10.dp, vertical = 8.dp),
            color = RadiolaColors.secondaryText, fontSize = 20.sp)
    }
}
