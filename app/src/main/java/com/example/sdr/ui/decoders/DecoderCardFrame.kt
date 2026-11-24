package com.example.sdr.ui.decoders

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.sdr.ui.theme.RadiolaColors

/** FT8 card geometry shared by every modulation panel. */
@Composable
internal fun DecoderCardFrame(
    modifier: Modifier = Modifier.fillMaxSize(),
    sidebar: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(modifier.border(1.dp, RadiolaColors.border, RoundedCornerShape(6.dp))
        .background(RadiolaColors.surface, RoundedCornerShape(6.dp))
        .padding(start = 10.dp, end = 8.dp, top = 2.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (sidebar != null) {
            sidebar()
            Box(Modifier.width(1.dp).fillMaxHeight().padding(top = 5.dp, bottom = 4.dp)
                .background(RadiolaColors.border))
        }
        Column(Modifier.weight(1f).fillMaxHeight(), content = content)
    }
}
