package com.example.sdr.ui.decoders

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sdr.ui.theme.RadiolaColors

internal val DecoderToolbarText = TextStyle(fontSize = 10.sp, lineHeight = 12.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false))

@Composable
internal fun DecoderToolbar(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(14.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
internal fun DecoderDivider() {
    HorizontalDivider(modifier = Modifier.padding(bottom = 4.dp),
        color = RadiolaColors.border.copy(alpha = .6f), thickness = .5.dp)
}

@Composable
internal fun RowScope.DecoderAction(label: String, enabled: Boolean = true, checked: Boolean? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(4.dp)
    val active = checked == true
    val action = if (checked != null) Modifier.toggleable(value = checked, enabled = enabled,
        role = Role.Switch, onValueChange = { onClick() }) else Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    Text(label.uppercase(java.util.Locale.ROOT), color = (if (active) RadiolaColors.primary else RadiolaColors.secondaryText)
        .copy(alpha = if (enabled) 1f else .35f), style = DecoderToolbarText,
        modifier = Modifier.alignByBaseline().height(14.dp).then(action)
            .background(if (active) RadiolaColors.primaryTint else Color.Transparent, shape)
            .padding(horizontal = 8.dp).wrapContentHeight(Alignment.CenterVertically))
}
