package com.example.sdr.ui.decoders

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sdr.decoders.ftx.CallsignCountries
import com.example.sdr.decoders.ftx.FtxContact
import com.example.sdr.decoders.ftx.FtxContacts
import com.example.sdr.decoders.ftx.FtxInformation
import com.example.sdr.decoders.ftx.FtxMode
import com.example.sdr.decoders.ftx.RadioPoint
import com.example.sdr.decoders.ftx.coordinateLabel
import com.example.sdr.decoders.ftx.countryDisplayName
import com.example.sdr.decoders.ftx.countryFlag
import com.example.sdr.platform.rememberPhoneLocation
import com.example.sdr.receiver.ReceiverSource
import com.example.sdr.sources.websdr.WebSdrDirectory
import com.example.sdr.ui.theme.RadiolaColors
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun FtxInformationRoute(mode: FtxMode, info: FtxInformation, clear: () -> Unit,
    source: ReceiverSource, url: String?) {
    val context = LocalContext.current
    val remote = source.online
    val phoneLocation = rememberPhoneLocation(enabled = !remote)
    var countries by remember { mutableStateOf<CallsignCountries?>(null) }
    var serverPoint by remember(url) { mutableStateOf<RadioPoint?>(null) }
    LaunchedEffect(context) {
        countries = withContext(Dispatchers.IO) {
            CallsignCountries(context.assets.open("radio/cty.dat").bufferedReader().use { it.readText() })
        }
    }
    LaunchedEffect(remote, url) {
        serverPoint = if (remote && url != null) withContext(Dispatchers.IO) {
            WebSdrDirectory(context).cached()?.receivers?.firstOrNull { it.url.trimEnd('/') == url.trimEnd('/') }
                ?.let { RadioPoint(it.lat.toDouble(), it.lon.toDouble()) }
        } else null
    }
    val origin = if (remote) serverPoint else phoneLocation.point
    val resolver = remember(countries) { countries?.let(::FtxContacts) }
    val rows = remember(info.lines, origin, resolver) { resolver?.rows(info.lines, origin).orEmpty() }
    FtxInformationPanel(mode, info, rows, origin, remote, phoneLocation.status, phoneLocation.request, clear)
}

