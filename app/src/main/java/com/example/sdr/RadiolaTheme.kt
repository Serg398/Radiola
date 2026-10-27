package com.example.sdr

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

/** Shared light instrument palette; layout and receiver behavior stay independent. */
internal object RadiolaColors {
    val background = Color(0xFFF2F6FC)
    val surface = Color(0xFFFCFDFF)
    val sidebar = Color(0xFFE8EFF8)
    val border = Color(0xFFC7D5E8)
    val primary = Color(0xFF175CE5)
    val primaryTint = Color(0xFFE2ECFF)
    val text = Color(0xFF162842)
    val secondaryText = Color(0xFF62748E)
    val plot = Color(0xFFF7FAFF)
    val success = Color(0xFF17855B)
    val warning = Color(0xFFBD5B1A)
}

private val radiolaScheme = lightColorScheme(
    primary = RadiolaColors.primary,
    onPrimary = Color.White,
    primaryContainer = RadiolaColors.primaryTint,
    onPrimaryContainer = RadiolaColors.primary,
    secondary = RadiolaColors.secondaryText,
    background = RadiolaColors.background,
    onBackground = RadiolaColors.text,
    surface = RadiolaColors.surface,
    onSurface = RadiolaColors.text,
    surfaceVariant = RadiolaColors.sidebar,
    onSurfaceVariant = RadiolaColors.secondaryText,
    outline = RadiolaColors.border,
)

@Composable
internal fun RadiolaLightTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = radiolaScheme) {
        // Keep the existing text metrics: changing colors must not alter layout.
        CompositionLocalProvider(LocalTextStyle provides TextStyle.Default, content = content)
    }
}
