package com.example.sdr

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun FtxInformationPanel(mode: FtxMode, info: FtxInformation, clear: () -> Unit) {
    val context = LocalContext.current
    val online = (context.applicationContext as? RadiolaApplication)?.webSdr
    val remote = online?.source == "WebSDR"
    val url = online?.receiverUrl
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
    val scroll = rememberLazyListState()
    LaunchedEffect(rows.size, rows.lastOrNull()) { if (rows.isNotEmpty()) scroll.scrollToItem(rows.lastIndex) }
    Row(Modifier.fillMaxSize().border(1.dp, RadiolaColors.border, RoundedCornerShape(6.dp))
        .background(RadiolaColors.surface, RoundedCornerShape(6.dp))
        .padding(start = 10.dp, end = 8.dp, top = 3.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.width(80.dp).padding(top = 5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(mode.name, color = RadiolaColors.primary, fontSize = 18.sp)
            Text("USB / 3 kHz", color = RadiolaColors.secondaryText, fontSize = 10.sp)
            Text("${rows.size} messages", color = RadiolaColors.secondaryText, fontSize = 9.sp)
            Spacer(Modifier.height(2.dp))
            Text(if (remote) "SERVER" else "PHONE", color = RadiolaColors.secondaryText,
                fontSize = 8.sp, letterSpacing = .5.sp)
            if (origin != null) {
                Text(coordinateLabel(origin.lat, latitude = true), color = RadiolaColors.text,
                    fontSize = 9.sp, lineHeight = 11.sp, maxLines = 1, fontFamily = FontFamily.Monospace)
                Text(coordinateLabel(origin.lon, latitude = false), color = RadiolaColors.text,
                    fontSize = 9.sp, lineHeight = 11.sp, maxLines = 1, fontFamily = FontFamily.Monospace)
            } else {
                Text(if (remote) "Unavailable" else phoneLocation.status,
                    color = RadiolaColors.secondaryText, fontSize = 8.sp, maxLines = 2,
                    modifier = Modifier.clickable(enabled = !remote) { phoneLocation.request() })
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().padding(top = 5.dp, bottom = 4.dp).background(RadiolaColors.border))
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(info.error ?: if (info.decoding) "DECODING" else "LISTENING",
                    color = RadiolaColors.secondaryText, fontSize = 11.sp, lineHeight = 13.sp,
                    style = androidx.compose.ui.text.TextStyle(platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)))
                Spacer(Modifier.weight(1f))
                Text("CLEAR", color = RadiolaColors.secondaryText, fontSize = 11.sp, lineHeight = 13.sp,
                    style = androidx.compose.ui.text.TextStyle(platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)),
                    modifier = Modifier.clickable { clear() }
                        .padding(horizontal = 8.dp))
            }
            HorizontalDivider(color = RadiolaColors.border.copy(alpha = .6f), thickness = .5.dp)
            if (info.lines.isEmpty()) Text("Waiting for a complete transmission...", color = RadiolaColors.secondaryText,
                fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            else LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = scroll) {
                itemsIndexed(rows) { index, row ->
                    Box(Modifier.fillMaxWidth().background(if (index % 2 == 0) RadiolaColors.primaryTint.copy(alpha = .25f)
                        else androidx.compose.ui.graphics.Color.Transparent)) { FtxTableRow(row) }
                }
            }
        }
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
