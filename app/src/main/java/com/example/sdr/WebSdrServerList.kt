package com.example.sdr

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun WebSdrServerList(receivers: List<WebSdrReceiver>, selected: String?,
    onSelect: (WebSdrReceiver) -> Unit, modifier: Modifier) {
    val warm = RadiolaColors.primary
    LazyColumn(modifier.background(RadiolaColors.background), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (receivers.isEmpty()) item { Text("No receivers", color = RadiolaColors.secondaryText, modifier = Modifier.padding(16.dp)) }
        items(receivers, key = { it.url }) { receiver ->
            val name = remember(receiver.name) { android.text.Html.fromHtml(receiver.name, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim() }
            Row(Modifier.fillMaxWidth().background(if (receiver.url == selected) RadiolaColors.primaryTint else RadiolaColors.surface, RoundedCornerShape(5.dp))
                .clickable { onSelect(receiver) }.padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(name, color = if (receiver.url == selected) warm else RadiolaColors.text, fontSize = 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOf(receiver.location, receiver.url).filter { it.isNotBlank() }.joinToString("  /  "),
                        color = RadiolaColors.secondaryText, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                receiver.users?.let { Text("$it listeners", color = RadiolaColors.secondaryText, fontSize = 10.sp) }

            }
        }
    }
}