@Composable
private fun FtxInformationPanel(mode: FtxMode, info: FtxInformation, rows: List<FtxContact>,
    origin: RadioPoint?, remote: Boolean, locationStatus: String, requestLocation: () -> Unit, clear: () -> Unit) {
    val scroll = rememberLazyListState()
    var displayedRows by remember(mode) { mutableStateOf(emptyList<Triple<String, FtxContact, Long>>()) }
    var enteringRows by remember(mode) { mutableIntStateOf(0) }
    // Keep identity independent of resolved country/grid and list position, so
    // existing messages move smoothly instead of appearing to be new decodes.
    val newestFirst = remember(rows) {
        val occurrences = mutableMapOf<String, Int>()
        rows.map { row ->
            val identity = "${row.utc}|${row.audioHz}|${row.message}"
            val occurrence = occurrences.getOrDefault(identity, 0)
            occurrences[identity] = occurrence + 1
            val key = "$identity|$occurrence"
            key to row
        }.asReversed()
    }
    LaunchedEffect(newestFirst, mode) {
        val arrived = displayedRows.associate { it.first to it.third }
        val headChanged = displayedRows.firstOrNull()?.first != newestFirst.firstOrNull()?.first
        val now = SystemClock.uptimeMillis()
        val added = newestFirst.takeWhile { it.first !in arrived }.size
        enteringRows = if (headChanged) added else 0
        // Anchor the old first row before publishing, then scroll the complete
        // batch into view. One list movement keeps every row touching its neighbor.
        if (enteringRows > 0) scroll.requestScrollToItem(enteringRows)
        displayedRows = newestFirst.map { (key, row) -> Triple(key, row, arrived[key] ?: now) }
        if (headChanged) {
            if (enteringRows > 0) {
                scroll.scrollToItem(enteringRows)
                scroll.animateScrollToItem(0)
            } else scroll.scrollToItem(0)
        }
        enteringRows = 0
    }
    val scrollbarColor = RadiolaColors.secondaryText.copy(alpha = .45f)
    DecoderCardFrame(modifier = Modifier.fillMaxSize(), sidebar = {
        DecoderSidebar(mode.name) {
            Text("USB / 3 kHz", color = RadiolaColors.secondaryText)
            Text("${rows.size} messages", color = RadiolaColors.secondaryText)
            Spacer(Modifier.height(2.dp))
            Text(if (remote) "SERVER" else "PHONE", color = RadiolaColors.secondaryText,
                fontSize = 11.sp, letterSpacing = .5.sp)
            if (origin != null) {
                Text(coordinateLabel(origin.lat, latitude = true), color = RadiolaColors.text,
                    lineHeight = 16.sp, maxLines = 1, fontFamily = FontFamily.Monospace)
                Text(coordinateLabel(origin.lon, latitude = false), color = RadiolaColors.text,
                    lineHeight = 16.sp, maxLines = 1, fontFamily = FontFamily.Monospace)
            } else {
                Text(if (remote) "Unavailable" else locationStatus,
                    color = RadiolaColors.secondaryText, maxLines = 2,
                    modifier = Modifier.clickable(enabled = !remote) { requestLocation() })
            }
        }
    }) {
            DecoderToolbar {
                Text((info.error ?: if (info.decoding) "DECODING" else "LISTENING").uppercase(java.util.Locale.ROOT),
                    color = RadiolaColors.secondaryText, style = DecoderToolbarText,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).alignByBaseline())
                DecoderAction("CLEAR", onClick = clear)
            }
            DecoderDivider()
            if (info.lines.isEmpty()) Text("Waiting for a complete transmission...", color = RadiolaColors.secondaryText,
                fontSize = 11.sp)
            else BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val viewportHeight = maxHeight
            LazyColumn(Modifier.fillMaxSize().drawWithContent {
                drawContent()
                val layout = scroll.layoutInfo
                if (layout.totalItemsCount > 0 && (scroll.canScrollForward || scroll.canScrollBackward)) {
                    val rowHeight = 24.dp.toPx()
                    val viewport = (layout.viewportEndOffset - layout.viewportStartOffset).toFloat()
                    val totalHeight = displayedRows.size * rowHeight
                    val thumbHeight = (size.height * viewport / totalHeight).coerceIn(12.dp.toPx().coerceAtMost(size.height), size.height)
                    val offset = scroll.firstVisibleItemIndex * rowHeight + scroll.firstVisibleItemScrollOffset
                    val progress = (offset / (totalHeight - viewport).coerceAtLeast(1f)).coerceIn(0f, 1f)
                    drawRoundRect(scrollbarColor,
                        topLeft = Offset(size.width - 3.dp.toPx(), (size.height - thumbHeight) * progress),
                        size = Size(2.dp.toPx(), thumbHeight), cornerRadius = CornerRadius(1.dp.toPx()))
                }
            }, state = scroll, contentPadding = PaddingValues(end = 7.dp)) {
                itemsIndexed(displayedRows, key = { _, item -> item.first }) { index, (key, row, arrivedAt) ->
                    Box(Modifier.fillMaxWidth().background(if (index % 2 == 0) RadiolaColors.primaryTint.copy(alpha = .25f)
                        else androidx.compose.ui.graphics.Color.Transparent)) {
                        FtxMessageRow(key, row, arrivedAt)
                    }
                }
                // Temporary runway lets even the first batch start above the
                // viewport. It disappears once the shared movement finishes.
                if (enteringRows > 0) item(key = "arrival-runway") { Spacer(Modifier.height(viewportHeight)) }
            }
            }
            }
}

@Composable
private fun FtxMessageRow(key: String, row: FtxContact, arrivedAt: Long) {
    val fresh = remember(key) { SystemClock.uptimeMillis() - arrivedAt < 1200L }
    val highlight = remember(key) { Animatable(if (fresh) .24f else 0f) }
    LaunchedEffect(key) {
        if (fresh) {
            highlight.animateTo(0f, tween(1000))
        }
    }
    Box(Modifier.fillMaxWidth().background(RadiolaColors.primary.copy(alpha = highlight.value))) {
        FtxTableRow(row)
    }
}

@Composable
private fun FtxTableRow(row: FtxContact) {
    Row(Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        @Composable fun Cell(text: String, modifier: Modifier, accent: Boolean = false, mono: Boolean = false, right: Boolean = false) {
            Text(text, modifier = modifier, color = if (accent) RadiolaColors.primary else RadiolaColors.secondaryText,
                fontSize = 10.sp, fontWeight = if (accent) FontWeight.Medium else FontWeight.Normal,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                textAlign = if (right) TextAlign.End else TextAlign.Start, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Cell(row.utc, Modifier.width(46.dp), mono = true)
        Cell("${row.audioHz} Hz", Modifier.width(46.dp), right = true)
        Cell(row.call ?: "\u2014", Modifier.width(74.dp), accent = true, mono = true)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(countryFlag(row.country), fontSize = 13.sp, modifier = Modifier.width(18.dp), maxLines = 1)
            Text(countryDisplayName(row.country), color = RadiolaColors.text, fontSize = 10.sp,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Cell(row.grid ?: "\u2014", Modifier.width(40.dp), mono = true)
        Text(row.message, modifier = Modifier.weight(1.45f), color = RadiolaColors.text,
            fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Cell(row.distanceKm?.let { "\u2248${it.roundToInt()} km" } ?: "\u2014",
            Modifier.width(62.dp), accent = true, right = true)
    }
}
