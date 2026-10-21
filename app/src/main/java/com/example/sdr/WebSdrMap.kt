package com.example.sdr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import android.text.Html
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.min
import kotlin.math.log2
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


/** Offline map rendered by the app from public-domain Natural Earth geometry. */
@Composable
internal fun WebSdrMap(
    modifier: Modifier = Modifier,
    selectedReceiverUrl: String? = null,
    onReceiverSelected: (WebSdrReceiver) -> Unit = {},
) {
    val currentSelectionCallback by rememberUpdatedState(onReceiverSelected)
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    var listMode by rememberSaveable { mutableStateOf(false) }
    var cities by remember { mutableStateOf<List<MapCity>>(emptyList()) }
    val cityPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.DEFAULT } }
    val cityOutline = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.DEFAULT
        style = Paint.Style.STROKE
        color = android.graphics.Color.rgb(12, 16, 19)
        strokeJoin = Paint.Join.ROUND
    } }
    LaunchedEffect(Unit) {
        cities = WebSdrMapAssets.cities(context)
    }

    val directory = remember { WebSdrDirectory(context.applicationContext) }
    var mapReceivers by remember { mutableStateOf<List<WebSdrReceiver>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var directoryFailed by remember { mutableStateOf(false) }
    var cached by remember { mutableStateOf(false) }
    var updatedAt by remember { mutableLongStateOf(0L) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) {
        loading = true
        directoryFailed = false
        try {
            val saved = withContext(Dispatchers.IO) { directory.cached() }
            if (saved != null && mapReceivers.isEmpty()) {
                mapReceivers = saved.receivers; updatedAt = saved.updatedAt; cached = true
            }
            if (refresh > 0 || saved == null || System.currentTimeMillis() - saved.updatedAt !in 0L..120_000L) {
                val live = withContext(Dispatchers.IO) { directory.fetch() }
                mapReceivers = live.receivers; updatedAt = live.updatedAt; cached = false
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { directoryFailed = true; cached = mapReceivers.isNotEmpty() }
        finally { loading = false }
    }
    var land by remember { mutableStateOf<List<Path>>(emptyList()) }
    var loadFailed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            land = WebSdrMapAssets.land(context)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { loadFailed = true }
    }
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    var panX by rememberSaveable { mutableFloatStateOf(0f) }
    var panY by rememberSaveable { mutableFloatStateOf(0f) }
    var selectedUrl by rememberSaveable { mutableStateOf(selectedReceiverUrl) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val warm = Color(0xFFF7BA6D)
    val muted = Color(0xFF968E84)
    fun point(receiver: WebSdrReceiver): Offset {
        val unit = min(viewport.width / 360f, viewport.height / 180f) * zoom
        return Offset(viewport.width / 2f + panX + receiver.lon * unit,
            viewport.height / 2f + panY - receiver.lat * unit)
    }
    fun transform(factor: Float, delta: Offset = Offset.Zero, anchor: Offset = Offset(viewport.width / 2f, viewport.height / 2f)) {
        val next = (zoom * factor).coerceIn(1f, 12f)
        val ratio = next / zoom
        val offset = anchor - Offset(viewport.width / 2f, viewport.height / 2f)
        panX = ((panX - offset.x) * ratio + offset.x + delta.x)
            .coerceIn(-viewport.width * (next - 1) / 2, viewport.width * (next - 1) / 2)
        panY = ((panY - offset.y) * ratio + offset.y + delta.y)
            .coerceIn(-viewport.height * (next - 1) / 2, viewport.height * (next - 1) / 2)
        zoom = next
    }
    Box(modifier.clipToBounds().background(Color(0xFF0C1013))
        .border(1.dp, Color(0xFF30302E), RoundedCornerShape(6.dp))) {
        if (!listMode) Canvas(Modifier.fillMaxSize().onSizeChanged { viewport = it }
            .pointerInput(Unit) { detectTransformGestures { centroid, pan, scale, _ -> transform(scale, pan, centroid) } }
            .pointerInput(Unit) { detectTapGestures(
                onDoubleTap = { transform(1.8f, anchor = it) },
                onTap = { tap ->
                    val receiver = mapReceivers.minByOrNull { (point(it) - tap).getDistance() }
                        ?.takeIf { (point(it) - tap).getDistance() < 28.dp.toPx() }
                    selectedUrl = receiver?.url
                    receiver?.let { currentSelectionCallback(it) }
                },
            ) }) {
            val unit = min(size.width / 360f, size.height / 180f) * zoom
            val center = Offset(size.width / 2f + panX, size.height / 2f + panY)
            for (lon in -180..180 step 30) {
                val x = center.x + lon * unit
                drawLine(Color(0xFF1B252B), Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
            }
            for (lat in -90..90 step 30) {
                val y = center.y - lat * unit
                drawLine(Color(0xFF1B252B), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
            }
            withTransform({ translate(center.x, center.y); scale(unit, unit, pivot = Offset.Zero) }) {
                land.forEach { path ->
                    drawPath(path, Color(0xFF232C30))
                    drawPath(path, Color(0xFF4B5350), style = Stroke(0.7.dp.toPx() / unit))
                }
            }
            val labelSize = 10.sp.toPx()
            cityPaint.textSize = labelSize
            cityPaint.color = android.graphics.Color.rgb(185, 192, 188)
            cityOutline.textSize = labelSize
            cityOutline.strokeWidth = 3.dp.toPx()
            val occupied = mutableListOf(
                RectF(0f, 0f, 200.dp.toPx(), 88.dp.toPx()),
                RectF(size.width - 50.dp.toPx(), 0f, size.width, 130.dp.toPx()),
            )
            occupied += RectF(200.dp.toPx(), 0f, size.width - 50.dp.toPx(), 52.dp.toPx())
            if (selectedUrl != null) occupied += RectF(0f, size.height - 200.dp.toPx(),
                min(size.width, 464.dp.toPx()), size.height)
            // Keep receiver hit targets and city labels distinct at every zoom level.
            mapReceivers.forEach { receiver ->
                val p = point(receiver)
                if (p.x < -30.dp.toPx() || p.y < -30.dp.toPx() || p.x > size.width + 30.dp.toPx() || p.y > size.height + 30.dp.toPx()) return@forEach
                val radius = 9.dp.toPx()
                occupied += RectF(p.x - radius, p.y - radius, p.x + radius, p.y + radius)
            }
            cities.filter { it.minZoom <= 2f + log2(zoom) }.forEach { city ->
                val x = center.x + city.lon * unit
                val y = center.y - city.lat * unit
                val textWidth = cityPaint.measureText(city.name)
                val gap = 13.dp.toPx()
                val placement = listOf(
                    Offset(x + gap, y - labelSize / 2),
                    Offset(x - gap - textWidth, y - labelSize / 2),
                    Offset(x - textWidth / 2, y - gap - labelSize),
                    Offset(x - textWidth / 2, y + gap),
                ).firstOrNull { candidate ->
                    val bounds = RectF(candidate.x - 2.dp.toPx(), candidate.y - 2.dp.toPx(),
                        candidate.x + textWidth + 3.dp.toPx(), candidate.y + labelSize + 4.dp.toPx())
                    bounds.left >= 0 && bounds.top >= 0 && bounds.right < size.width && bounds.bottom < size.height &&
                        occupied.none { RectF.intersects(it, bounds) }
                }
                if (placement != null) {
                    val left = placement.x
                    val top = placement.y
                    val bounds = RectF(left - 2.dp.toPx(), top - 2.dp.toPx(),
                        left + textWidth + 3.dp.toPx(), top + labelSize + 4.dp.toPx())
                    occupied += bounds
                    drawCircle(Color(0xFFADB5B1), 1.5.dp.toPx(), Offset(x, y))
                    val baseline = top - cityPaint.fontMetrics.ascent
                    drawContext.canvas.nativeCanvas.drawText(city.name, left, baseline, cityOutline)
                    drawContext.canvas.nativeCanvas.drawText(city.name, left, baseline, cityPaint)
                }
            }
            mapReceivers.forEach { receiver ->
                val position = point(receiver)
                if (position.x < -30.dp.toPx() || position.y < -30.dp.toPx() || position.x > size.width + 30.dp.toPx() || position.y > size.height + 30.dp.toPx()) return@forEach
                val active = selectedUrl == receiver.url
                drawCircle(warm.copy(alpha = if (active) .22f else .10f), if (active) 15.dp.toPx() else 11.dp.toPx(), position)
                drawCircle(warm, 5.dp.toPx(), position)
                drawCircle(Color(0xFF0C1013), 2.dp.toPx(), position)
                if (active) drawCircle(warm, 11.dp.toPx(), position, style = Stroke(1.dp.toPx()))
            }
        }
        if (listMode) WebSdrServerList(mapReceivers, selectedUrl,
            onSelect = { selectedUrl = it.url; currentSelectionCallback(it) },
            modifier = Modifier.fillMaxSize().padding(top = 100.dp, start = 12.dp, end = 12.dp, bottom = 12.dp))
        TextButton(onClick = { listMode = !listMode },
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 56.dp)) {
            Text(if (listMode) "MAP" else "LIST", color = warm, fontSize = 10.sp)
        }
        Column(Modifier.align(Alignment.TopStart).padding(12.dp)
            .background(Color(0xE60C1013), RoundedCornerShape(4.dp)).padding(6.dp)) {
            Text("WEBSDR", color = warm, fontSize = 12.sp, letterSpacing = 2.sp)
            val time = if (updatedAt > 0) SimpleDateFormat("dd MMM HH:mm", locale).format(Date(updatedAt)) else ""
            Text(when {
                loadFailed -> "Map unavailable"
                loading -> "Loading receivers…"
                directoryFailed && mapReceivers.isEmpty() -> "Unable to load receivers"
                else -> "${mapReceivers.size} receivers · ${if (cached) "cached" else "updated"} $time"
            }, color = muted, fontSize = 10.sp)
            if (directoryFailed && mapReceivers.isNotEmpty()) Text("Update failed · showing saved list", color = muted, fontSize = 10.sp)
            TextButton(onClick = { refresh++ }, enabled = !loading, contentPadding = PaddingValues(0.dp),
                modifier = Modifier.height(30.dp)) {
                Text("Refresh", color = if (loading) muted else warm, fontSize = 11.sp)
            }
        }
        if (!listMode) Column(Modifier.align(Alignment.TopEnd).padding(8.dp)
            .background(Color(0xEE151719), RoundedCornerShape(5.dp))) {
            TextButton(onClick = { transform(1.5f) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(36.dp)) {
                Text("+", color = warm, fontSize = 22.sp)
            }
            TextButton(onClick = { transform(1 / 1.5f) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(36.dp)) {
                Text("−", color = warm, fontSize = 22.sp)
            }
            TextButton(onClick = { zoom = 1f; panX = 0f; panY = 0f }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(36.dp)) {
                Text("↺", color = warm, fontSize = 20.sp)
            }
        }
        if (!listMode) mapReceivers.firstOrNull { it.url == selectedUrl }?.let { receiver ->
            Column(Modifier.align(Alignment.BottomStart).padding(12.dp)
                .widthIn(max = 440.dp).heightIn(max = 180.dp)
                .background(Color(0xF5151719), RoundedCornerShape(6.dp))
                .border(1.dp, Color(0xFF765333), RoundedCornerShape(6.dp)).padding(12.dp)
                .verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Html.fromHtml(receiver.name, Html.FROM_HTML_MODE_LEGACY).toString().trim(),
                        modifier = Modifier.weight(1f), color = warm, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)

                }
                Text(listOfNotNull(receiver.location.takeIf { it.isNotBlank() }, receiver.users?.let { "$it listeners" })
                    .joinToString(" · "), color = Color(0xFFF4E8D7), fontSize = 12.sp)
                Text(receiver.url, color = muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                receiver.bands.forEach { band ->
                    Text(String.format(Locale.US, "%.3f–%.3f MHz", band.lowMHz, band.highMHz), color = Color(0xFFF4E8D7), fontSize = 11.sp)
                    if (band.antenna.isNotBlank()) Text(Html.fromHtml(band.antenna, Html.FROM_HTML_MODE_LEGACY)
                        .toString().trim(), color = muted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Text("Natural Earth", color = muted, fontSize = 9.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp))
    }
}
