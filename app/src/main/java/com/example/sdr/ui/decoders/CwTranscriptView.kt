package com.example.sdr.ui.decoders

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sdr.ui.theme.RadiolaColors
import kotlin.math.floor

@Composable
internal fun CwTranscriptView(text: String, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val style = TextStyle(fontSize = 13.sp, lineHeight = 20.sp, letterSpacing = .25.sp,
        fontFamily = FontFamily.Monospace, platformStyle = PlatformTextStyle(includeFontPadding = false))
    val measurer = rememberTextMeasurer()
    val characterPx = remember(style, density) { measurer.measure("0000000000", style).size.width / 10f }
    val rowHeight = with(density) { 20.sp.toDp() }
    val state = rememberLazyListState()
    val dragged by state.interactionSource.collectIsDraggedAsState()
    var follow by remember { mutableStateOf(true) }
    val transcript = remember { CwTranscript() }
    BoxWithConstraints(modifier.padding(horizontal = 6.dp)) {
        val columns = floor(with(density) { maxWidth.toPx() } / characterPx).toInt().coerceAtLeast(1)
        val lines = remember(text, columns) { transcript.update(text, columns) }
        val visibleRows = floor(maxHeight / rowHeight).toInt().coerceAtLeast(1)
        val atEnd by remember { derivedStateOf {
            state.layoutInfo.visibleItemsInfo.lastOrNull()?.index == state.layoutInfo.totalItemsCount - 1
        } }
        LaunchedEffect(dragged, atEnd) { if (dragged) follow = atEnd }
        LaunchedEffect(lines.lastOrNull()?.id, follow) {
            if (lines.isEmpty()) { follow = true; return@LaunchedEffect }
            if (follow && !dragged) state.animateScrollToItem((lines.size - visibleRows).coerceAtLeast(0))
        }
        LazyColumn(state = state, modifier = Modifier.fillMaxWidth().height(rowHeight * visibleRows)) {
            items(lines, key = { it.id }) { line ->
                Text(line.text, color = RadiolaColors.text, style = style, softWrap = false,
                    maxLines = 1, modifier = Modifier.fillMaxWidth().height(rowHeight))
            }
        }
        if (!follow) Text("↓", color = RadiolaColors.primary, fontSize = 18.sp,
            modifier = Modifier.align(Alignment.BottomEnd)
                .background(RadiolaColors.surface, RoundedCornerShape(4.dp))
                .clickable { follow = true }.padding(horizontal = 10.dp, vertical = 2.dp))
    }
}
