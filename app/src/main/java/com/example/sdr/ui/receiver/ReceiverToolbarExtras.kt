package com.example.sdr.ui.receiver

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sdr.ui.theme.RadiolaColors

@Composable
internal fun StereoToggle(available: Boolean, enabled: Boolean, locked: Boolean, onChange: (Boolean) -> Unit) {
    val active = available && enabled
    Column(
        Modifier.width(60.dp).height(28.dp)
            .background(if (active) RadiolaColors.primaryTint else RadiolaColors.surface, RoundedCornerShape(5.dp))
            .clickable(enabled = available) { onChange(!enabled) }
            .semantics { contentDescription = "WFM stereo ${if (active) "enabled" else "disabled"}, pilot ${if (locked) "locked" else "absent"}" },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Text(if (active) "STEREO" else "MONO", color = if (active) RadiolaColors.primary else if (available) RadiolaColors.text else RadiolaColors.secondaryText.copy(alpha = 0.5f), fontSize = 10.sp)
        Text(if (!available) "WFM" else if (!enabled) "FORCED" else if (locked) "LOCK" else "AUTO",
            color = if (active && locked) RadiolaColors.primary else RadiolaColors.secondaryText, fontSize = 8.sp)
    }
}

@Composable
internal fun ToolbarDivider() {
    Box(Modifier.width(1.dp).height(16.dp).background(RadiolaColors.border))
}

@Composable
internal fun ControlCard(modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.border(1.dp, RadiolaColors.border.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
            .background(RadiolaColors.surface, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        content = { content() }
    )
}

@Composable
internal fun SmallAction(label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(28.dp).border(1.dp, if (enabled) RadiolaColors.primary else RadiolaColors.border, RoundedCornerShape(4.dp))
            .background(if (enabled) RadiolaColors.primaryTint else RadiolaColors.surface, RoundedCornerShape(4.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (enabled) RadiolaColors.primary else RadiolaColors.secondaryText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}
