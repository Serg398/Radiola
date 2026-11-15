package com.example.sdr.ui.receiver

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sdr.favorites.FavoriteStation
import com.example.sdr.receiver.ReceiverSource
import com.example.sdr.ui.theme.RadiolaColors as C
import java.util.Locale

@Composable
internal fun FavoriteEditor(value: FavoriteStation, existing: Boolean, onSave: (FavoriteStation) -> Unit,
    onDelete: () -> Unit, onClose: () -> Unit) {
    var name by remember(value.id) { mutableStateOf(value.name) }
    var deleting by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onClose, containerColor = C.surface,
        title = { Text(if (existing) "Edit favorite" else "Add favorite", color = C.text) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stationDetails(value), color = C.secondaryText, fontSize = 12.sp)
            OutlinedTextField(name, { name = it.take(100) }, label = { Text("Name") }, singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = C.text, unfocusedTextColor = C.text,
                    cursorColor = C.primary, focusedBorderColor = C.primary, unfocusedBorderColor = C.border,
                    focusedLabelColor = C.primary, unfocusedLabelColor = C.secondaryText))
            if (existing) TextButton(onClick = { deleting = true }) { Text("Delete", color = C.warning) }
        } },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(value.copy(name = name)); onClose() }) { Text("Save", color = C.primary) } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel", color = C.secondaryText) } })
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, containerColor = C.surface,
        title = { Text("Delete favorite?", color = C.text) }, text = { Text(value.name, color = C.secondaryText) },
        confirmButton = { TextButton(onClick = { onDelete(); onClose() }) { Text("Delete", color = C.warning) } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } })
}

private fun stationDetails(v: FavoriteStation): String = buildString {
    append(String.format(Locale.US, "%.6f MHz", v.frequencyHz / 1e6))
    append("  /  ${v.modeLabel}  /  ")
    append(v.bandwidthHz?.let { "${it / 1000.0} kHz" } ?: "Default BW")
}

@Composable
internal fun FavoritesPanel(presentation: ReceiverPresentation, onEdit: (FavoriteStation) -> Unit, onClose: () -> Unit, usbConnected: Boolean) {
    var filter by remember { mutableStateOf<ReceiverSource?>(null) }
    BackHandler { presentation.cancelFavorite(); onClose() }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Favorites", color = C.text, fontSize = 22.sp, fontWeight = FontWeight.Light, modifier = Modifier.weight(1f))
            listOf<ReceiverSource?>(null, *ReceiverSource.entries.toTypedArray()).forEach { source ->
                Text(source?.storedValue ?: "All", color = if (source == ReceiverSource.USB && !usbConnected) C.secondaryText.copy(alpha = .38f)
                    else if (filter == source) C.primary else C.secondaryText,
                    fontSize = 12.sp, modifier = Modifier.clickable { filter = source }.padding(horizontal = 10.dp, vertical = 10.dp))
            }
        }
        presentation.favoriteError?.let { Text(it, color = C.warning, fontSize = 12.sp) }
        val values = presentation.favorites.stations.filter { filter == null || it.source == filter }
        if (values.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Save a station using the star beside the frequency", color = C.secondaryText, fontSize = 14.sp)
        } else LazyColumn(Modifier.fillMaxSize()) {
            items(values, key = { it.id }) { value ->
                Row(Modifier.fillMaxWidth().heightIn(min = 62.dp).clickable(enabled = presentation.applyingFavorite == null) {
                    presentation.activateFavorite(value, onClose)
                }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(value.name, color = C.text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stationDetails(value), color = C.primary, fontSize = 12.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(value.source.storedValue,
                                color = if (value.source == ReceiverSource.USB && !usbConnected) C.secondaryText.copy(alpha = .38f) else C.secondaryText,
                                fontSize = 11.sp)
                            val details = listOf(android.text.Html.fromHtml(value.serverName, android.text.Html.FROM_HTML_MODE_LEGACY).toString(), value.bandName)
                                .filter { it.isNotBlank() }.joinToString(" / ")
                            if (details.isNotEmpty()) Text(" / $details", color = C.secondaryText,
                                fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (presentation.applyingFavorite == value.id) CircularProgressIndicator(Modifier.size(18.dp), color = C.primary, strokeWidth = 2.dp)
                    TextButton(onClick = { onEdit(value) }, enabled = presentation.applyingFavorite == null) { Text("Edit", color = C.secondaryText) }
                }
                Box(Modifier.fillMaxWidth().height(.5.dp).background(C.border))
            }
        }
    }
}
