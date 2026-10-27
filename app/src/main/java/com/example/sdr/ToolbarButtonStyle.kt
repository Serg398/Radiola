package com.example.sdr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

@Composable
internal fun Modifier.toolbarButtonSurface(active: Boolean = false): Modifier {
    val shape = RoundedCornerShape(5.dp)
    return clip(shape)
        .background(if (active) RadiolaColors.primaryTint else RadiolaColors.surface)
        .border(.75.dp, if (active) RadiolaColors.primary.copy(alpha = .6f)
            else RadiolaColors.border, shape)
}
