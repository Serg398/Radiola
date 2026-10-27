package com.example.sdr

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

/** Observable palette shared by Compose and native Canvas drawing. */
internal object RadiolaColors {
    var dark by mutableStateOf(false)
        private set
    fun select(dark: Boolean, context: android.content.Context) {
        this.dark = dark
        context.getSharedPreferences("appearance", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("dark", dark).apply()
    }
    fun adaptive(light: Color, night: Color) = if (dark) night else light
    val background get() = adaptive(Color(0xFFF2F6FC), Color(0xFF0D0F11))
    val surface get() = adaptive(Color(0xFFFCFDFF), Color(0xFF151719))
    val sidebar get() = adaptive(Color(0xFFE8EFF8), Color(0xFF171819))
    val border get() = adaptive(Color(0xFFC7D5E8), Color(0xFF30302E))
    val primary get() = adaptive(Color(0xFF175CE5), Color(0xFFF7BA6D))
    val primaryTint get() = adaptive(Color(0xFFE2ECFF), Color(0xFF3C2A1D))
    val text get() = adaptive(Color(0xFF162842), Color(0xFFF4E8D7))
    val secondaryText get() = adaptive(Color(0xFF62748E), Color(0xFF968E84))
    val plot get() = adaptive(Color(0xFFF7FAFF), Color(0xFF0C0E10))
    val success get() = adaptive(Color(0xFF17855B), Color(0xFFF7BA6D))
    val warning get() = adaptive(Color(0xFFBD5B1A), Color(0xFFFF8D55))
    val onPrimary get() = if (dark) surface else Color.White
    val activeMode get() = if (dark) primaryTint else primary
    val onActiveMode get() = if (dark) primary else Color.White
}

@Composable
internal fun RadiolaTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    remember(context.applicationContext) {
        val saved = context.getSharedPreferences("appearance", android.content.Context.MODE_PRIVATE).getBoolean("dark", false)
        RadiolaColors.select(saved, context)
        true
    }
    val scheme = if (RadiolaColors.dark) darkColorScheme(
        primary = RadiolaColors.primary, onPrimary = RadiolaColors.onPrimary,
        background = RadiolaColors.background, onBackground = RadiolaColors.text,
        surface = RadiolaColors.surface, onSurface = RadiolaColors.text,
        primaryContainer = RadiolaColors.primaryTint, onPrimaryContainer = RadiolaColors.primary,
        surfaceVariant = RadiolaColors.sidebar, onSurfaceVariant = RadiolaColors.secondaryText,
        outline = RadiolaColors.border,
    ) else lightColorScheme(
        primary = RadiolaColors.primary, onPrimary = RadiolaColors.onPrimary,
        background = RadiolaColors.background, onBackground = RadiolaColors.text,
        surface = RadiolaColors.surface, onSurface = RadiolaColors.text,
        primaryContainer = RadiolaColors.primaryTint, onPrimaryContainer = RadiolaColors.primary,
        surfaceVariant = RadiolaColors.sidebar, onSurfaceVariant = RadiolaColors.secondaryText,
        outline = RadiolaColors.border,
    )
    MaterialTheme(colorScheme = scheme) {
        // Keep the existing text metrics: changing colors must not alter layout.
        CompositionLocalProvider(LocalTextStyle provides TextStyle.Default, content = content)
    }
}
