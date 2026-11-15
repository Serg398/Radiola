package com.example.sdr.ui.receiver

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
    val defaultName = remember(value.frequencyHz) {
        val frequency = String.format(Locale.US, "%.6f", value.frequencyHz / 1e6)
        val whole = frequency.substringBefore('.')
        val fraction = frequency.substringAfter('.').trimEnd('0').padEnd(3, '0')
        "$whole.$fraction MHz"
    }
    var name by remember(value.id) { mutableStateOf(if (existing && value.name != defaultName) value.name else "") }
    var deleting by remember { mutableStateOf(false) }
    fun save() { onSave(value.copy(name = name.trim().ifBlank { defaultName })); onClose() }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.width(260.dp).background(C.surface, RoundedCornerShape(12.dp))
            .border(.65.dp, C.border, RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(defaultName, color = C.text, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Name", color = C.secondaryText, fontSize = 11.sp)
                BasicTextField(name, { name = it.take(100) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(vertical = 4.dp),
                    textStyle = TextStyle(color = C.text, fontSize = 16.sp),
                    cursorBrush = SolidColor(C.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    decorationBox = { input ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (name.isEmpty()) Text(defaultName, color = C.secondaryText.copy(alpha = .5f), fontSize = 16.sp)
                            input()
                        }
                    })
                Box(Modifier.fillMaxWidth().height(.65.dp).background(C.border))
            }
            Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                if (existing) Text("Delete", color = C.secondaryText, fontSize = 12.sp,
                    modifier = Modifier.clickable { deleting = true }.padding(vertical = 8.dp))
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClose, modifier = Modifier.height(34.dp), contentPadding = PaddingValues(horizontal = 10.dp)) {
                    Text("Cancel", color = C.secondaryText, fontSize = 12.sp)
                }
                TextButton(onClick = { save() }, modifier = Modifier.height(34.dp), contentPadding = PaddingValues(horizontal = 10.dp)) {
                    Text("Save", color = C.primary, fontSize = 12.sp)
                }
            }
        }
    }

    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, containerColor = C.surface,
        title = { Text("Delete favorite?", color = C.text) }, text = { Text(value.name, color = C.secondaryText) },
        confirmButton = { TextButton(onClick = { onDelete(); onClose() }) { Text("Delete", color = C.warning) } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } })
}

private enum class FavoriteSort { NAME, FREQUENCY, MODE }

