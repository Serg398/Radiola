package com.example.sdr.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sdr.ui.theme.RadiolaColors

@Composable
internal fun CompactMenuEntry(label: String, selected: Boolean = false,
    subtitle: String? = null, onClick: () -> Unit) {
    val color = if (selected) RadiolaColors.onActiveMode else RadiolaColors.text
    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp)
        .height(if (subtitle == null) 30.dp else 38.dp)
        .background(if (selected) RadiolaColors.activeMode else Color.Transparent, RoundedCornerShape(4.dp))
        .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
        .padding(horizontal = 10.dp), verticalArrangement = Arrangement.Center) {
        Text(label, color = color, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) Text(subtitle,
            color = if (selected) color.copy(alpha = .75f) else RadiolaColors.secondaryText,
            fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

internal data class CompactMenuAnchor(val modifier: Modifier, val maxHeight: Dp)

/** Leave room below the anchor so DropdownMenu never has to move above it. */
@Composable
internal fun rememberCompactMenuAnchor(): CompactMenuAnchor {
    val density = LocalDensity.current
    val windowHeight = LocalWindowInfo.current.containerSize.height
    var maxHeight by remember(windowHeight, density) { mutableStateOf(240.dp) }
    return CompactMenuAnchor(Modifier.onGloballyPositioned { coordinates ->
        val below = with(density) { (windowHeight - coordinates.boundsInWindow().bottom).toDp() }
        // Material menus reserve a 48 dp margin at the window edge, plus 16 dp content padding.
        maxHeight = (below - 64.dp).coerceIn(30.dp, 300.dp)
    }, maxHeight)
}
