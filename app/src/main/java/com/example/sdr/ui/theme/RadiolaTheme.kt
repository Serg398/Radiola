package com.example.sdr.ui.theme

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle

/** Observable palette shared by Compose and native Canvas drawing. */
internal object RadiolaColors {
    enum class Theme(val label: String) { LIGHT("Light"), DARK("Dark"), RETRO("Classic") }
    var selected by mutableStateOf(Theme.LIGHT)
        private set
    val dark get() = selected == Theme.DARK
    fun select(theme: Theme, context: android.content.Context) {
        selected = theme
        context.getSharedPreferences("appearance", android.content.Context.MODE_PRIVATE)
            .edit().putString("theme", theme.name).apply()
    }
    fun adaptive(light: Color, night: Color, retro: Color = classicAccent(light)) = when (selected) {
        Theme.LIGHT -> light
        Theme.DARK -> night
        Theme.RETRO -> retro
    }
    // Custom Canvas accents use the same warm palette as the standard controls.
    private fun classicAccent(light: Color): Color {
        val rgb = light.copy(alpha = 1f)
        val replacement = when (rgb) {
            Color(0xFF205BCB), Color(0xFF0046CF) -> Color(0xFF57532C)
            Color(0xFFBBCBE0) -> Color(0xFFB9B28D)
            Color(0xFFE4ECF7) -> Color(0xFFDDD6B5)
            Color(0xFFF5F8FD) -> Color(0xFFF3EBCB)
            Color(0xFFDDE7F4) -> Color(0xFFD8D0AC)
            Color(0xFFAEBFD6) -> Color(0xFFA69F7D)
            Color(0xFFFCFDFE), Color(0xFFFCFDFF) -> Color(0xFFF6EED5)
            else -> light
        }
        return replacement.copy(alpha = light.alpha)
    }
    val background get() = adaptive(Color(0xFFF1F3F6), Color(0xFF101214), Color(0xFFECE3C8))
    val surface get() = adaptive(Color(0xFFFFFFFF), Color(0xFF1A1D20), Color(0xFFF3EBD3))
    val sidebar get() = adaptive(Color(0xFFE8ECF1), Color(0xFF16191C), Color(0xFFE5DCBE))
    val border get() = adaptive(Color(0xFFCAD2DD), Color(0xFF3C4248), Color(0xFFBDB38E))
    val primary get() = adaptive(Color(0xFF205BCB), Color(0xFFE8B36A), Color(0xFF57532C))
    val receiveChannel get() = adaptive(Color(0xFFB66A20), Color(0xFFE8B36A), Color(0xFF57532C))
    val primaryTint get() = adaptive(Color(0xFFE3ECFC), Color(0xFF393025), Color(0xFFD2C79F))
    val text get() = adaptive(Color(0xFF202B3B), Color(0xFFECEEF0), Color(0xFF25271E))
    val secondaryText get() = adaptive(Color(0xFF53647A), Color(0xFFACB3BC), Color(0xFF68674E))
    val plot get() = adaptive(Color(0xFFFAFBFD), Color(0xFF111416), Color(0xFFEFE6CA))
    val plotGrid get() = adaptive(Color(0xFFE2E7EE), Color(0xFF292F34), Color(0xFFDCD2AF))
    val success get() = adaptive(Color(0xFF147450), Color(0xFF77C7A2), Color(0xFF59653B))
    val warning get() = adaptive(Color(0xFFAC4D14), Color(0xFFF2A269), Color(0xFFAA5424))
    val onPrimary get() = if (dark) surface else Color.White
    val activeMode get() = if (dark) primaryTint else primary
    val onActiveMode get() = if (dark) primary else Color.White
}

@Composable
internal fun RadiolaTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    remember(context.applicationContext) {
        val preferences = context.getSharedPreferences("appearance", android.content.Context.MODE_PRIVATE)
        val saved = preferences.getString("theme", null)
        val theme = RadiolaColors.Theme.entries.firstOrNull { it.name == saved }
            ?: if (preferences.getBoolean("dark", false)) RadiolaColors.Theme.DARK else RadiolaColors.Theme.LIGHT
        RadiolaColors.select(theme, context)
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
