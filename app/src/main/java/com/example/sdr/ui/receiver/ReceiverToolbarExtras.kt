package com.example.sdr.ui.receiver

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sdr.ui.theme.RadiolaColors
import com.example.sdr.ui.components.ToolbarCaptionStyle
import com.example.sdr.ui.components.ToolbarValueStyle
import com.example.sdr.ui.components.toolbarButtonSurface

@Composable
internal fun StereoToggle(available: Boolean, enabled: Boolean, locked: Boolean, onChange: (Boolean) -> Unit) {
    val active = available && enabled
    Column(
        Modifier.width(60.dp).height(28.dp)
            .toolbarButtonSurface(active)
            .clickable(enabled = available) { onChange(!enabled) }
            .semantics { contentDescription = "WFM stereo ${if (active) "enabled" else "disabled"}, pilot ${if (locked) "locked" else "absent"}" },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Text(if (active) "STEREO" else "MONO", modifier = Modifier.height(13.dp), style = ToolbarValueStyle, color = if (active) RadiolaColors.primary else if (available) RadiolaColors.text else RadiolaColors.secondaryText.copy(alpha = 0.5f), fontSize = 10.sp)
        Text(if (!available) "WFM" else if (!enabled) "FORCED" else if (locked) "LOCK" else "AUTO",
            modifier = Modifier.height(10.dp), style = ToolbarCaptionStyle,
            color = if (active && locked) RadiolaColors.primary else RadiolaColors.secondaryText, fontSize = 8.sp)
    }
}
