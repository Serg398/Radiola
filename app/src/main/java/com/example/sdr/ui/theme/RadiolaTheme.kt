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
    var dark by mutableStateOf(false)
        private set
    fun select(dark: Boolean, context: android.content.Context) {
        this.dark = dark
        context.getSharedPreferences("appearance", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("dark", dark).apply()
    }
    fun adaptive(light: Color, night: Color) = if (dark) night else light
    val background get() = adaptive(Color(0xFFF1F3F6), Color(0xFF101214))
    val surface get() = adaptive(Color(0xFFFFFFFF), Color(0xFF1A1D20))
    val sidebar get() = adaptive(Color(0xFFE8ECF1), Color(0xFF16191C))
    val border get() = adaptive(Color(0xFFCAD2DD), Color(0xFF3C4248))
    val primary get() = adaptive(Color(0xFF205BCB), Color(0xFFE8B36A))
    val primaryTint get() = adaptive(Color(0xFFE3ECFC), Color(0xFF393025))
    val text get() = adaptive(Color(0xFF202B3B), Color(0xFFECEEF0))
    val secondaryText get() = adaptive(Color(0xFF53647A), Color(0xFFACB3BC))
    val plot get() = adaptive(Color(0xFFFAFBFD), Color(0xFF111416))
    val plotGrid get() = adaptive(Color(0xFFE2E7EE), Color(0xFF292F34))
    val success get() = adaptive(Color(0xFF147450), Color(0xFF77C7A2))
    val warning get() = adaptive(Color(0xFFAC4D14), Color(0xFFF2A269))
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
