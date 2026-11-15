package com.example.sdr.ui.receiver

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sdr.favorites.FavoriteStation
import com.example.sdr.ui.theme.RadiolaColors

private data class FavoriteLabelGroup(val stations: MutableList<FavoriteStation>, var left: Float, var width: Float)

/** Only the spectrum carries interactive labels; all RF anchors stay at their actual frequency. */
@Composable
internal fun FavoriteFrequencyMarkers(stations: List<FavoriteStation>, centerHz: Double, spanHz: Int,
    modifier: Modifier = Modifier, endInset: Dp = 0.dp, onSelect: (FavoriteStation) -> Unit) {
    val accent = RadiolaColors.primary
    val guide = RadiolaColors.adaptive(Color(0xFF167A78), Color(0xFF70C7C2), Color(0xFF397F77))
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = TextStyle(fontSize = 10.sp)
    BoxWithConstraints(modifier) {
        val low = centerHz - spanHz / 2.0
        val visible = remember(stations, centerHz, spanHz) {
            if (spanHz <= 0) emptyList() else stations.filter { it.frequencyHz.toDouble() in low..(low + spanHz) }.sortedBy { it.frequencyHz }
        }
        fun x(station: FavoriteStation) = ((station.frequencyHz - low) / spanHz * with(density) { maxWidth.toPx() }).toFloat()
        val groups = remember(visible, centerHz, spanHz, maxWidth, endInset, density) {
            val available = with(density) { (maxWidth - endInset).coerceAtLeast(1.dp).toPx() }
            val maxLabelWidth = with(density) { 104.dp.toPx() }.coerceAtMost(available)
            val padding = with(density) { 12.dp.toPx() }
            val gap = with(density) { 4.dp.toPx() }
            val result = mutableListOf<FavoriteLabelGroup>()
            visible.forEach { station ->
                val width = (measurer.measure(station.name, style, softWrap = false).size.width + padding).coerceAtMost(maxLabelWidth)
                val left = (x(station) - width / 2).coerceIn(0f, (available - width).coerceAtLeast(0f))
                val previous = result.lastOrNull()
                if (previous != null && left < previous.left + previous.width + gap) {
                    previous.stations.add(station)
                    previous.width = maxLabelWidth
                    previous.left = previous.left.coerceAtMost((available - maxLabelWidth).coerceAtLeast(0f))
                    // A widened cluster can reach the preceding cluster near an edge.
                    while (result.size > 1) {
                        val before = result[result.lastIndex - 1]
                        if (previous.left >= before.left + before.width + gap) break
                        previous.stations.addAll(0, before.stations)
                        previous.left = before.left.coerceAtMost((available - maxLabelWidth).coerceAtLeast(0f))
                        result.removeAt(result.lastIndex - 1)
                    }
                } else result.add(FavoriteLabelGroup(mutableListOf(station), left, width))
            }
            result
        }
        Canvas(Modifier.fillMaxSize()) {
            clipRect {
                visible.distinctBy { it.frequencyHz }.forEach { station ->
                    val anchor = x(station)
                    drawLine(guide.copy(alpha = .38f), Offset(anchor, 17.dp.toPx()), Offset(anchor, size.height),
                        .65.dp.toPx())
                }
            }
        }
        groups.forEach { group ->
            key(group.stations.map { it.id }) {
                var expanded by remember { mutableStateOf(false) }
                val multiple = group.stations.size > 1
                val title = if (multiple) "${group.stations.first().name} +${group.stations.size - 1}" else group.stations.first().name
                Box(Modifier.offset(x = with(density) { group.left.toDp() }, y = 2.dp)
                    .width(with(density) { group.width.toDp() })) {
                    Text(title, color = accent, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                            .background(RadiolaColors.plot, RoundedCornerShape(4.dp))
                            .border(.65.dp, accent.copy(alpha = .7f), RoundedCornerShape(4.dp))
                            .semantics { contentDescription = if (multiple) "${group.stations.size} favorite stations" else "Tune to ${group.stations.first().name}" }
                            .clickable { if (multiple) expanded = true else onSelect(group.stations.first()) }
                            .padding(horizontal = 5.dp, vertical = 1.dp))
                    if (multiple) DropdownMenu(expanded, onDismissRequest = { expanded = false }, containerColor = RadiolaColors.surface) {
                        group.stations.forEach { station ->
                            DropdownMenuItem(text = { Text("${station.name}  /  ${station.source.storedValue}", color = RadiolaColors.text) },
                                onClick = { expanded = false; onSelect(station) })
                        }
                    }
                }
            }
        }
    }
}