@Composable
internal fun FavoritesPanel(presentation: ReceiverPresentation, onEdit: (FavoriteStation) -> Unit, onClose: () -> Unit, usbConnected: Boolean) {
    var filter by rememberSaveable { mutableStateOf<ReceiverSource?>(null) }
    var sort by rememberSaveable { mutableStateOf(FavoriteSort.NAME) }
    var descending by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<FavoriteStation?>(null) }
    BackHandler { presentation.cancelFavorite(); onClose() }
    val values = remember(presentation.favorites.stations, filter, sort, descending) {
        val comparator = when (sort) {
            FavoriteSort.NAME -> compareBy<FavoriteStation> { it.name.lowercase(Locale.ROOT) }
            FavoriteSort.FREQUENCY -> compareBy { it.frequencyHz }
            FavoriteSort.MODE -> compareBy { it.modeLabel.lowercase(Locale.ROOT) }
        }.thenBy { it.id }
        presentation.favorites.stations.filter { filter == null || it.source == filter }
            .sortedWith(if (descending) comparator.reversed() else comparator)
    }
    fun sortBy(column: FavoriteSort) {
        if (sort == column) descending = !descending else { sort = column; descending = false }
    }
    fun heading(label: String, column: FavoriteSort) = label + if (sort == column) {
        if (descending) " \u2193" else " \u2191"
    } else ""
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Favorites", color = C.text, fontSize = 22.sp, fontWeight = FontWeight.Light, modifier = Modifier.weight(1f))
            listOf<ReceiverSource?>(null, *ReceiverSource.entries.toTypedArray()).forEach { source ->
                Text(source?.storedValue ?: "All", color = if (source == ReceiverSource.USB && !usbConnected) C.secondaryText.copy(alpha = .38f)
                    else if (filter == source) C.primary else C.secondaryText,
                    fontSize = 12.sp, modifier = Modifier.clickable { filter = source }.padding(horizontal = 10.dp, vertical = 10.dp))
            }
        }
        presentation.favoriteError?.let { Text(it, color = C.warning, fontSize = 12.sp) }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            // Header and rows share one horizontal viewport on narrower screens.
            val tableWidth = maxOf(maxWidth, 680.dp)
            Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                Column(Modifier.width(tableWidth).fillMaxHeight()) {
                    Row(Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FavoriteCell(heading("Name", FavoriteSort.NAME), Modifier.weight(1.35f).clickable { sortBy(FavoriteSort.NAME) }, header = true, active = sort == FavoriteSort.NAME)
                        FavoriteCell(heading("Frequency", FavoriteSort.FREQUENCY), Modifier.width(116.dp).clickable { sortBy(FavoriteSort.FREQUENCY) }, header = true,
                            active = sort == FavoriteSort.FREQUENCY, right = true)
                        FavoriteCell(heading("Mode", FavoriteSort.MODE), Modifier.width(64.dp).clickable { sortBy(FavoriteSort.MODE) }, header = true, active = sort == FavoriteSort.MODE)
                        FavoriteCell("BW", Modifier.width(66.dp), header = true)
                        FavoriteCell("Source", Modifier.width(86.dp), header = true)
                        FavoriteCell("Server / Band", Modifier.weight(1.4f), header = true)
                        Spacer(Modifier.width(36.dp))
                    }
                    FavoriteDivider()
                    if (values.isEmpty()) Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text("Save a station using the star above MHz", color = C.secondaryText, fontSize = 13.sp)
                    } else LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                        items(values, key = { it.id }) { value ->
                            var menu by remember { mutableStateOf(false) }
                            val loading = presentation.applyingFavorite == value.id
                            val enabled = presentation.applyingFavorite == null
                            Row(Modifier.fillMaxWidth().height(44.dp)
                                .background(if (loading) C.primary.copy(alpha = .07f) else Color.Transparent)
                                .clickable(enabled = enabled) { presentation.activateFavorite(value, onClose) }
                                .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                FavoriteCell(value.name, Modifier.weight(1.35f), color = C.text)
                                FavoriteCell(String.format(Locale.US, "%.6f", value.frequencyHz / 1e6) + " MHz",
                                    Modifier.width(116.dp), color = C.primary, right = true, numeric = true)
                                FavoriteCell(value.modeLabel, Modifier.width(64.dp))
                                val bandwidth = value.bandwidthHz?.let { hz ->
                                    if (hz < 1000) "$hz Hz" else "${String.format(Locale.US, "%.3f", hz / 1000.0).trimEnd('0').trimEnd('.')} kHz"
                                } ?: "Default"
                                FavoriteCell(bandwidth, Modifier.width(66.dp))
                                FavoriteCell(value.source.storedValue, Modifier.width(86.dp),
                                    color = if (value.source == ReceiverSource.USB && !usbConnected) C.secondaryText.copy(alpha = .38f) else C.secondaryText)
                                val server = remember(value.serverName, value.bandName) {
                                    listOf(android.text.Html.fromHtml(value.serverName, android.text.Html.FROM_HTML_MODE_LEGACY).toString(), value.bandName)
                                        .filter { it.isNotBlank() }.joinToString(" / ").ifEmpty { "\u2014" }
                                }
                                FavoriteCell(server, Modifier.weight(1.4f))
                                Box(Modifier.width(36.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                                    if (loading) CircularProgressIndicator(Modifier.size(16.dp), color = C.primary, strokeWidth = 1.5.dp)
                                    else {
                                        Text("\u22ee", color = C.secondaryText, fontSize = 21.sp,
                                            modifier = Modifier.fillMaxSize().semantics { contentDescription = "Actions for ${value.name}" }
                                                .clickable(enabled = enabled) { menu = true }.wrapContentSize())
                                        DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = C.surface) {
                                            DropdownMenuItem(text = { Text("Rename", color = C.text) }, onClick = { menu = false; onEdit(value) })
                                            DropdownMenuItem(text = { Text("Delete", color = C.warning) }, onClick = { menu = false; deleting = value })
                                        }
                                    }
                                }
                            }
                            FavoriteDivider()
                        }
                    }
                }
            }
        }
    }
    deleting?.let { value ->
        AlertDialog(onDismissRequest = { deleting = null }, containerColor = C.surface,
            title = { Text("Delete favorite?", color = C.text) }, text = { Text(value.name, color = C.secondaryText) },
            confirmButton = { TextButton(onClick = { presentation.favorites.delete(value.id); deleting = null }) { Text("Delete", color = C.warning) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel", color = C.secondaryText) } })
    }
}

@Composable
private fun FavoriteCell(text: String, modifier: Modifier, header: Boolean = false, active: Boolean = false,
    right: Boolean = false, numeric: Boolean = false, color: Color = C.secondaryText) {
    Box(modifier.fillMaxHeight().padding(horizontal = 6.dp),
        contentAlignment = if (right) Alignment.CenterEnd else Alignment.CenterStart) {
        Text(text, color = if (header && active) C.primary else color, fontSize = if (header) 10.sp else 12.sp,
            fontWeight = if (header) FontWeight.Medium else FontWeight.Normal,
            style = TextStyle(fontFeatureSettings = if (numeric) "tnum" else null),
            textAlign = if (right) TextAlign.End else TextAlign.Start, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun FavoriteDivider() {
    Box(Modifier.fillMaxWidth().height(.5.dp).background(C.border.copy(alpha = .6f)))
}
