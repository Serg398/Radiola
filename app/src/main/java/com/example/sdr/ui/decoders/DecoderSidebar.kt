package com.example.sdr.ui.decoders

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sdr.ui.theme.RadiolaColors

internal val DecoderSidebarWidth = 124.dp

/** Common label column keeps decoder identity and metadata aligned between cards. */
@Composable
internal fun DecoderSidebar(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.width(DecoderSidebarWidth).padding(top = 5.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = RadiolaColors.primary, fontSize = 18.sp, lineHeight = 24.sp,
            fontWeight = FontWeight.Medium, letterSpacing = .6.sp,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)))
        content()
    }
}
